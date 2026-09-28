# Relatório Técnico - Sistema de Reservas: Concorrência, Sincronização e Distribuição

**Disciplina:** Programação Concorrente e Distribuída  
**Instituição:** IFSC - Câmpus Gaspar  
**Linguagem:** Java  

## 1. Objetivo

O projeto implementa um sistema de reservas de assentos para um evento, com múltiplos clientes acessando o serviço simultaneamente. O objetivo principal é demonstrar, de forma experimental, uma condição de corrida sobre dados compartilhados, aplicar sincronização para corrigir a inconsistência e executar o serviço com duas réplicas, mantendo disponibilidade em caso de falha de uma delas.

A comunicação principal utiliza TCP. Cada conexão aceita pelo servidor é atendida em uma thread própria. A consulta de estado `STATUS` utiliza UDP. Para a parte distribuída foi adotada uma estratégia de replicação primária-backup.

## 2. Arquitetura

A solução é formada por cinco componentes:

1. **Réplica primária:** recebe normalmente as operações dos clientes pela porta TCP 5000 e responde ao `STATUS` pela porta UDP 6000.
2. **Réplica backup:** recebe o estado replicado pela porta TCP 7001, atende `STATUS` pela porta UDP 6001 e mantém uma porta TCP 5001 para assumir os clientes em caso de falha da primária.
3. **Clientes interativos:** permitem listar, reservar e cancelar assentos.
4. **Cliente de teste concorrente:** cria três conexões simultâneas e executa dez reservas por conexão, totalizando 30 requisições.
5. **Cliente UDP:** envia `STATUS` sem criar uma conexão TCP.

Em operação normal, o backup permanece passivo. A primária envia snapshots de estado após as operações e também periodicamente como heartbeat. Se o backup deixar de receber a primária por mais de aproximadamente três segundos, ele é promovido a ativo.

## 3. Protocolo de aplicação

| Comando | Transporte | Função | Resposta |
|---|---|---|---|
| `RESERVAR|<assento>|<user>` | TCP | Reservar assento | `OK|RESERVADO` ou `ERRO|motivo` |
| `CANCELAR|<assento>|<user>` | TCP | Cancelar uma reserva | `OK|CANCELADO` ou `ERRO|motivo` |
| `LISTAR` | TCP | Listar assentos livres | `LISTA|1,4,7,...` |
| `STATUS` | UDP | Consultar estado da réplica | `STATUS|CLIENTES=...|REQ=...` |
| `SAIR` | TCP | Encerrar sessão | `BYE` |

## 4. Experimento de concorrência

### 4.1 Recurso compartilhado e região crítica

Os recursos compartilhados são o vetor lógico de assentos e o contador de requisições processadas. A região crítica corresponde à sequência de operações de leitura e escrita necessária para verificar o estado atual e atualizar esses recursos.

Uma reserva não é apenas uma escrita isolada. Ela envolve uma operação composta de **check-then-act**:

1. verificar se o assento está livre;
2. decidir se a reserva pode ser feita;
3. gravar o usuário como proprietário do assento.

Da mesma forma, o incremento do contador envolve ler o valor atual e depois gravar `valor + 1`. Sem exclusão mútua, duas ou mais threads podem ler o mesmo valor anterior e sobrescrever a atualização umas das outras.

### 4.2 Teste sem sincronização

O teste usa três clientes simultâneos, cada um enviando dez requisições. Os três disputam os mesmos assentos de 1 a 10, o que amplia intencionalmente a janela de concorrência.

Também existe um pequeno atraso artificial dentro da região crítica exclusivamente para tornar a condição de corrida facilmente reproduzível durante a demonstração.

Na execução de validação deste projeto, foram observados:

| Métrica | Resultado |
|---|---:|
| Requisições enviadas | 30 |
| Contador final do servidor (`REQ`) | 10 |
| Respostas `OK` de reserva | 30 |
| Assentos efetivamente ocupados | 10 |

O resultado é inconsistente por dois motivos. Primeiro, houve perda de atualizações no contador: três threads leram repetidamente o mesmo valor anterior, portanto 30 requisições resultaram em apenas 10 incrementos observáveis. Segundo, vários clientes receberam `OK` para o mesmo assento, embora no estado final exista apenas um proprietário.

Exemplo de log:

```text
[THREAD=Client-A] REQ contador antes=0 depois=1
[THREAD=Client-B] REQ contador antes=0 depois=1
[THREAD=Client-C] REQ contador antes=0 depois=1
```

Esse comportamento caracteriza a condição de corrida: o resultado depende da ordem de interleaving entre as threads e não representa corretamente todas as operações executadas.

### 4.3 Teste com sincronização

Na segunda execução foi utilizado `synchronized (criticalSection)` em torno da região crítica. O mesmo teste de 30 requisições foi repetido sem alterar a quantidade de clientes ou o padrão de acesso.

Na execução de validação foram observados:

| Métrica | Resultado |
|---|---:|
| Requisições enviadas | 30 |
| Contador final do servidor (`REQ`) | 30 |
| Reservas válidas | 10 |
| Requisições rejeitadas por assento ocupado | 20 |
| Assentos efetivamente ocupados | 10 |

O total de reservas válidas é 10 porque os três clientes disputam os mesmos dez assentos. O ponto relevante é que as 30 requisições foram processadas corretamente e cada assento terminou com, no máximo, um proprietário. As outras 20 tentativas foram rejeitadas de forma consistente.

O mecanismo funciona porque, durante a região crítica, apenas uma thread por vez pode executar a sequência de verificação e atualização. Assim, não existe interleaving entre o teste de disponibilidade e a gravação do assento.

## 5. Sistema distribuído e replicação

A estratégia escolhida foi **primária-backup**. A primária é responsável pelas operações normais de escrita. O backup recebe snapshots contendo o vetor de assentos, o contador de requisições e uma versão monotônica do estado.

Após cada operação, a primária tenta enviar o snapshot ao backup e aguarda um ACK. Além disso, snapshots periódicos servem como heartbeat e também permitem atualizar o backup durante a execução.

Foi usada uma numeração de versão para impedir que uma atualização antiga, recebida fora de ordem, sobrescreva um snapshot mais novo.

Essa estratégia foi escolhida por ser mais simples de implementar e demonstrar do que quorum ou consenso completo, ao mesmo tempo em que permite manter uma segunda cópia do estado e realizar failover.

## 6. Tolerância a falhas

Quando a primária cai, o backup deixa de receber os heartbeats. Após aproximadamente três segundos, ele muda de `PASSIVO` para `ATIVO`. O cliente possui os dois endereços configurados e, ao detectar a quebra da conexão com a primária, tenta a réplica seguinte.

Na validação do projeto, uma reserva foi feita na primária e apareceu no `STATUS` do backup com a mesma versão. Depois que a primária foi encerrada, o backup foi promovido e uma nova reserva foi processada normalmente pela porta TCP 5001.

Para reduzir risco de duplicação durante o failover, reservar o mesmo assento novamente com o mesmo usuário é tratado de forma idempotente. Isso cobre o caso em que a primária aplicou e replicou uma reserva, mas caiu antes de a resposta chegar ao cliente.

## 7. TCP e UDP

O TCP foi escolhido para as operações principais de reserva porque oferece comunicação orientada à conexão, entrega confiável e preservação da ordem dos bytes. Isso é adequado para comandos que alteram o estado do sistema.

O UDP foi usado no comando `STATUS` por ser uma consulta pequena e independente, sem necessidade de manter uma conexão. Nesse caso, perder uma resposta não altera o estado do sistema; o cliente pode simplesmente consultar novamente.

## 8. Respostas às perguntas do relatório

### 1. O que caracteriza este sistema como distribuído?

O sistema possui múltiplos processos de servidor independentes, acessíveis pela rede, que mantêm cópias do mesmo estado e cooperam por meio de mensagens de replicação. Além disso, o cliente pode mudar de uma réplica para outra quando ocorre uma falha.

### 2. Qual é o recurso compartilhado e qual é a região crítica?

Os principais recursos compartilhados são os assentos e o contador de requisições. A região crítica é a sequência em que o servidor lê o estado atual, verifica uma condição e realiza a atualização correspondente. No código, ela é protegida pelo bloco `synchronized (criticalSection)` quando o modo sincronizado está ativo.

### 3. O que é uma condição de corrida e como foi produzida?

É uma situação em que o resultado depende da ordem de execução concorrente das threads. Foi produzida removendo a exclusão mútua e executando três clientes simultâneos. Um atraso curto dentro da operação aumenta a chance de múltiplas threads lerem o mesmo valor antes de qualquer uma concluir a escrita.

### 4. Qual foi a inconsistência numérica observada?

Na execução de validação sem sincronização, 30 requisições foram enviadas, mas o contador final indicou `REQ=10`. Com sincronização, o mesmo teste terminou em `REQ=30`.

### 5. Como a sincronização resolve o problema?

O `synchronized` garante exclusão mútua sobre a região crítica. Enquanto uma thread verifica e atualiza o estado, as demais aguardam. Isso impede perda de incrementos e evita que dois clientes confirmem corretamente a mesma reserva ao mesmo tempo.

### 6. Qual estratégia de replicação foi usada e por quê?

Foi utilizada primária-backup. Ela foi escolhida por permitir uma implementação direta: uma réplica processa as escritas e envia o estado à outra, reduzindo a complexidade em comparação com algoritmos completos de consenso.

### 7. O que acontece quando uma réplica cai?

Se o backup cair, a primária continua atendendo e apenas registra que a replicação está indisponível. Se a primária cair, o backup detecta a ausência de heartbeat, torna-se ativo e o cliente tenta se reconectar a ele.

### 8. Qual a diferença entre TCP e UDP e onde foram usados?

TCP é orientado à conexão, confiável e ordenado; foi usado em `LISTAR`, `RESERVAR`, `CANCELAR`, `SAIR` e na replicação. UDP não é orientado à conexão e não garante entrega ou ordem; foi usado apenas na consulta `STATUS`.

### 9. Quais são as limitações conhecidas?

A solução é acadêmica e possui limitações importantes: não implementa consenso real; pode existir split-brain em cenários de particionamento de rede mais complexos; o estado é mantido apenas em memória; não existe autenticação; os endereços e portas são fixos; e o mecanismo de failover depende de um timeout simples de heartbeat. Em produção seriam necessários persistência, autenticação, descoberta de serviço, TLS e um protocolo de consenso ou armazenamento transacional adequado.

## 9. Conclusão

O experimento mostra que o simples uso de múltiplas threads não garante consistência. A execução sem sincronização produz resultados incorretos porque várias threads modificam o mesmo estado sem exclusão mútua. Com a proteção da região crítica, as 30 requisições são contabilizadas corretamente e a regra de uma única reserva por assento é preservada.

A adição de uma segunda réplica amplia o problema para o contexto distribuído. A estratégia primária-backup permite manter uma cópia do estado e continuar atendendo após a queda da primária, demonstrando na prática concorrência, sincronização, comunicação TCP/UDP, replicação e tolerância a falhas.

# Relatório Técnico — Sistema de Reservas

**Disciplina:** Programação Concorrente e Distribuída  
**Curso:** Análise e Desenvolvimento de Sistemas — IFSC Câmpus Gaspar

## 1. Objetivo

A atividade teve como objetivo desenvolver um sistema simples de reservas para demonstrar, na prática, conceitos de concorrência, sincronização, comunicação em rede, replicação e tolerância a falhas.

O sistema possui 50 assentos e permite que vários clientes se conectem ao mesmo tempo para listar, reservar e cancelar assentos.

## 2. Concorrência e condição de corrida

Cada cliente conectado ao servidor é atendido por uma thread própria. Como todas as threads acessam o mesmo estado dos assentos e o mesmo contador de requisições, podem ocorrer problemas caso esse acesso não seja controlado.

Para demonstrar isso, foi realizado um teste com **3 clientes**, cada um enviando **10 requisições**, totalizando **30 requisições concorrentes**.

### Teste sem sincronização

Sem proteção da região crítica, várias threads podem ler e alterar o mesmo valor praticamente ao mesmo tempo. Isso gera uma condição de corrida e pode causar perda de atualizações.

**Resultado do teste:**

- Requisições enviadas: 30
- Contador final: 30
- Sincronização: não

O valor final ficou diferente de 30 porque algumas atualizações foram sobrescritas durante o acesso concorrente.

### Teste com sincronização

O mesmo teste foi repetido utilizando `synchronized` para proteger a região crítica.

**Resultado:**

- Requisições enviadas: 30
- Requisições processadas: 30
- Sincronização: sim

Com a sincronização, somente uma thread por vez executa a parte protegida do código, evitando a perda de atualizações.

## 3. Comunicação TCP e UDP

As operações principais do sistema utilizam TCP, como:

- listar assentos;
- reservar;
- cancelar;
- encerrar conexão.

O TCP foi utilizado por fornecer comunicação confiável e ordenada.

Também foi implementada uma consulta de status via UDP. Nesse caso, o UDP é suficiente porque a consulta não altera o estado do sistema e pode ser realizada novamente caso algum pacote seja perdido.

## 4. Replicação e tolerância a falhas

O sistema utiliza dois servidores:

- **Servidor primário**
- **Servidor backup**

Em funcionamento normal, o servidor primário atende os clientes e envia seu estado atualizado ao backup.

O backup mantém uma cópia das reservas e permanece passivo enquanto a primária está ativa.

Caso a primária deixe de responder por alguns segundos, o backup detecta a falha e passa para o estado ativo. O cliente então tenta se conectar ao backup e continua utilizando o sistema com o estado que já havia sido replicado.

A estratégia utilizada foi **primária-backup**, escolhida por ser simples e suficiente para demonstrar replicação e tolerância a falhas na atividade.

## 5. Região crítica

O principal recurso compartilhado é o estado dos assentos.

A região crítica corresponde à parte do código em que o servidor verifica se um assento está disponível e depois altera seu estado.

Essas operações precisam ser protegidas juntas para evitar que duas threads reservem o mesmo assento ou alterem o mesmo dado ao mesmo tempo.

## 6. Desconexão de cliente

Como cada cliente é atendido por uma thread separada, a desconexão de um cliente não encerra o servidor.

A thread daquele cliente é finalizada e os demais continuam sendo atendidos normalmente.

## 7. Limitações

A solução foi desenvolvida para fins acadêmicos e possui algumas limitações:

- os dados ficam armazenados apenas em memória;
- não existe autenticação de usuários;
- a detecção de falha da primária utiliza um tempo limite simples;
- a replicação utilizada é simplificada em relação a sistemas distribuídos reais.

Mesmo assim, a implementação atende aos objetivos da atividade e permite demonstrar os principais conceitos estudados.

## 8. Conclusão

A atividade permitiu observar na prática como o acesso simultâneo a um recurso compartilhado pode causar inconsistências.

Sem sincronização, ocorreram perdas de atualização causadas pela condição de corrida. Com o uso de `synchronized`, o mesmo teste passou a produzir um resultado consistente.

Também foram utilizados TCP e UDP para diferentes tipos de comunicação, além de dois servidores com replicação primária-backup e failover.

Com isso, o projeto reuniu os principais conceitos de concorrência, sincronização, comunicação em rede, sistemas distribuídos e tolerância a falhas.

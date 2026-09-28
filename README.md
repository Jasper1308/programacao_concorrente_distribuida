# Sistema de Reservas - Programação Concorrente e Distribuída (IFSC)

Projeto em Java sem bibliotecas externas para demonstrar:

- servidor TCP concorrente com uma thread por cliente;
- pelo menos 3 clientes simultâneos;
- comandos `LISTAR`, `RESERVAR`, `CANCELAR` e `SAIR`;
- `STATUS` via UDP;
- condição de corrida reproduzível;
- correção da região crítica com `synchronized`;
- duas réplicas com estratégia primária-backup;
- replicação de estado;
- failover automático para a réplica backup;
- retry idempotente de reserva após falha de conexão.

## 1. Requisitos

- JDK 8 ou superior (`java -version` e `javac -version`)
- Terminal / PowerShell / Prompt de Comando

## 2. Compilar

### Windows

```bat
compile.bat
```

Ou manualmente:

```bat
mkdir out
javac -d out src\*.java
```

### Linux/macOS

```bash
mkdir -p out
javac -d out src/*.java
```

## 3. Arquitetura e portas

| Processo | TCP clientes | UDP STATUS | Replicação |
|---|---:|---:|---:|
| Primária | 5000 | 6000 | envia para 7001 |
| Backup | 5001 | 6001 | recebe em 7001 |

Estratégia: **primária-backup com replicação síncrona por snapshot**. A primária envia o estado ao backup após cada operação e também a cada 1 segundo como heartbeat. O backup inicia passivo. Se ficar mais de 3 segundos sem receber a primária, é promovido automaticamente a ativo.

## 4. Teste 1 - condição de corrida SEM sincronização

Abra 3 terminais.

### Terminal 1 - backup

```bat
java -cp out BackupServer false
```

### Terminal 2 - primária

```bat
java -cp out PrimaryServer false
```

### Terminal 3 - teste concorrente

```bat
java -cp out StressTest
```

O `StressTest` cria **3 clientes simultâneos**, cada um enviando **10 reservas**, totalizando 30 requisições. Os três disputam os mesmos assentos 1 a 10.

Resultado esperado no modo sem sincronização:

- múltiplos clientes podem receber `OK|RESERVADO` para o mesmo assento;
- o contador compartilhado `REQ` perde incrementos;
- no teste de validação deste projeto, foram enviadas 30 requisições, mas `STATUS` terminou com `REQ=10`;
- isso comprova a condição de corrida, pois três threads leram o mesmo valor anterior e sobrescreveram umas às outras.

Exemplo de log:

```text
[THREAD=Client-A] REQ contador antes=0 depois=1
[THREAD=Client-B] REQ contador antes=0 depois=1
[THREAD=Client-C] REQ contador antes=0 depois=1
```

**Feche as duas réplicas antes do próximo teste.**

## 5. Teste 2 - COM sincronização

### Terminal 1

```bat
java -cp out BackupServer true
```

### Terminal 2

```bat
java -cp out PrimaryServer true
```

### Terminal 3

```bat
java -cp out StressTest
```

Resultado esperado:

- `STATUS ... REQ=30 ... SYNC=SIM`;
- somente um cliente consegue reservar cada assento;
- como os três clientes disputam os mesmos 10 assentos, aparecem 10 reservas válidas e 20 rejeições por assento ocupado;
- as 30 requisições foram processadas corretamente, sem perda de atualização.

## 6. STATUS via UDP

Primária:

```bat
java -cp out UdpStatusClient 127.0.0.1 6000
```

Backup:

```bat
java -cp out UdpStatusClient 127.0.0.1 6001
```

Exemplo:

```text
STATUS|CLIENTES=0|REQ=30|RESERVADOS=10|PAPEL=PRIMARIA|ATIVO|SYNC=SIM|VERSAO=30
```

## 7. Teste manual com clientes

Com as duas réplicas em execução:

```bat
java -cp out ClientMain adrian
```

Em outros terminais:

```bat
java -cp out ClientMain cliente2
java -cp out ClientMain cliente3
```

Comandos no cliente:

```text
LISTAR
RESERVAR|12
CANCELAR|12
STATUS
SAIR
```

O nome do usuário é acrescentado automaticamente pelo cliente ao protocolo.

## 8. Teste de tolerância a falhas / failover

1. Inicie `BackupServer true`.
2. Inicie `PrimaryServer true`.
3. Inicie `ClientMain adrian`.
4. Reserve um assento, por exemplo `RESERVAR|25`.
5. Consulte o backup via UDP e confirme que ele possui o mesmo estado.
6. Encerre a **primária** com `Ctrl+C`.
7. Aguarde aproximadamente 3 segundos.
8. No cliente, envie outra reserva.
9. O cliente detecta a queda e tenta a réplica backup; o backup passa a `ATIVO` e continua processando.

O retry é idempotente: se a primária confirmar internamente uma reserva e cair antes de devolver a resposta, repetir `RESERVAR` com o mesmo usuário não cria uma segunda reserva.

## 9. Protocolo principal

| Comando | Transporte | Função | Resposta típica |
|---|---|---|---|
| `RESERVAR|<assento>|<user>` | TCP | Reserva um assento | `OK|RESERVADO` ou `ERRO|motivo` |
| `CANCELAR|<assento>|<user>` | TCP | Cancela reserva do usuário | `OK|CANCELADO` ou `ERRO|motivo` |
| `LISTAR` | TCP | Lista assentos livres | `LISTA|1,4,7,...` |
| `STATUS` | UDP | Consulta estado da réplica | `STATUS|CLIENTES=...|REQ=...` |
| `SAIR` | TCP | Encerra a sessão | `BYE` |

## 10. Onde está a região crítica

A região crítica está em `ReservationState.java`, nas operações compostas que:

1. leem o contador compartilhado;
2. calculam e gravam o novo valor;
3. verificam se o assento está livre;
4. gravam o usuário no assento.

No modo sem sincronização, várias threads podem executar essas etapas ao mesmo tempo. No modo com sincronização, o bloco `synchronized (criticalSection)` permite que apenas uma thread execute a região crítica por vez.

## 11. Como salvar logs reais para a entrega

Exemplo no Windows PowerShell:

```powershell
java -cp out PrimaryServer false 2>&1 | Tee-Object logs\servidor_sem_sync.txt
```

Em outro terminal:

```powershell
java -cp out StressTest 2>&1 | Tee-Object logs\teste_sem_sync.txt
```

Repita com `true` e salve como `servidor_com_sync.txt` e `teste_com_sync.txt`.

## 12. Git

```bash
git init
git add .
git commit -m "Sistema de reservas concorrente e distribuido"
```

Depois crie um repositório remoto e faça o push normalmente.

## Arquivos principais

- `src/ReservationState.java`: estado dos assentos, região crítica e snapshots.
- `src/PrimaryServer.java`: servidor TCP primário + UDP + replicação.
- `src/BackupServer.java`: réplica backup + failover automático.
- `src/ClientMain.java`: cliente interativo com reconexão/failover.
- `src/StressTest.java`: 3 clientes x 10 requisições.
- `src/UdpStatusClient.java`: teste específico do protocolo UDP.
- `RELATORIO_TECNICO.md`: relatório pronto para adaptar com os seus logs.
- `APRESENTACAO_10_MIN.md`: roteiro da apresentação.
- `PROTOCOLO.md`: protocolo documentado.
- `DIAGRAMA_ARQUITETURA.md`: diagrama da solução.

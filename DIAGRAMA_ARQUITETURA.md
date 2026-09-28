# Diagrama de Arquitetura

```text
                         TCP 5000
+-----------+          +--------------------+
| Cliente 1 |--------->|                    |
+-----------+          |  RÉPLICA PRIMÁRIA  |---- UDP 6000 (STATUS)
+-----------+          |                    |
| Cliente 2 |--------->|  Thread por cliente|
+-----------+          +---------+----------+
+-----------+                    |
| Cliente 3 |--------->          | TCP 7001
+-----------+                     | snapshot + heartbeat
                                  v
                         +--------------------+
                         |   RÉPLICA BACKUP   |---- UDP 6001 (STATUS)
                         |   TCP clientes 5001|
                         | PASSIVO -> ATIVO   |
                         +--------------------+
                                  ^
                                  |
                       failover automático do cliente
```

## Fluxo normal

1. Clientes usam a primária pela porta TCP 5000.
2. Cada conexão é atendida por uma thread própria.
3. A primária atualiza os assentos.
4. A primária replica o snapshot ao backup pela porta 7001.
5. O backup confirma com ACK e permanece passivo.

## Falha da primária

1. Backup deixa de receber snapshots/heartbeat.
2. Após aproximadamente 3 segundos, o backup é promovido a ativo.
3. O cliente detecta a queda da conexão TCP com a primária.
4. O cliente reconecta na porta TCP 5001 do backup.
5. As reservas já replicadas continuam disponíveis sem duplicação.

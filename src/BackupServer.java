import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class BackupServer {
    private final int tcpPort;
    private final int udpPort;
    private final int replicationPort;
    private final ReservationState state;
    private final AtomicInteger connectedClients = new AtomicInteger(0);

    private volatile boolean active = false;
    private volatile boolean seenPrimary = false;
    private volatile long lastReplicationAt = 0L;

    public BackupServer(int tcpPort, int udpPort, int replicationPort, boolean synchronizedMode) {
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.replicationPort = replicationPort;
        // Backup usa o mesmo modo para permitir a demonstração também após failover.
        this.state = new ReservationState(ReservationState.DEFAULT_SEATS, synchronizedMode);
    }

    public void start() throws IOException {
        Thread replication = new Thread(this::runReplicationListener, "REPLICATION-LISTENER");
        replication.setDaemon(true);
        replication.start();

        Thread monitor = new Thread(this::runPrimaryFailureMonitor, "PRIMARY-FAILURE-MONITOR");
        monitor.setDaemon(true);
        monitor.start();

        Thread udp = new Thread(this::runUdpStatus, "UDP-STATUS-BACKUP");
        udp.setDaemon(true);
        udp.start();

        try (ServerSocket serverSocket = new ServerSocket(tcpPort)) {
            System.out.printf("BACKUP iniciada | TCP=%d UDP=%d REPL=%d | aguardando primaria%n",
                    tcpPort, udpPort, replicationPort);

            while (true) {
                Socket socket = serverSocket.accept();
                connectedClients.incrementAndGet();
                Thread t = new Thread(() -> handleClient(socket),
                        "BackupClient-" + socket.getRemoteSocketAddress());
                t.start();
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket clientSocket = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            out.println("OK|CONECTADO|BACKUP|" + (active ? "ATIVO" : "PASSIVO"));
            String line;
            while ((line = in.readLine()) != null) {
                String response = processCommand(line);
                out.println(response);
                if ("BYE".equals(response)) break;
            }
        } catch (IOException e) {
            System.out.println("Cliente no backup desconectou: " + e.getMessage());
        } finally {
            connectedClients.decrementAndGet();
        }
    }

    private String processCommand(String line) {
        try {
            String[] parts = line.trim().split("\\|", 3);
            String cmd = parts[0].toUpperCase();

            switch (cmd) {
                case "LISTAR":
                    return "LISTA|" + state.listAvailable();
                case "RESERVAR":
                    if (!active) return "ERRO|BACKUP_PASSIVO";
                    if (parts.length < 3) return "ERRO|FORMATO_RESERVAR";
                    int seat = Integer.parseInt(parts[1]);
                    String user = parts[2];
                    ReservationState.OperationResult reserveResult = state.reserve(seat, user);
                    return (reserveResult.ok() ? "OK|" : "ERRO|") + reserveResult.message();
                case "CANCELAR":
                    if (!active) return "ERRO|BACKUP_PASSIVO";
                    if (parts.length < 3) return "ERRO|FORMATO_CANCELAR";
                    int cancelSeat = Integer.parseInt(parts[1]);
                    String cancelUser = parts[2];
                    ReservationState.OperationResult cancelResult = state.cancel(cancelSeat, cancelUser);
                    return (cancelResult.ok() ? "OK|" : "ERRO|") + cancelResult.message();
                case "SAIR":
                    return "BYE";
                default:
                    return "ERRO|COMANDO_DESCONHECIDO";
            }
        } catch (NumberFormatException e) {
            return "ERRO|ASSENTO_INVALIDO";
        } catch (Exception e) {
            return "ERRO|INTERNO|" + e.getClass().getSimpleName();
        }
    }

    private void runReplicationListener() {
        try (ServerSocket server = new ServerSocket(replicationPort)) {
            while (true) {
                try (Socket socket = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                    String line = in.readLine();
                    if (line != null && line.startsWith("SNAPSHOT|")) {
                        boolean applied = state.applySnapshotLine(line);
                        seenPrimary = true;
                        lastReplicationAt = System.currentTimeMillis();
                        if (active) {
                            System.out.println("[FAILOVER] Primaria voltou; backup retorna a PASSIVO.");
                        }
                        active = false;
                        out.println("ACK|" + (applied ? "APLICADO" : "IGNORADO") + "|VERSAO=" + state.getVersion());
                    } else {
                        out.println("ERRO|REPLICACAO_INVALIDA");
                    }
                } catch (Exception e) {
                    System.err.println("Erro em replicacao: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Listener de replicacao encerrado: " + e.getMessage());
        }
    }

    private void runPrimaryFailureMonitor() {
        while (true) {
            if (seenPrimary && !active) {
                long elapsed = System.currentTimeMillis() - lastReplicationAt;
                if (elapsed > 3000) {
                    active = true;
                    System.out.println("[FAILOVER] Primaria sem heartbeat por >3s. BACKUP PROMOVIDO A ATIVO.");
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void runUdpStatus() {
        byte[] buffer = new byte[1024];
        try (DatagramSocket socket = new DatagramSocket(udpPort)) {
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String request = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                if (!"STATUS".equalsIgnoreCase(request)) continue;

                String response = String.format(
                        "STATUS|CLIENTES=%d|REQ=%d|RESERVADOS=%d|PAPEL=BACKUP|%s|SYNC=%s|VERSAO=%d",
                        connectedClients.get(), state.getProcessedRequests(), state.getReservedCount(),
                        active ? "ATIVO" : "PASSIVO",
                        state.isSynchronizedMode() ? "SIM" : "NAO", state.getVersion());

                byte[] data = response.getBytes(StandardCharsets.UTF_8);
                DatagramPacket reply = new DatagramPacket(data, data.length, packet.getAddress(), packet.getPort());
                socket.send(reply);
            }
        } catch (IOException e) {
            System.err.println("UDP STATUS backup encerrado: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        boolean sync = args.length == 0 || Boolean.parseBoolean(args[0]);
        new BackupServer(5001, 6001, 7001, sync).start();
    }
}

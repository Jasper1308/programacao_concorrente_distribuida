import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class PrimaryServer {
    private static final String BACKUP_HOST = "127.0.0.1";
    private static final int BACKUP_REPLICATION_PORT = 7001;

    private final int tcpPort;
    private final int udpPort;
    private final ReservationState state;
    private final AtomicInteger connectedClients = new AtomicInteger(0);

    public PrimaryServer(int tcpPort, int udpPort, boolean synchronizedMode) {
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.state = new ReservationState(ReservationState.DEFAULT_SEATS, synchronizedMode);
    }

    public void start() throws IOException {
        Thread udpThread = new Thread(this::runUdpStatus, "UDP-STATUS-PRIMARY");
        udpThread.setDaemon(true);
        udpThread.start();

        Thread heartbeat = new Thread(this::runReplicationHeartbeat, "REPLICATION-HEARTBEAT");
        heartbeat.setDaemon(true);
        heartbeat.start();

        try (ServerSocket serverSocket = new ServerSocket(tcpPort)) {
            System.out.printf("PRIMARIA iniciada | TCP=%d UDP=%d | sincronizacao=%s%n",
                    tcpPort, udpPort, state.isSynchronizedMode() ? "COM" : "SEM");
            System.out.println("Aguardando clientes...");

            while (true) {
                Socket socket = serverSocket.accept();
                connectedClients.incrementAndGet();
                Thread t = new Thread(() -> handleClient(socket),
                        "Client-" + socket.getRemoteSocketAddress());
                t.start();
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket clientSocket = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            out.println("OK|CONECTADO|PRIMARIA");
            String line;
            while ((line = in.readLine()) != null) {
                String response = processCommand(line);
                out.println(response);
                if ("BYE".equals(response)) break;
            }
        } catch (IOException e) {
            System.out.println("Cliente desconectou abruptamente: " + e.getMessage());
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
                    if (parts.length < 3) return "ERRO|FORMATO_RESERVAR";
                    int seat = Integer.parseInt(parts[1]);
                    String user = parts[2];
                    ReservationState.OperationResult reserveResult = state.reserve(seat, user);
                    replicateSnapshot(); // commit no backup antes da resposta quando ele estiver disponivel
                    return (reserveResult.ok() ? "OK|" : "ERRO|") + reserveResult.message();
                case "CANCELAR":
                    if (parts.length < 3) return "ERRO|FORMATO_CANCELAR";
                    int cancelSeat = Integer.parseInt(parts[1]);
                    String cancelUser = parts[2];
                    ReservationState.OperationResult cancelResult = state.cancel(cancelSeat, cancelUser);
                    replicateSnapshot();
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

    private void runUdpStatus() {
        byte[] buffer = new byte[1024];
        try (DatagramSocket socket = new DatagramSocket(udpPort)) {
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String request = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                if (!"STATUS".equalsIgnoreCase(request)) continue;

                String response = String.format(
                        "STATUS|CLIENTES=%d|REQ=%d|RESERVADOS=%d|PAPEL=PRIMARIA|ATIVO|SYNC=%s|VERSAO=%d",
                        connectedClients.get(), state.getProcessedRequests(), state.getReservedCount(),
                        state.isSynchronizedMode() ? "SIM" : "NAO", state.getVersion());

                byte[] data = response.getBytes(StandardCharsets.UTF_8);
                DatagramPacket reply = new DatagramPacket(data, data.length, packet.getAddress(), packet.getPort());
                socket.send(reply);
            }
        } catch (IOException e) {
            System.err.println("UDP STATUS primaria encerrado: " + e.getMessage());
        }
    }

    private void runReplicationHeartbeat() {
        while (true) {
            replicateSnapshot();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void replicateSnapshot() {
        String snapshot = state.createSnapshotLine();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(BACKUP_HOST, BACKUP_REPLICATION_PORT), 300);
            socket.setSoTimeout(700);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                out.println(snapshot);
                String ack = in.readLine();
                if (ack == null || !ack.startsWith("ACK")) {
                    System.out.println("[REPLICACAO] backup sem ACK");
                }
            }
        } catch (IOException e) {
            // A primária deve continuar funcionando se o backup cair.
            System.out.println("[REPLICACAO] backup indisponivel; primaria segue ativa");
        }
    }

    public static void main(String[] args) throws Exception {
        boolean sync = args.length == 0 || Boolean.parseBoolean(args[0]);
        new PrimaryServer(5000, 6000, sync).start();
    }
}

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;
import java.util.Scanner;

public class ClientMain {
    private static class Endpoint {
        private final String name;
        private final String host;
        private final int tcpPort;
        private final int udpPort;

        Endpoint(String name, String host, int tcpPort, int udpPort) {
            this.name = name;
            this.host = host;
            this.tcpPort = tcpPort;
            this.udpPort = udpPort;
        }

        String name() { return name; }
        String host() { return host; }
        int tcpPort() { return tcpPort; }
        int udpPort() { return udpPort; }
    }

    private final String user;
    private final List<Endpoint> endpoints = Arrays.asList(
            new Endpoint("PRIMARIA", "127.0.0.1", 5000, 6000),
            new Endpoint("BACKUP", "127.0.0.1", 5001, 6001)
    );

    private int endpointIndex = 0;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    public ClientMain(String user) {
        this.user = user;
    }

    private boolean connectFrom(int startIndex) {
        closeCurrent();
        for (int offset = 0; offset < endpoints.size(); offset++) {
            int idx = (startIndex + offset) % endpoints.size();
            Endpoint ep = endpoints.get(idx);
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ep.host(), ep.tcpPort()), 700);
                s.setSoTimeout(4000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                String greeting = reader.readLine();

                this.socket = s;
                this.in = reader;
                this.out = writer;
                this.endpointIndex = idx;

                System.out.println("Conectado em " + ep.name() + " -> " + greeting);
                return true;
            } catch (IOException e) {
                System.out.println(ep.name() + " indisponivel: " + e.getMessage());
            }
        }
        return false;
    }

    private String sendWithFailover(String command) {
        for (int attempt = 0; attempt < 10; attempt++) {
            if (socket == null || socket.isClosed()) {
                if (!connectFrom(endpointIndex)) {
                    sleep(700);
                    continue;
                }
            }

            try {
                out.println(command);
                String response = in.readLine();
                if (response == null) throw new EOFException("servidor fechou a conexao");

                if (response.startsWith("ERRO|BACKUP_PASSIVO")) {
                    System.out.println("Backup ainda passivo; aguardando promocao...");
                    sleep(700);
                    continue;
                }
                return response;
            } catch (IOException e) {
                System.out.println("Conexao perdida com " + endpoints.get(endpointIndex).name() + ". Tentando failover...");
                int next = (endpointIndex + 1) % endpoints.size();
                closeCurrent();
                connectFrom(next);
                sleep(300);
            }
        }
        return "ERRO|FAILOVER_NAO_CONCLUIDO";
    }

    private String udpStatus() {
        Endpoint ep = endpoints.get(endpointIndex);
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setSoTimeout(1500);
            byte[] req = "STATUS".getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(req, req.length,
                    InetAddress.getByName(ep.host()), ep.udpPort());
            ds.send(packet);

            byte[] buffer = new byte[2048];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            ds.receive(reply);
            return new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "ERRO|UDP_STATUS|" + e.getMessage();
        }
    }

    public void runInteractive() {
        if (!connectFrom(0)) {
            System.out.println("Nenhuma replica disponivel agora. O cliente continuara tentando ao enviar comandos.");
        }

        System.out.println("\nComandos:");
        System.out.println("  LISTAR");
        System.out.println("  RESERVAR|<assento>   (usuario preenchido automaticamente)");
        System.out.println("  CANCELAR|<assento>   (usuario preenchido automaticamente)");
        System.out.println("  STATUS               (UDP)");
        System.out.println("  SAIR\n");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print(user + "> ");
                String raw = scanner.nextLine().trim();
                if (raw.isEmpty()) continue;

                if (raw.equalsIgnoreCase("STATUS")) {
                    System.out.println(udpStatus());
                    continue;
                }

                String command = raw;
                String upper = raw.toUpperCase();
                if (upper.startsWith("RESERVAR|")) {
                    command = "RESERVAR|" + raw.substring(raw.indexOf('|') + 1) + "|" + user;
                } else if (upper.startsWith("CANCELAR|")) {
                    command = "CANCELAR|" + raw.substring(raw.indexOf('|') + 1) + "|" + user;
                }

                String response = sendWithFailover(command);
                System.out.println(response);
                if (raw.equalsIgnoreCase("SAIR")) break;
            }
        } finally {
            closeCurrent();
        }
    }

    private void closeCurrent() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        socket = null;
        in = null;
        out = null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        String user = args.length > 0 ? args[0] : "cliente";
        new ClientMain(user).runInteractive();
    }
}

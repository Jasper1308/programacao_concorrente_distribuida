import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class StressTest {
    private static final int CLIENTS = 3;
    private static final int REQUESTS_PER_CLIENT = 10;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int tcpPort = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        int udpPort = args.length > 2 ? Integer.parseInt(args[2]) : 6000;

        CyclicBarrier barrier = new CyclicBarrier(CLIENTS);
        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        List<String> responses = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        System.out.println("=== TESTE DE CONCORRENCIA: 3 clientes x 10 requisicoes = 30 ===");
        System.out.println("Todos disputam os mesmos assentos 1..10 para expor a corrida check-then-write.");

        for (int c = 1; c <= CLIENTS; c++) {
            final int clientId = c;
            futures.add(pool.submit(() -> {
                String user = "cliente" + clientId;
                try (Socket socket = new Socket(host, tcpPort);
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                    String greeting = in.readLine();
                    System.out.println(user + " conectado: " + greeting);
                    barrier.await();

                    for (int seat = 1; seat <= REQUESTS_PER_CLIENT; seat++) {
                        String command = "RESERVAR|" + seat + "|" + user;
                        out.println(command);
                        String response = in.readLine();
                        String row = user + " -> assento " + seat + " -> " + response;
                        responses.add(row);
                        if (response != null && response.startsWith("OK|")) ok.incrementAndGet();
                        else errors.incrementAndGet();
                    }
                    out.println("SAIR");
                    in.readLine();
                } catch (Exception e) {
                    responses.add(user + " -> FALHA: " + e.getMessage());
                    errors.addAndGet(REQUESTS_PER_CLIENT);
                }
                return null;
            }));
        }

        for (Future<?> f : futures) f.get();
        pool.shutdown();

        System.out.println("\n--- RESPOSTAS DOS 30 PEDIDOS ---");
        responses.stream().sorted().forEach(System.out::println);

        System.out.println("\n--- RESUMO DO CLIENTE DE TESTE ---");
        System.out.println("Total enviado: 30");
        System.out.println("Respostas OK: " + ok.get());
        System.out.println("Respostas ERRO: " + errors.get());
        System.out.println("Observacao: no modo SEM sincronizacao pode haver mais de um OK para o mesmo assento.");

        System.out.println("\n--- STATUS UDP DO SERVIDOR ---");
        System.out.println(queryStatus(host, udpPort));
        System.out.println("Compare REQ com 30. COM sincronizacao deve ser 30/30; SEM sincronizacao tende a perder incrementos.");
    }

    private static String queryStatus(String host, int udpPort) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            byte[] req = "STATUS".getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(req, req.length, InetAddress.getByName(host), udpPort);
            socket.send(packet);

            byte[] buffer = new byte[2048];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            socket.receive(reply);
            return new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "ERRO|STATUS_UDP|" + e.getMessage();
        }
    }
}

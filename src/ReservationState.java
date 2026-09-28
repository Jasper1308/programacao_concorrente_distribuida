import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class ReservationState {
    public static final int DEFAULT_SEATS = 50;
    private static final int RACE_DELAY_MS = 15;

    private final AtomicReferenceArray<String> seats;
    private final Object criticalSection = new Object();
    private final boolean synchronizedMode;
    private volatile int processedRequests = 0;
    private final AtomicLong version = new AtomicLong(0);

    public ReservationState(int seatCount, boolean synchronizedMode) {
        this.seats = new AtomicReferenceArray<>(seatCount);
        this.synchronizedMode = synchronizedMode;
    }

    public OperationResult reserve(int seatNumber, String user) {
        OperationResult result;
        if (synchronizedMode) {
            synchronized (criticalSection) {
                result = reserveUnsafe(seatNumber, user);
            }
        } else {
            result = reserveUnsafe(seatNumber, user);
        }
        long v = version.incrementAndGet();
        return new OperationResult(result.ok(), result.message(), v);
    }

    public OperationResult cancel(int seatNumber, String user) {
        OperationResult result;
        if (synchronizedMode) {
            synchronized (criticalSection) {
                result = cancelUnsafe(seatNumber, user);
            }
        } else {
            result = cancelUnsafe(seatNumber, user);
        }
        long v = version.incrementAndGet();
        return new OperationResult(result.ok(), result.message(), v);
    }

    private OperationResult reserveUnsafe(int seatNumber, String user) {
        int before = processedRequests;
        sleepQuietly(RACE_DELAY_MS);
        processedRequests = before + 1; // propositalmente não atômico no modo SEM sincronização
        int after = processedRequests;

        System.out.printf("[THREAD=%s] REQ contador antes=%d depois=%d | RESERVAR assento=%d user=%s%n",
                Thread.currentThread().getName(), before, after, seatNumber, user);

        if (!validSeat(seatNumber)) {
            return new OperationResult(false, "ASSENTO_INVALIDO", version.get());
        }

        int idx = seatNumber - 1;
        String currentOwner = seats.get(idx);

        if (user.equals(currentOwner)) {
            // Torna retry de failover idempotente: se a resposta do primário se perder,
            // o mesmo cliente pode repetir sem gerar uma reserva duplicada.
            return new OperationResult(true, "JA_RESERVADO_POR_VOCE", version.get());
        }
        if (currentOwner != null) {
            return new OperationResult(false, "ASSENTO_OCUPADO_POR_" + currentOwner, version.get());
        }

        // Janela proposital para tornar a corrida observável no experimento sem sincronização.
        sleepQuietly(RACE_DELAY_MS);
        seats.set(idx, user);
        return new OperationResult(true, "RESERVADO", version.get());
    }

    private OperationResult cancelUnsafe(int seatNumber, String user) {
        int before = processedRequests;
        sleepQuietly(RACE_DELAY_MS);
        processedRequests = before + 1;
        int after = processedRequests;

        System.out.printf("[THREAD=%s] REQ contador antes=%d depois=%d | CANCELAR assento=%d user=%s%n",
                Thread.currentThread().getName(), before, after, seatNumber, user);

        if (!validSeat(seatNumber)) {
            return new OperationResult(false, "ASSENTO_INVALIDO", version.get());
        }

        int idx = seatNumber - 1;
        String currentOwner = seats.get(idx);
        if (currentOwner == null) {
            // Também idempotente para retry após falha de conexão.
            return new OperationResult(true, "JA_ESTAVA_LIVRE", version.get());
        }
        if (!user.equals(currentOwner)) {
            return new OperationResult(false, "RESERVA_PERTENCE_A_" + currentOwner, version.get());
        }

        sleepQuietly(RACE_DELAY_MS);
        seats.set(idx, null);
        return new OperationResult(true, "CANCELADO", version.get());
    }

    public String listAvailable() {
        List<String> available = new ArrayList<>();
        for (int i = 0; i < seats.length(); i++) {
            if (seats.get(i) == null) {
                available.add(String.valueOf(i + 1));
            }
        }
        return String.join(",", available);
    }

    public String listReserved() {
        List<String> reserved = new ArrayList<>();
        for (int i = 0; i < seats.length(); i++) {
            String owner = seats.get(i);
            if (owner != null) {
                reserved.add((i + 1) + "=" + owner);
            }
        }
        return String.join(",", reserved);
    }

    public int getProcessedRequests() {
        return processedRequests;
    }

    public int getReservedCount() {
        int total = 0;
        for (int i = 0; i < seats.length(); i++) {
            if (seats.get(i) != null) total++;
        }
        return total;
    }

    public boolean isSynchronizedMode() {
        return synchronizedMode;
    }

    public long getVersion() {
        return version.get();
    }

    public int seatCount() {
        return seats.length();
    }

    public String createSnapshotLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("SNAPSHOT|")
          .append(version.get()).append('|')
          .append(processedRequests).append('|')
          .append(seats.length()).append('|');

        for (int i = 0; i < seats.length(); i++) {
            if (i > 0) sb.append(',');
            String owner = seats.get(i);
            if (owner == null) {
                sb.append("-");
            } else {
                sb.append(Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(owner.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return sb.toString();
    }

    public synchronized boolean applySnapshotLine(String line) {
        String[] parts = line.split("\\|", 5);
        if (parts.length != 5 || !"SNAPSHOT".equals(parts[0])) {
            return false;
        }

        long incomingVersion = Long.parseLong(parts[1]);
        int incomingProcessed = Integer.parseInt(parts[2]);
        int incomingSeatCount = Integer.parseInt(parts[3]);
        if (incomingSeatCount != seats.length()) {
            throw new IllegalArgumentException("Quantidade de assentos incompatível");
        }

        if (incomingVersion < version.get()) {
            return false; // snapshot antigo chegando fora de ordem
        }

        String[] encodedOwners = parts[4].split(",", -1);
        if (encodedOwners.length != seats.length()) {
            throw new IllegalArgumentException("Snapshot inválido: assentos=" + encodedOwners.length);
        }

        for (int i = 0; i < encodedOwners.length; i++) {
            String token = encodedOwners[i];
            if ("-".equals(token) || token.isEmpty()) {
                seats.set(i, null);
            } else {
                String owner = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                seats.set(i, owner);
            }
        }
        processedRequests = incomingProcessed;
        version.set(incomingVersion);
        return true;
    }

    private boolean validSeat(int seatNumber) {
        return seatNumber >= 1 && seatNumber <= seats.length();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class OperationResult {
        private final boolean ok;
        private final String message;
        private final long version;

        public OperationResult(boolean ok, String message, long version) {
            this.ok = ok;
            this.message = message;
            this.version = version;
        }

        public boolean ok() { return ok; }
        public String message() { return message; }
        public long version() { return version; }
    }
}

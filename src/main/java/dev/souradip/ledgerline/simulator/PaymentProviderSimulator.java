package dev.souradip.ledgerline.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand in for an external payment provider, misbehaving on demand.
 *
 * <p>A local stub that always answers 200 in a millisecond tests nothing interesting. Real
 * providers are slow, go down, and sometimes take the money and then fail to answer. This one
 * can do all of that, and counts how many times each payment was actually executed so tests can
 * prove nothing was paid twice.
 *
 * <p>Like a real provider, it dedupes on payment id: submitting the same id again returns the
 * original result without charging again.
 */
public class PaymentProviderSimulator {

    public enum Behavior {
        /** Pay and answer normally. */
        SETTLE,
        /** Refuse the payment with a 422. */
        DECLINE,
        /** Pay, then stall longer than the client's read timeout. The client never sees the answer. */
        SETTLE_THEN_HANG,
        /** Answer every request with 503 and do nothing. */
        UNAVAILABLE
    }

    private record Payment(String paymentId, String status, String reference, long amountMinor, String currency, String reason) {}

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Behavior behavior = Behavior.SETTLE;
    private volatile Duration hang = Duration.ofSeconds(5);
    private HttpServer server;

    public PaymentProviderSimulator start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/payments", this::payments);
        server.createContext("/settlements", this::settlements);
        server.start();
        return this;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public void behave(Behavior behavior) {
        this.behavior = behavior;
    }

    public void hangFor(Duration hang) {
        this.hang = hang;
    }

    public void reset() {
        payments.clear();
        executions.clear();
        requests.set(0);
        behavior = Behavior.SETTLE;
    }

    /** How many times money actually moved for this payment id. Anything above 1 is a double payment. */
    public int executionsOf(String paymentId) {
        AtomicInteger count = executions.get(paymentId);
        return count == null ? 0 : count.get();
    }

    public int requestCount() {
        return requests.get();
    }

    /** Simulates a settlement the ledger knows nothing about, for reconciliation tests. */
    public void recordSettlement(String paymentId, long amountMinor, String currency) {
        payments.put(paymentId, new Payment(paymentId, "SETTLED", "sim-" + UUID.randomUUID(), amountMinor, currency, null));
    }

    private void payments(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (behavior == Behavior.UNAVAILABLE) {
            respond(exchange, 503, Map.of("error", "service unavailable"));
            return;
        }
        if (exchange.getRequestMethod().equals("GET")) {
            String id = exchange.getRequestURI().getPath().substring("/payments/".length());
            Payment payment = payments.get(id);
            if (payment == null) {
                respond(exchange, 404, Map.of("error", "unknown payment"));
            } else {
                respond(exchange, 200, payment);
            }
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(exchange.getRequestBody(), Map.class);
        String id = (String) body.get("paymentId");
        long amount = ((Number) body.get("amountMinor")).longValue();
        String currency = (String) body.get("currency");

        Behavior now = behavior;
        Payment candidate = now == Behavior.DECLINE
                ? new Payment(id, "DECLINED", null, amount, currency, "insufficient funds at the receiving bank")
                : new Payment(id, "SETTLED", "sim-" + UUID.randomUUID(), amount, currency, null);
        Payment existing = payments.putIfAbsent(id, candidate);
        Payment result = existing != null ? existing : candidate;
        if (existing == null && result.status().equals("SETTLED")) {
            executions.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        }

        if (existing == null && now == Behavior.SETTLE_THEN_HANG) {
            try {
                Thread.sleep(hang);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        respond(exchange, result.status().equals("DECLINED") ? 422 : 200, result);
    }

    private void settlements(HttpExchange exchange) throws IOException {
        List<Payment> settled = payments.values().stream().filter(p -> p.status().equals("SETTLED")).toList();
        respond(exchange, 200, settled);
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException clientGaveUp) {
            // The caller timed out and closed the connection, which is the point of SETTLE_THEN_HANG.
        } finally {
            exchange.close();
        }
    }
}

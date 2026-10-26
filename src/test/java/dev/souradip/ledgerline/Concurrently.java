package dev.souradip.ledgerline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/** Fires tasks at the same instant from virtual threads and collects each outcome. */
public final class Concurrently {

    private Concurrently() {}

    public record Outcome<T>(T value, Throwable error) {

        public boolean failed() {
            return error != null;
        }
    }

    public static <T> List<Outcome<T>> run(int tasks, IntFunction<T> work) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>(tasks);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        return new Outcome<>(work.apply(index), null);
                    } catch (Throwable t) {
                        return new Outcome<T>(null, t);
                    }
                }));
            }
            startGate.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>(tasks);
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        }
    }
}

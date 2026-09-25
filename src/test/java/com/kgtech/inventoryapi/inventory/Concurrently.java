package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs one task on several threads released together, for the concurrency tests (D9, S11, W2). */
final class Concurrently {

    private static final long TIMEOUT_SECONDS = 30;

    private Concurrently() {
    }

    /**
     * Runs {@code task} on {@code threads} threads that wait on one start latch, and returns every result in
     * submission order. Any thread that throws or times out fails the test with all collected causes.
     */
    static <T> List<T> run(int threads, Callable<T> task) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("all threads ready").isTrue();
            start.countDown();

            List<T> results = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                } catch (TimeoutException e) {
                    failures.add(e);
                }
            }
            assertThat(failures).as("exceptions thrown by the concurrent threads").isEmpty();
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

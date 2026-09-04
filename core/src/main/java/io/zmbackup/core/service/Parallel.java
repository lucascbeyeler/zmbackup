package io.zmbackup.core.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class Parallel {

    private static final Duration TASK_TIMEOUT = Duration.ofHours(12);

    private Parallel() {}

    static <T> List<T> run(int maxParallelProcesses, List<Callable<T>> tasks) throws IOException {
        return run(maxParallelProcesses, tasks, TASK_TIMEOUT);
    }

    static <T> List<T> run(int maxParallelProcesses, List<Callable<T>> tasks, Duration taskTimeout)
            throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, maxParallelProcesses));
        try {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(task));
            }

            Instant deadline = Instant.now().plus(taskTimeout);

            List<T> results = new ArrayList<>(futures.size());
            IOException firstFailure = null;
            for (Future<T> future : futures) {
                try {
                    long remainingMillis = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
                    T result = future.get(remainingMillis, TimeUnit.MILLISECONDS);
                    if (firstFailure == null) {
                        results.add(result);
                    }
                } catch (ExecutionException e) {
                    if (firstFailure == null) {
                        Throwable cause = e.getCause();
                        firstFailure =
                                cause instanceof IOException io ? io : new IOException("Parallel task failed", cause);
                    }
                } catch (TimeoutException e) {
                    future.cancel(true);
                    if (firstFailure == null) {
                        firstFailure =
                                new IOException("Parallel task exceeded " + taskTimeout + " and was cancelled", e);
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            throw new IOException("Interrupted while running parallel tasks", e);
        } finally {
            executor.shutdown();
        }
    }
}

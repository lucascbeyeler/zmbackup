package io.zmbackup.core.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs a batch of tasks on a bounded thread pool and collects their results, mirroring the bash
 * tool's {@code parallel --jobs "$MAX_PARALLEL_PROCESS"} invocations: every task runs to
 * completion regardless of whether others fail, and results are returned in the same order the
 * tasks were given.
 */
final class Parallel {

    private Parallel() {}

    /**
     * Runs {@code tasks} on a fixed thread pool of {@code maxParallelProcesses} threads (at least
     * one), waiting for all of them to finish before returning.
     *
     * @throws IOException if the calling thread is interrupted while waiting, or if any task
     *     throws; an {@link IOException} thrown by a task is rethrown as-is, anything else is
     *     wrapped in one
     */
    static <T> List<T> run(int maxParallelProcesses, List<Callable<T>> tasks) throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, maxParallelProcesses));
        try {
            List<Future<T>> futures = executor.invokeAll(tasks);
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException("Parallel task failed", cause);
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running parallel tasks", e);
        } finally {
            executor.shutdown();
        }
    }
}

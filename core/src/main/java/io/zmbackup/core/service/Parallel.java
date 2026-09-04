package io.zmbackup.core.service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a batch of tasks on a bounded thread pool and collects their results, mirroring the bash
 * tool's {@code parallel --jobs "$MAX_PARALLEL_PROCESS"} invocations: every task runs to
 * completion (or is cancelled after {@link #TASK_TIMEOUT}, see below) regardless of whether
 * others fail, and results are returned in the same order the tasks were given.
 */
final class Parallel {

    /**
     * The longest a single task may run before {@link #run} gives up on it, cancels it, and
     * reports it as failed. Without this, a task stuck in a hung network call (e.g. an adapter
     * bug, or a peer that stops responding mid-transfer in a way its own timeout doesn't catch)
     * would block {@link #run} - and the backup/restore session it belongs to - forever, with no
     * recovery short of killing the JVM. Deliberately generous, well beyond any single adapter's
     * own request timeout (the longest of which, {@code ZimbraRestMailboxExporter}'s {@code
     * REQUEST_TIMEOUT}, is 6 hours, and a {@code FULL} backup's LDAP export adds at most another
     * 10 minutes on top), so this is a last-resort safety net that should never fire during normal
     * operation, not a bound on how long a legitimately large export may take.
     */
    private static final Duration TASK_TIMEOUT = Duration.ofHours(12);

    private Parallel() {}

    /**
     * Runs {@code tasks} on a fixed thread pool of {@code maxParallelProcesses} threads (at least
     * one), waiting for all of them to finish - or to exceed {@link #TASK_TIMEOUT} - before
     * returning.
     *
     * @throws IOException if the calling thread is interrupted while waiting, if any task throws,
     *     or if any task exceeds {@link #TASK_TIMEOUT} (in which case it is cancelled); an {@link
     *     IOException} thrown by a task is rethrown as-is, anything else is wrapped in one. When
     *     more than one task fails or times out, the first one in {@code tasks}' order is the one
     *     reported, but every task is still given up to {@link #TASK_TIMEOUT} to finish first.
     */
    static <T> List<T> run(int maxParallelProcesses, List<Callable<T>> tasks) throws IOException {
        return run(maxParallelProcesses, tasks, TASK_TIMEOUT);
    }

    /**
     * Package-visible overload taking an explicit {@code taskTimeout}, so tests can exercise the
     * cancel-on-timeout path without waiting out the real {@link #TASK_TIMEOUT}.
     */
    static <T> List<T> run(int maxParallelProcesses, List<Callable<T>> tasks, Duration taskTimeout)
            throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, maxParallelProcesses));
        try {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(task));
            }

            List<T> results = new ArrayList<>(futures.size());
            IOException firstFailure = null;
            for (Future<T> future : futures) {
                try {
                    T result = future.get(taskTimeout.toMillis(), TimeUnit.MILLISECONDS);
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
            // The sole cleanup for every exit path except the interrupted one above, which already
            // shut the pool down via shutdownNow(); shutdown() on an already-shutdown executor is a
            // documented no-op, so calling it again here is safe rather than redundant.
            executor.shutdown();
        }
    }
}

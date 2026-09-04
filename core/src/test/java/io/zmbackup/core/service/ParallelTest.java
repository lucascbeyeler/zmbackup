package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParallelTest {

    @Test
    void neverRunsMoreThanMaxParallelProcessesConcurrently() throws IOException {
        int maxParallelProcesses = 3;
        int taskCount = 12;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxObservedInFlight = new AtomicInteger();
        CountDownLatch releaseGate = new CountDownLatch(1);

        List<Callable<Integer>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            tasks.add(() -> {
                int current = inFlight.incrementAndGet();
                maxObservedInFlight.updateAndGet(previousMax -> Math.max(previousMax, current));
                releaseGate.await(5, TimeUnit.SECONDS);
                inFlight.decrementAndGet();
                return current;
            });
        }

        Thread releaser = new Thread(() -> {
            try {
                while (inFlight.get() < maxParallelProcesses) {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                releaseGate.countDown();
            }
        });
        releaser.start();

        List<Integer> results = Parallel.run(maxParallelProcesses, tasks);

        assertEquals(taskCount, results.size());
        assertTrue(
                maxObservedInFlight.get() <= maxParallelProcesses,
                "observed " + maxObservedInFlight.get() + " concurrent tasks, expected at most "
                        + maxParallelProcesses);
        assertEquals(maxParallelProcesses, maxObservedInFlight.get());
    }

    @Test
    void treatsValuesBelowOneAsOneThread() throws IOException {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxObservedInFlight = new AtomicInteger();
        List<Callable<Void>> tasks = List.of(
                () -> {
                    maxObservedInFlight.updateAndGet(prev -> Math.max(prev, inFlight.incrementAndGet()));
                    Thread.sleep(20);
                    inFlight.decrementAndGet();
                    return null;
                },
                () -> {
                    maxObservedInFlight.updateAndGet(prev -> Math.max(prev, inFlight.incrementAndGet()));
                    Thread.sleep(20);
                    inFlight.decrementAndGet();
                    return null;
                });

        Parallel.run(0, tasks);

        assertEquals(1, maxObservedInFlight.get());
    }

    @Test
    void runsEveryTaskToCompletionRegardlessOfOthersFailing() throws IOException {
        AtomicInteger completed = new AtomicInteger();
        List<Callable<Integer>> tasks = List.of(
                () -> {
                    completed.incrementAndGet();
                    return 1;
                },
                () -> {
                    completed.incrementAndGet();
                    throw new IOException("simulated failure");
                },
                () -> {
                    completed.incrementAndGet();
                    return 3;
                });

        assertThrows(IOException.class, () -> Parallel.run(2, tasks));
        assertEquals(3, completed.get());
    }

    @Test
    void returnsResultsInTheSameOrderAsTasks() throws IOException {
        List<Callable<Integer>> tasks =
                List.of(() -> 1, () -> 2, () -> 3, () -> 4, () -> 5);

        List<Integer> results = Parallel.run(4, tasks);

        assertEquals(List.of(1, 2, 3, 4, 5), results);
    }

    @Test
    void rethrowsIOExceptionThrownByATaskAsIs() {
        List<Callable<Void>> tasks = List.of(() -> {
            throw new IOException("boom");
        });

        IOException exception = assertThrows(IOException.class, () -> Parallel.run(1, tasks));
        assertEquals("boom", exception.getMessage());
    }

    @Test
    void wrapsNonIOExceptionThrownByATaskInIOException() {
        List<Callable<Void>> tasks = List.of(() -> {
            throw new RuntimeException("boom");
        });

        IOException exception = assertThrows(IOException.class, () -> Parallel.run(1, tasks));
        assertEquals(RuntimeException.class, exception.getCause().getClass());
    }

    @Test
    void cancelsAndFailsATaskThatExceedsTheTimeout() throws IOException {
        AtomicInteger interrupted = new AtomicInteger();
        List<Callable<Void>> tasks = List.of(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(30).toMillis());
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                Thread.currentThread().interrupt();
            }
            return null;
        });

        IOException exception = assertThrows(
                IOException.class, () -> Parallel.run(1, tasks, Duration.ofMillis(50)));

        assertTrue(exception.getMessage().contains("exceeded"));
        for (int attempt = 0; attempt < 100 && interrupted.get() == 0; attempt++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(1, interrupted.get());
    }

    @Test
    void stillRunsEveryOtherTaskToCompletionWhenOneTimesOut() throws IOException {
        AtomicInteger completed = new AtomicInteger();
        List<Callable<Integer>> tasks = List.of(
                () -> {
                    Thread.sleep(Duration.ofSeconds(30).toMillis());
                    return 1;
                },
                () -> {
                    completed.incrementAndGet();
                    return 2;
                });

        assertThrows(IOException.class, () -> Parallel.run(2, tasks, Duration.ofMillis(50)));

        assertEquals(1, completed.get());
    }
}

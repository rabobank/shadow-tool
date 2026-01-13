package io.github.rabobank.shadow_tool;

import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executes all submitted tasks directly in the same thread as the caller.
 * Humbly copied from <a href="https://stackoverflow.com/a/13726463">StackOverflow</a>
 */
class SameThreadExecutorService extends AbstractExecutorService {
    /**
     * Lock used whenever accessing the state variables
     * (runningTasks, shutdown, terminationCondition) of the executor
     */
    private final Lock lock = new ReentrantLock();
    /** Signaled after the executor is shutdown and running tasks are done */
    private final java.util.concurrent.locks.Condition termination = lock.newCondition();
    /*
     * Conceptually, these two variables describe the executor being in
     * one of three states:
     *   - Active: shutdown == false
     *   - Shutdown: runningTasks > 0 and shutdown == true
     *   - Terminated: runningTasks == 0 and shutdown == true
     */
    private int runningTasks = 0;
    private boolean shutdown = false;

    @Override
    public void execute(final Runnable command) {
        startTask();
        try {
            command.run();
        } finally {
            endTask();
        }
    }

    @Override
    public boolean isShutdown() {
        lock.lock();
        try {
            return shutdown;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    @NonNull
    public List<Runnable> shutdownNow() {
        shutdown();
        return Collections.emptyList();
    }

    @Override
    public boolean isTerminated() {
        lock.lock();
        try {
            return shutdown && runningTasks == 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            for (; ; ) {
                if (isTerminated()) {
                    return true;
                } else if (nanos <= 0) {
                    return false;
                } else {
                    nanos = termination.awaitNanos(nanos);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if the executor has been shut down and increments the running
     * task count.
     *
     * @throws RejectedExecutionException if the executor has been previously
     *         shutdown
     */
    private void startTask() {
        lock.lock();
        try {
            if (isShutdown()) {
                throw new RejectedExecutionException("Executor already shutdown");
            }
            runningTasks++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Decrements the running task count.
     */
    private void endTask() {
        lock.lock();
        try {
            runningTasks--;
            if (isTerminated()) {
                termination.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }
}

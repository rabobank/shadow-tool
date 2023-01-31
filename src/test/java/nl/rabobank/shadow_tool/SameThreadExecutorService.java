package nl.rabobank.shadow_tool;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Executes all submitted tasks directly in the same thread as the caller.
 * Humbly copied from <a href="https://stackoverflow.com/a/13726463">StackOverflow</a>
 */
public class SameThreadExecutorService extends AbstractExecutorService {

    private volatile boolean terminated;

    @Override
    public void shutdown() {
        terminated = true;
    }

    @Override
    public boolean isShutdown() {
        return terminated;
    }

    @Override
    public boolean isTerminated() {
        return terminated;
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
        shutdown(); // TODO ok to call shutdown? what if the client never called shutdown???
        return terminated;
    }

    @Override
    public List<Runnable> shutdownNow() {
        return Collections.emptyList();
    }

    @Override
    public void execute(final Runnable theCommand) {
        theCommand.run();
    }
}

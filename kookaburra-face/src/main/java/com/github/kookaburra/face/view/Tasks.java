package com.github.kookaburra.face.view;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs database work off the FX thread and delivers results on it (design §17.4).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Tasks implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Tasks.class);

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "studio-db");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger running = new AtomicInteger();
    private final Consumer<Boolean> busy;

    /** @param busy called on the FX thread with true / false when work starts / ends */
    public Tasks(Consumer<Boolean> busy) {
        this.busy = busy;
    }

    public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        if (running.getAndIncrement() == 0) {
            busy.accept(true);
        }
        pool.execute(() -> {
            try {
                T value = work.call();
                Platform.runLater(() -> {
                    done();
                    onSuccess.accept(value);
                });
            } catch (Throwable t) {
                // usually a user error (bad OQL, version conflict): message at WARN, stack trace at DEBUG
                LOG.warn("task failed: {}", t.toString());
                LOG.debug("task failure detail", t);
                Platform.runLater(() -> {
                    done();
                    onError.accept(t);
                });
            }
        });
    }

    private void done() {
        if (running.decrementAndGet() == 0) {
            busy.accept(false);
        }
    }

    /** Number of unfinished tasks. */
    public int pending() {
        return running.get();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}

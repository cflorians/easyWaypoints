package com.easywp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Moves {@link JsonStore#write}'s disk work - temp file, fsync, atomic move, {@code .bak} - off
 * the calling (client) thread, and coalesces repeated saves of the same file.
 *
 * <p>Every mutating action in the mod saves immediately, and each save forces the file to disk.
 * On the client thread that is a stall the player can feel: a Fast Ping, a death, or dragging a
 * config slider (one save per step) each waited for an fsync mid-frame, which on a slow disk or
 * a busy HDD is a visible hitch. Here the caller only hands over the finished document; one
 * background thread writes it. The document itself is still serialised by the caller, on the
 * client thread, at the moment of the change - so what gets written is exactly the state that was
 * saved, never a later one, and the caller's data structures are never read from another thread.
 *
 * <p>Saves queue per file and <b>coalesce</b>: while a file already has a document waiting, a
 * newer one replaces it instead of queueing behind it. Twenty slider steps that land while the
 * disk is busy cost one more write, not twenty, and the file still ends at the newest state,
 * because only ever the latest document is kept and one thread writes them in order.
 *
 * <p>{@link #flush()} blocks until everything handed over so far is on disk. It is what makes the
 * deferral invisible: anything about to <em>read</em> one of these files flushes first, so it can
 * never see an older revision than the one last saved; and the client flushes on shutdown, since
 * the writer thread is a daemon and would otherwise be cut off mid-queue.
 *
 * <p>Write failures are reported to the error handler and do not stop the queue; the next save of
 * the same file simply tries again with its newer document.
 */
public final class AsyncJsonWriter {
    /** The disk operation, injectable so tests can observe and stall it. */
    @FunctionalInterface
    interface Sink {
        void write(Path target, String document) throws IOException;
    }

    /** Receives a failed write; called on the writer thread. */
    @FunctionalInterface
    public interface ErrorHandler {
        void onError(Path target, IOException error);
    }

    /** The one writer the mod's stores share, so a single flush covers all of them. */
    public static final AsyncJsonWriter SHARED = new AsyncJsonWriter(
            (target, error) -> EasyWp.LOGGER.error("Failed to save {}", target, error));

    private final Sink sink;
    private final ErrorHandler errorHandler;
    private final Executor executor;

    private final Object lock = new Object();
    /** Documents waiting to be written, newest per file, in first-submitted order. Guarded by {@link #lock}. */
    private final Map<Path, String> pending = new LinkedHashMap<>();
    /** True from the moment a drain is scheduled until it has written everything. Guarded by {@link #lock}. */
    private boolean draining = false;

    public AsyncJsonWriter(ErrorHandler errorHandler) {
        this(JsonStore::write, errorHandler, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "easywp-save");
            thread.setDaemon(true);
            return thread;
        }));
    }

    AsyncJsonWriter(Sink sink, ErrorHandler errorHandler, Executor executor) {
        this.sink = sink;
        this.errorHandler = errorHandler;
        this.executor = executor;
    }

    /**
     * Queues {@code document} to be written to {@code target}, replacing any document for the same
     * file that is still waiting. Returns immediately.
     */
    public void write(Path target, String document) {
        synchronized (lock) {
            pending.put(target, document);
            if (!draining) {
                draining = true;
                executor.execute(this::drain);
            }
        }
    }

    /**
     * Blocks until every document handed to {@link #write} before this call is on disk (or has
     * failed and been reported). Returns at once when nothing is queued.
     */
    public void flush() {
        synchronized (lock) {
            boolean interrupted = false;
            while (draining) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void drain() {
        while (true) {
            Path target;
            String document;
            synchronized (lock) {
                Iterator<Map.Entry<Path, String>> next = pending.entrySet().iterator();
                if (!next.hasNext()) {
                    draining = false;
                    lock.notifyAll();
                    return;
                }
                Map.Entry<Path, String> entry = next.next();
                target = entry.getKey();
                document = entry.getValue();
                next.remove();
            }
            try {
                sink.write(target, document);
            } catch (IOException e) {
                errorHandler.onError(target, e);
            } catch (RuntimeException e) {
                errorHandler.onError(target, new IOException(e));
            }
        }
    }
}

package com.easywp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class AsyncJsonWriterTest {

    @TempDir
    Path dir;

    /** Records every write; the first one blocks until released, so later saves pile up behind it. */
    private static final class StallingSink implements AsyncJsonWriter.Sink {
        final List<String> writes = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(Path target, String document) throws IOException {
            boolean first = firstWriteStarted.getCount() > 0;
            firstWriteStarted.countDown();
            if (first) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            writes.add(target.getFileName() + "=" + document);
        }
    }

    private static ExecutorService singleThread() {
        return Executors.newSingleThreadExecutor();
    }

    @Test
    void flushLeavesTheLatestDocumentOnDisk() throws IOException {
        List<IOException> errors = new ArrayList<>();
        AsyncJsonWriter writer = new AsyncJsonWriter(JsonStore::write, (t, e) -> errors.add(e), singleThread());
        Path file = dir.resolve("waypoints_test.json");

        writer.write(file, "[1]");
        writer.write(file, "[1,2]");
        writer.write(file, "[1,2,3]");
        writer.flush();

        assertEquals("[1,2,3]", Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(errors.isEmpty());
    }

    @Test
    void savesQueuedWhileTheDiskIsBusyCoalesceToTheNewest() throws InterruptedException {
        StallingSink sink = new StallingSink();
        AsyncJsonWriter writer = new AsyncJsonWriter(sink, (t, e) -> { }, singleThread());
        Path file = dir.resolve("config.json");

        writer.write(file, "v1");
        assertTrue(sink.firstWriteStarted.await(5, TimeUnit.SECONDS));
        // The writer is stuck inside v1's write; these three must collapse into one.
        writer.write(file, "v2");
        writer.write(file, "v3");
        writer.write(file, "v4");
        sink.release.countDown();
        writer.flush();

        assertEquals(List.of("config.json=v1", "config.json=v4"), sink.writes);
    }

    @Test
    void differentFilesAreEachWritten() throws InterruptedException {
        StallingSink sink = new StallingSink();
        AsyncJsonWriter writer = new AsyncJsonWriter(sink, (t, e) -> { }, singleThread());

        writer.write(dir.resolve("a.json"), "a1");
        assertTrue(sink.firstWriteStarted.await(5, TimeUnit.SECONDS));
        writer.write(dir.resolve("b.json"), "b1");
        writer.write(dir.resolve("a.json"), "a2");
        sink.release.countDown();
        writer.flush();

        assertEquals(List.of("a.json=a1", "b.json=b1", "a.json=a2"), sink.writes);
    }

    @Test
    void flushWaitsForTheWriteInFlight() throws InterruptedException {
        StallingSink sink = new StallingSink();
        AsyncJsonWriter writer = new AsyncJsonWriter(sink, (t, e) -> { }, singleThread());

        writer.write(dir.resolve("a.json"), "a1");
        assertTrue(sink.firstWriteStarted.await(5, TimeUnit.SECONDS));

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            sink.release.countDown();
        });
        releaser.start();
        // Nothing is pending any more - the only document is mid-write - so a flush that only
        // looked at the queue would return here with the file not yet written.
        writer.flush();

        assertEquals(List.of("a.json=a1"), sink.writes);
        releaser.join();
    }

    @Test
    void aFailedWriteIsReportedAndDoesNotStopTheQueue() {
        List<Path> failed = Collections.synchronizedList(new ArrayList<>());
        List<String> written = Collections.synchronizedList(new ArrayList<>());
        AsyncJsonWriter.Sink sink = (target, document) -> {
            if (document.equals("bad")) {
                throw new IOException("disk full");
            }
            written.add(document);
        };
        // Runs the drain only when the test says so, so both saves are queued together and the
        // failing one is written first with the other still waiting behind it.
        List<Runnable> scheduled = new ArrayList<>();
        AsyncJsonWriter writer = new AsyncJsonWriter(sink, (t, e) -> failed.add(t), scheduled::add);

        writer.write(dir.resolve("a.json"), "bad");
        writer.write(dir.resolve("b.json"), "b1");
        scheduled.forEach(Runnable::run);
        writer.flush();

        assertEquals(List.of(dir.resolve("a.json")), failed);
        assertEquals(List.of("b1"), written);

        // And the failed file itself is retried by its next save.
        scheduled.clear();
        writer.write(dir.resolve("a.json"), "good");
        scheduled.forEach(Runnable::run);
        assertEquals(List.of("b1", "good"), written);
    }

    @Test
    void flushWithNothingQueuedReturnsImmediately() {
        AsyncJsonWriter writer = new AsyncJsonWriter((t, d) -> { }, (t, e) -> { }, singleThread());
        writer.flush();
    }
}

package dev.aether.modules.clip;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClipRecorder {
    private static final int QUEUE_CAPACITY = 2;
    private static final int POOL_LIMIT = 3;
    private static final int SEGMENT_SECONDS = 1;

    private final String ffmpeg;
    private final File segmentDir;
    private final int width;
    private final int height;
    private final int fps;
    private final int frameBytes;

    private final ArrayBlockingQueue<ByteBuffer> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();
    private int allocatedBuffers;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int retainSegments = 64;

    private Process process;
    private OutputStream stdin;
    private WritableByteChannel channel;
    private Thread writerThread;

    ClipRecorder(String ffmpeg, File segmentDir, int width, int height, int fps) {
        this.ffmpeg = ffmpeg;
        this.segmentDir = segmentDir;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.frameBytes = width * height * 4;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    boolean isRunning() {
        return running.get();
    }

    void setRetainSegments(int segments) {
        retainSegments = Math.max(4, segments);
    }

    void start() throws IOException {
        clearSegmentDir();
        List<String> command = List.of(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pixel_format", "rgba",
                "-video_size", width + "x" + height, "-framerate", Integer.toString(fps),
                "-i", "pipe:0", "-an",
                "-vf", "vflip,format=yuv420p",
                "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-g", Integer.toString(fps),
                "-f", "segment", "-segment_time", Integer.toString(SEGMENT_SECONDS),
                "-segment_format", "mpegts", "-reset_timestamps", "1",
                new File(segmentDir, "seg_%06d.ts").getAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(Redirect.DISCARD);
        builder.redirectError(Redirect.DISCARD);
        process = builder.start();
        stdin = process.getOutputStream();
        channel = Channels.newChannel(stdin);
        running.set(true);

        writerThread = new Thread(this::writerLoop, "Aether-Clip-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    ByteBuffer acquireBuffer() {
        synchronized (pool) {
            ByteBuffer buffer = pool.poll();
            if (buffer != null) {
                return buffer;
            }
            if (allocatedBuffers < POOL_LIMIT) {
                allocatedBuffers++;
                return ByteBuffer.allocateDirect(frameBytes);
            }
        }
        return null;
    }

    void returnBuffer(ByteBuffer buffer) {
        recycle(buffer);
    }

    private void recycle(ByteBuffer buffer) {
        buffer.clear();
        synchronized (pool) {
            pool.offer(buffer);
        }
    }

    void submitFrame(ByteBuffer buffer) {
        if (!running.get() || !queue.offer(buffer)) {
            recycle(buffer);
        }
    }

    private void writerLoop() {
        int sincePrune = 0;
        try {
            while (running.get()) {
                ByteBuffer buffer = queue.poll(200, TimeUnit.MILLISECONDS);
                if (buffer == null) {
                    continue;
                }
                try {
                    buffer.position(0).limit(frameBytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    stdin.flush();
                } finally {
                    recycle(buffer);
                }
                if (++sincePrune >= fps) {
                    sincePrune = 0;
                    prune();
                }
            }
        } catch (Exception exception) {
            running.set(false);
        }
    }

    private void prune() {
        File[] segments = listSegments();
        if (segments == null || segments.length <= retainSegments) {
            return;
        }
        int toDelete = segments.length - retainSegments;
        for (int i = 0; i < toDelete; i++) {
            segments[i].delete();
        }
    }

    private File[] listSegments() {
        File[] segments = segmentDir.listFiles((dir, name) -> name.startsWith("seg_") && name.endsWith(".ts"));
        if (segments != null) {
            Arrays.sort(segments, Comparator.comparing(File::getName));
        }
        return segments;
    }

    File saveClip(int clipSeconds, File outFile) throws IOException, InterruptedException {
        File[] segments = listSegments();
        if (segments == null || segments.length < 2) {
            return null;
        }

        int completed = segments.length - 1;
        int take = Math.min(completed, clipSeconds + 1);
        int startIndex = completed - take;

        StringBuilder list = new StringBuilder();
        for (int i = startIndex; i < completed; i++) {
            String path = segments[i].getAbsolutePath().replace('\\', '/').replace("'", "'\\''");
            list.append("file '").append(path).append("'\n");
        }

        File listFile = new File(segmentDir, "concat_" + System.currentTimeMillis() + ".txt");
        Files.writeString(listFile.toPath(), list.toString());
        try {
            List<String> command = List.of(
                    ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "concat", "-safe", "0", "-i", listFile.getAbsolutePath(),
                    "-c", "copy", "-movflags", "+faststart", outFile.getAbsolutePath());
            Process concat = new ProcessBuilder(command)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start();
            if (!concat.waitFor(60, TimeUnit.SECONDS)) {
                concat.destroyForcibly();
                return null;
            }
            if (concat.exitValue() != 0 || !outFile.exists()) {
                return null;
            }
            return outFile;
        } finally {
            listFile.delete();
        }
    }

    void stop() {
        running.set(false);
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        clearSegmentDir();
        synchronized (pool) {
            pool.clear();
            allocatedBuffers = 0;
        }
        queue.clear();
    }

    private void clearSegmentDir() {
        if (!segmentDir.exists()) {
            segmentDir.mkdirs();
            return;
        }
        File[] files = segmentDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }
}

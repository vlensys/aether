package dev.aether.modules.clip;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroStateManager;
import dev.aether.mixin.AccessorGlDevice;
import dev.aether.mixin.AccessorGpuDevice;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.util.ClientUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClipManager {
    private static final int FPS = 20;
    private static final int TARGET_HEIGHT = 720;
    private static final long TRIGGER_COOLDOWN_MS = 4000L;
    private static final long POST_TRIGGER_FLUSH_MS = 2000L;
    private static final long START_RETRY_MS = 10000L;
    private static final long IDLE_STOP_MS = 15000L;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static volatile boolean active = false;
    private static volatile boolean starting = false;
    private static volatile ClipRecorder recorder;
    private static volatile ExecutorService control;
    private static boolean ffmpegMissingNotified = false;
    private static long lastTriggerMs = 0;
    private static long lastStartAttemptMs = 0;
    private static long lastInWorldMs = 0;
    private static long lastCaptureNanos = 0;

    private static int captureFbo = 0;
    private static int captureTex = 0;
    private static int captureW = 0;
    private static int captureH = 0;

    private ClipManager() {
    }

    public static void syncFromConfig() {
        if (!AetherConfig.CLIP_ENABLED.get()) {
            stop();
        } else {
            lastStartAttemptMs = 0;
        }
    }

    public static void tick(Minecraft client) {
        if (!AetherConfig.CLIP_ENABLED.get()) {
            if (active || starting || recorder != null) {
                stop();
            }
            return;
        }
        ClipRecorder current = recorder;
        if (current != null) {
            current.setRetainSegments(AetherConfig.CLIP_LENGTH_SECONDS.get() + 3);
        }

        boolean inWorld = client != null && client.level != null;
        if (inWorld) {
            lastInWorldMs = System.currentTimeMillis();
        } else if (active && System.currentTimeMillis() - lastInWorldMs > IDLE_STOP_MS) {
            stop();
            return;
        }

        if (active || starting || !inWorld) {
            return;
        }
        tryStart(client);
    }

    private static void tryStart(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastStartAttemptMs < START_RETRY_MS) {
            return;
        }
        lastStartAttemptMs = now;

        int frameWidth = client.getWindow().getWidth();
        int frameHeight = client.getWindow().getHeight();
        if (frameWidth <= 0 || frameHeight <= 0) {
            return;
        }
        int height = Math.min(TARGET_HEIGHT, frameHeight);
        height = Math.max(2, height - (height % 2));
        int width = (int) Math.round((double) frameWidth / frameHeight * height);
        width = Math.max(2, width - (width % 2));

        int recorderWidth = width;
        int recorderHeight = height;
        starting = true;
        ensureControl();
        control.submit(() -> startRecorder(recorderWidth, recorderHeight));
    }

    private static void startRecorder(int width, int height) {
        try {
            String ffmpeg = resolveFfmpeg();
            if (!validateFfmpeg(ffmpeg)) {
                if (!ffmpegMissingNotified) {
                    ffmpegMissingNotified = true;
                    message("§cClip Recorder: ffmpeg not found. Install ffmpeg or set its path in the settings.");
                }
                return;
            }
            ffmpegMissingNotified = false;
            ClipRecorder created = new ClipRecorder(ffmpeg, segmentDir(), width, height, FPS);
            created.setRetainSegments(AetherConfig.CLIP_LENGTH_SECONDS.get() + 3);
            created.start();
            recorder = created;
            active = true;
        } catch (Exception exception) {
            System.err.println("[Aether] Failed to start clip recorder: " + exception.getMessage());
        } finally {
            starting = false;
        }
    }

    public static void stop() {
        active = false;
        starting = false;
        ClipRecorder previous = recorder;
        recorder = null;
        ExecutorService executor = control;
        if (executor != null && previous != null) {
            executor.submit(previous::stop);
        } else if (previous != null) {
            previous.stop();
        }
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(ClipManager::releaseCaptureTarget);
        }
    }

    public static void shutdown() {
        active = false;
        starting = false;
        ClipRecorder previous = recorder;
        recorder = null;
        ExecutorService executor = control;
        control = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        if (previous != null) {
            previous.stop();
        }
        releaseCaptureTarget();
    }

    public static void onFailsafe() {
        if (!AetherConfig.CLIP_ENABLED.get() || !AetherConfig.CLIP_ON_FAILSAFE.get()) {
            return;
        }
        trigger("failsafe");
    }

    public static void onServerDisconnect(Component reason) {
        if (!AetherConfig.CLIP_ENABLED.get() || !AetherConfig.CLIP_ON_BAN.get()) {
            return;
        }
        if (MacroStateManager.isIntentionalDisconnect() || DynamicRestManager.isRestPending()) {
            return;
        }
        if (reason == null) {
            return;
        }
        String text = reason.getString().toLowerCase(Locale.ROOT);
        if (!text.contains("banned")) {
            return;
        }
        trigger("ban");
    }

    private static void trigger(String reason) {
        if (!active) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTriggerMs < TRIGGER_COOLDOWN_MS) {
            return;
        }
        lastTriggerMs = now;
        ClipRecorder current = recorder;
        ExecutorService executor = control;
        if (current == null || executor == null) {
            return;
        }
        int clipSeconds = AetherConfig.CLIP_LENGTH_SECONDS.get();
        executor.submit(() -> runSave(current, clipSeconds, reason));
    }

    private static void runSave(ClipRecorder current, int clipSeconds, String reason) {
        try {
            Thread.sleep(POST_TRIGGER_FLUSH_MS);
            File output = new File(clipsDir(), "clip_" + STAMP.format(LocalDateTime.now()) + "_" + reason + ".mp4");
            File saved = current.saveClip(clipSeconds, output);
            if (saved != null) {
                message("§aClip saved: §f" + saved.getName());
            } else {
                message("§cClip Recorder: not enough footage buffered to save a clip.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            System.err.println("[Aether] Failed to save clip: " + exception.getMessage());
        }
    }

    public static void captureFrame() {
        if (!active) {
            return;
        }
        ClipRecorder current = recorder;
        if (current == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastCaptureNanos < 1_000_000_000L / FPS) {
            return;
        }
        lastCaptureNanos = now;

        var target = client.getMainRenderTarget();
        if (target == null || target.width <= 0 || target.height <= 0) {
            return;
        }

        int sourceFbo;
        try {
            sourceFbo = ((GlTexture) target.getColorTexture()).getFbo(resolveDirectStateAccess(), null);
        } catch (RuntimeException | LinkageError exception) {
            return;
        }

        ByteBuffer buffer = current.acquireBuffer();
        if (buffer == null) {
            return;
        }

        boolean captured;
        try {
            captured = grab(sourceFbo, target.width, target.height, current.width(), current.height(), buffer);
        } catch (RuntimeException | LinkageError exception) {
            captured = false;
        }

        if (captured) {
            current.submitFrame(buffer);
        } else {
            current.returnBuffer(buffer);
        }
    }

    private static boolean grab(int sourceFbo, int sourceWidth, int sourceHeight, int destWidth, int destHeight, ByteBuffer buffer) {
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousAlign = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);

        ensureCaptureTarget(destWidth, destHeight);
        if (captureFbo == 0) {
            return false;
        }

        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, captureFbo);
            GL30.glBlitFramebuffer(0, 0, sourceWidth, sourceHeight, 0, 0, destWidth, destHeight,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, captureFbo);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            buffer.clear();
            GL11.glReadPixels(0, 0, destWidth, destHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            return true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPack);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousAlign);
        }
    }

    private static void ensureCaptureTarget(int width, int height) {
        if (captureFbo != 0 && captureW == width && captureH == height) {
            return;
        }
        releaseCaptureTarget();

        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        captureTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, captureTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        captureFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, captureFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, captureTex, 0);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        captureW = width;
        captureH = height;
    }

    private static void releaseCaptureTarget() {
        try {
            if (captureFbo != 0) {
                GL30.glDeleteFramebuffers(captureFbo);
            }
            if (captureTex != 0) {
                GL11.glDeleteTextures(captureTex);
            }
        } catch (RuntimeException | LinkageError ignored) {
        } finally {
            captureFbo = 0;
            captureTex = 0;
            captureW = 0;
            captureH = 0;
        }
    }

    public static void openClipsFolder() {
        ensureControl();
        control.submit(() -> {
            try {
                File dir = clipsDir();
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                String opener;
                if (os.contains("win")) {
                    opener = "explorer";
                } else if (os.contains("mac")) {
                    opener = "open";
                } else {
                    opener = "xdg-open";
                }
                new ProcessBuilder(opener, dir.getAbsolutePath()).start();
            } catch (Exception exception) {
                System.err.println("[Aether] Failed to open clips folder: " + exception.getMessage());
            }
        });
    }

    private static synchronized void ensureControl() {
        if (control == null || control.isShutdown()) {
            control = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Aether-Clip-Control");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    private static String resolveFfmpeg() {
        String path = AetherConfig.CLIP_FFMPEG_PATH.get();
        if (path != null && !path.isBlank()) {
            return path.trim();
        }
        return "ffmpeg";
    }

    private static boolean validateFfmpeg(String ffmpeg) {
        try {
            Process probe = new ProcessBuilder(ffmpeg, "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!probe.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return false;
            }
            return probe.exitValue() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static File clipsDir() {
        File dir = FabricLoader.getInstance().getGameDir().resolve("aether").resolve("clips").toFile();
        dir.mkdirs();
        return dir;
    }

    private static File segmentDir() {
        File dir = new File(clipsDir(), "buffer");
        dir.mkdirs();
        return dir;
    }

    private static void message(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> ClientUtils.sendMessage(text));
        }
    }

    private static DirectStateAccess resolveDirectStateAccess() {
        return ((AccessorGlDevice) ((AccessorGpuDevice) RenderSystem.getDevice()).aether$getBackend())
                .aether$directStateAccess();
    }
}

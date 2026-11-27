package com.logvex.addon.companion.mc;

import com.logvex.addon.companion.core.CompanionViewportSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Iterator;

/**
 * Live pictures of the game window, read from the framebuffer the game has just drawn.
 *
 * <p>The frame comes from the real framebuffer, not from a reconstruction: {@code takeScreenshot}
 * reads the same colour attachment the player is looking at, so what a viewer sees is the world as
 * rendered, including shaders, weather, the held item and any overlay the game draws.
 *
 * <p>The game can only hand over a {@link NativeImage} as a file, so the capture writes a temporary
 * PNG, reads it back and re-encodes it. That is one extra pass, but it buys two things worth having:
 * a real quality setting (JPEG) and a real width setting (scaling), both of which the game's own
 * encoder cannot express. The temporary file is always deleted, including on failure.
 *
 * <p>Everything here runs on the client thread, which is also the render thread, so the framebuffer
 * is not being written while it is read.
 */
public final class MinecraftViewportSource implements CompanionViewportSource {

    /** A viewer asking for more than this is clipped, so one request cannot allocate gigabytes. */
    private static final int MAX_WIDTH = 1920;
    /** Below this a frame is too small to recognise, so it is refused rather than served. */
    private static final int MIN_WIDTH = 160;

    @Override
    public int maxWidth() {
        return MAX_WIDTH;
    }

    @Override
    public int minWidth() {
        return MIN_WIDTH;
    }

    @Override
    public boolean available() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.getFramebuffer() != null && mc.getWindow() != null;
    }

    @Override
    public Frame capture(int targetWidth, int quality) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getFramebuffer() == null) return null;
        // A zero-sized or minimised window has nothing to read; the viewer shows "waiting".
        if (mc.getWindow() == null || mc.getWindow().getFramebufferWidth() <= 0) return null;

        NativeImage image = null;
        File temp = null;
        try {
            image = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
            if (image == null) return null;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return null;

            temp = File.createTempFile("logclient-frame-", ".png");
            image.writeTo(temp);

            BufferedImage decoded;
            try (InputStream in = Files.newInputStream(temp.toPath())) {
                decoded = ImageIO.read(in);
            }
            if (decoded == null) return null;

            BufferedImage scaled = scale(decoded, clampWidth(targetWidth, width));
            int outWidth = scaled.getWidth();
            int outHeight = scaled.getHeight();
            byte[] png = encode(scaled, quality);
            if (png == null) return null;
            return new Frame(png, outWidth, outHeight, System.currentTimeMillis());
        } catch (Exception e) {
            return null;
        } finally {
            if (image != null) image.close();
            if (temp != null && !temp.delete()) temp.deleteOnExit();
        }
    }

    /** The requested width, clipped to what this source will serve. */
    private static int clampWidth(int targetWidth, int actualWidth) {
        int width = targetWidth <= 0 ? actualWidth : targetWidth;
        width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
        // Never upscale: asking for 1920 from a 1280 window would only blur the picture.
        return Math.min(width, actualWidth);
    }

    /** Scales to the requested width, keeping the aspect ratio, and drops alpha. */
    private static BufferedImage scale(BufferedImage source, int width) {
        if (source.getWidth() == width) return toOpaque(source);
        int height = Math.max(1, (int) Math.round(
            source.getHeight() * (width / (double) source.getWidth())));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage toOpaque(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) return source;
        BufferedImage out = new BufferedImage(
            source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Encodes a frame, choosing the format from the quality.
     *
     * <p>A quality of 100 means PNG, which is lossless and is what a viewer wants when it is looking
     * for detail. Anything lower is JPEG at that quality, which is dramatically smaller for a
     * continuously updating stream and is the setting that makes a slow link usable.
     */
    private static byte[] encode(BufferedImage image, int quality) {
        int q = Math.max(1, Math.min(100, quality));
        try {
            if (q >= 100) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            }
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) return null;
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(q / 100f);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** The content type that matches {@link #encode}. */
    public static String contentType(int quality) {
        return quality >= 100 ? "image/png" : "image/jpeg";
    }
}

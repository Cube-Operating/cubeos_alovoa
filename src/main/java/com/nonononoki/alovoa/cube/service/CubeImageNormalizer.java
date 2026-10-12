package com.nonononoki.alovoa.cube.service;

import com.nonononoki.alovoa.cube.web.CubeApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;

/**
 * CubeOS: validates an uploaded photo (JPEG/PNG/WebP by content, at most 5 MB, sane dimensions) and
 * normalises it the way Alovoa does (centre-cropped square of app.profile.image.length px) before it
 * is handed to Alovoa's UserService. Output is JPEG; Alovoa stores it unchanged because it already has
 * the target size.
 */
@Component
public class CubeImageNormalizer {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final int MAX_SIDE = 10_000;
    public static final String MIME_JPEG = "image/jpeg";

    /** Decoding large photos is memory-hungry; keep at most two in flight (the JVM runs with -Xmx768m). */
    private final Semaphore decodeSlots = new Semaphore(2);

    @Value("${app.profile.image.length}")
    private int imageLength;

    public record Normalized(byte[] bytes, String mime) {
    }

    public Normalized normalize(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw CubeApiException.badRequest("Please attach a photo.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw CubeApiException.badRequest("Photos must be 5 MB or smaller.");
        }
        try {
            return normalize(file.getBytes());
        } catch (IOException e) {
            throw CubeApiException.badRequest("That photo couldn't be read. Use a JPEG, PNG or WebP image.");
        }
    }

    public Normalized normalize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw CubeApiException.badRequest("Please attach a photo.");
        }
        if (bytes.length > MAX_BYTES) {
            throw CubeApiException.badRequest("Photos must be 5 MB or smaller.");
        }
        String format = sniff(bytes);
        if (format == null) {
            throw CubeApiException.badRequest("Use a JPEG, PNG or WebP photo.");
        }
        try {
            decodeSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CubeApiException.badRequest("That photo couldn't be read. Please try again.");
        }
        try {
            BufferedImage image = decode(bytes, format);
            BufferedImage square = cropAndScale(image, imageLength);
            return new Normalized(encodeJpeg(square), MIME_JPEG);
        } catch (IOException | RuntimeException e) {
            if (e instanceof CubeApiException c) {
                throw c;
            }
            throw CubeApiException.badRequest("That photo couldn't be read. Use a JPEG, PNG or WebP image.");
        } finally {
            decodeSlots.release();
        }
    }

    /** @return "jpeg", "png" or "webp" from the file signature, or null. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static BufferedImage decode(byte[] bytes, String format) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            while (readers.hasNext()) {
                ImageReader reader = readers.next();
                String name = reader.getOriginatingProvider() == null ? "" : String.join(",", reader.getOriginatingProvider().getFormatNames()).toLowerCase();
                if (!name.contains(format)) {
                    continue;
                }
                try {
                    reader.setInput(in, true, true);
                    int w = reader.getWidth(0);
                    int h = reader.getHeight(0);
                    if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
                        throw CubeApiException.badRequest("That photo's resolution is too large.");
                    }
                    BufferedImage img = reader.read(0);
                    if (img != null) {
                        return img;
                    }
                } finally {
                    reader.dispose();
                }
            }
        }
        throw new IOException("no reader for " + format);
    }

    static BufferedImage cropAndScale(BufferedImage image, int length) {
        int side = Math.min(image.getWidth(), image.getHeight());
        int x = (image.getWidth() - side) / 2;
        int y = (image.getHeight() - side) / 2;
        BufferedImage cropped = image.getSubimage(x, y, side, side);
        BufferedImage out = new BufferedImage(length, length, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, length, length);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cropped, 0, 0, length, length, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }
}

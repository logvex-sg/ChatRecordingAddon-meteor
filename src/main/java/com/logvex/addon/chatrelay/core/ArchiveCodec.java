package com.logvex.addon.chatrelay.core;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GZIP + JSONL codec for report bodies.
 *
 * <p>Layout: {@code [magic int][crc32 of the compressed payload][gzip stream]}. The gzip stream
 * holds UTF-8 JSON Lines, one message per line. The CRC is checked on read, so a truncated or
 * bit-rotted file is reported as {@link ArchiveCorruptedException} instead of yielding garbage.
 */
public final class ArchiveCodec {
    private static final int MAGIC = 0x4C435250; // "LCRP"

    private ArchiveCodec() {
    }

    /** Encodes and compresses {@code messages} at the given gzip level (1..9). */
    public static byte[] encode(List<ChatMessage> messages, int compressionLevel) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(payload) {
            {
                def.setLevel(clampLevel(compressionLevel));
            }
        }) {
            for (ChatMessage message : messages) {
                gzip.write(Json.toJson(message.toMap()).getBytes(StandardCharsets.UTF_8));
                gzip.write('\n');
            }
        }

        byte[] compressed = payload.toByteArray();
        long crc = crc32(compressed);

        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length + 8);
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt((int) crc);
        data.write(compressed);
        data.flush();
        return out.toByteArray();
    }

    /** Decompresses a report body. Throws when the magic, checksum or gzip stream is invalid. */
    public static List<ChatMessage> decode(byte[] raw) throws ArchiveCorruptedException {
        if (raw == null || raw.length < 8) {
            throw new ArchiveCorruptedException("Archive entry is too short.");
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int magic = in.readInt();
            if (magic != MAGIC) throw new ArchiveCorruptedException("Bad archive magic.");
            int expectedCrc = in.readInt();
            byte[] compressed = in.readAllBytes();
            if (crc32(compressed) != (expectedCrc & 0xFFFFFFFFL)) {
                throw new ArchiveCorruptedException("Archive checksum mismatch.");
            }
            return decodeGzip(compressed);
        } catch (IOException e) {
            throw new ArchiveCorruptedException("Archive could not be read.", e);
        }
    }

    private static List<ChatMessage> decodeGzip(byte[] compressed) throws ArchiveCorruptedException {
        List<ChatMessage> messages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new GZIPInputStream(new BufferedInputStream(new ByteArrayInputStream(compressed))),
            StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) parseLine(line, messages);
        } catch (IOException e) {
            throw new ArchiveCorruptedException("Gzip stream is corrupt.", e);
        }
        return messages;
    }

    private static void parseLine(String line, List<ChatMessage> out) {
        if (line.isBlank()) return;
        Map<?, ?> map = Json.toMap(line);
        if (map != null) out.add(ChatMessage.fromMap(map));
    }

    private static int clampLevel(int level) {
        if (level < 1) return 1;
        return Math.min(level, 9);
    }

    private static long crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }
}

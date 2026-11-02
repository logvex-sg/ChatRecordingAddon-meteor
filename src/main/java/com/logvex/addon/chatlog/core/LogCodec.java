package com.logvex.addon.chatlog.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GZIP + JSONL codec for chat log segments.
 *
 * <p>Layout: {@code [magic int][crc32 of the compressed payload][gzip stream]}. The gzip stream
 * holds UTF-8 JSON Lines, one entry per line. The CRC is verified on read, so a truncated or
 * bit-rotted segment is reported as {@link ChatLogCorruptedException} instead of yielding garbage.
 */
public final class LogCodec {
    private static final int MAGIC = 0x4C4F4758; // "LOGX"

    private LogCodec() {
    }

    /** Encodes and compresses {@code entries} at the given gzip level (1..9). */
    public static byte[] encode(List<ChatEntry> entries, int compressionLevel) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        int level = clampLevel(compressionLevel);
        try (GZIPOutputStream gzip = new GZIPOutputStream(payload) {
            {
                def.setLevel(level);
            }
        }) {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8));
            for (ChatEntry entry : entries) {
                writer.write(Json.toJson(entry.toMap()));
                writer.write('\n');
            }
            writer.flush();
        }
        return frame(payload.toByteArray());
    }

    /** Decompresses a segment body. Throws when the magic, checksum or gzip stream is invalid. */
    public static List<ChatEntry> decode(byte[] raw) throws ChatLogCorruptedException {
        if (raw == null || raw.length < 8) {
            throw new ChatLogCorruptedException("Log segment is too short.");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int magic = in.readInt();
            if (magic != MAGIC) throw new ChatLogCorruptedException("Bad log segment magic.");
            int expectedCrc = in.readInt();
            byte[] compressed = in.readAllBytes();
            if (crc32(compressed) != (expectedCrc & 0xFFFFFFFFL)) {
                throw new ChatLogCorruptedException("Log segment checksum mismatch.");
            }
            return decodeGzip(compressed);
        } catch (IOException e) {
            throw new ChatLogCorruptedException("Log segment could not be read.", e);
        }
    }

    private static byte[] frame(byte[] compressed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length + 8);
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt((int) crc32(compressed));
        data.write(compressed);
        data.flush();
        return out.toByteArray();
    }

    private static List<ChatEntry> decodeGzip(byte[] compressed) throws ChatLogCorruptedException {
        List<ChatEntry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new GZIPInputStream(new ByteArrayInputStream(compressed)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) parseLine(line, entries);
        } catch (IOException e) {
            throw new ChatLogCorruptedException("Gzip stream is corrupt.", e);
        }
        return entries;
    }

    private static void parseLine(String line, List<ChatEntry> out) {
        if (line == null || line.isBlank()) return;
        Map<?, ?> map = Json.toMap(line);
        ChatEntry entry = ChatEntry.fromMap(map);
        if (entry != null) out.add(entry);
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

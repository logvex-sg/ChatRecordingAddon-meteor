package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.MceditSchematicReader;
import com.logvex.addon.schematicdropper.core.NbtNode;
import com.logvex.addon.schematicdropper.core.Schematic;
import com.logvex.addon.schematicdropper.core.SchematicFormat;
import com.logvex.addon.schematicdropper.core.SchematicParseException;
import com.logvex.addon.schematicdropper.core.SpongeSchematicReader;
import com.logvex.addon.schematicdropper.core.LitematicSchematicReader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Reads a schematic file into the Minecraft-free {@link Schematic} model.
 *
 * <p>Every failure is turned into a {@link SchematicParseException} with a user-facing reason, so the
 * module never has to show a raw exception. The readers themselves are pure Java; only the NBT
 * decode touches Minecraft.
 */
public final class SchematicFileLoader {
    /** Reads and parses a schematic, detecting the format from the extension and then the content. */
    public Schematic load(Path path) throws SchematicParseException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new SchematicParseException("That file does not exist.");
        }

        String fileName = path.getFileName().toString();
        SchematicFormat hinted = SchematicFormat.fromFileName(fileName);
        if (hinted == SchematicFormat.MATERIAL_LIST) {
            throw new SchematicParseException("That is a material list, not a schematic. Use Import Material List.");
        }

        NbtNode root = readNbt(path, fileName);

        // The extension is only a hint. Downloads are commonly renamed, have no extension, or carry
        // the wrong one, and a reader chosen from a wrong hint decodes the file into garbage. When
        // the hint is not a schematic, or the hinted reader cannot make sense of the tree, fall back
        // to detecting the format from the NBT structure itself.
        SchematicFormat format = hinted;
        if (!format.isSchematic()) {
            format = SchematicFormat.detect(root);
            if (!format.isSchematic()) {
                throw new SchematicParseException(
                    "Could not recognise " + fileName + " as a schematic (.schem, .schematic or .litematic).");
            }
        }

        try {
            return read(format, fileName, root);
        } catch (SchematicParseException first) {
            SchematicFormat detected = SchematicFormat.detect(root);
            if (detected != format && detected.isSchematic()) {
                // The hint was wrong: retry with the format the content actually describes.
                try {
                    return read(detected, fileName, root);
                } catch (SchematicParseException ignored) {
                    // Report the original failure, which describes the hinted format the user picked.
                }
            }
            throw first;
        }
    }

    private Schematic read(SchematicFormat format, String fileName, NbtNode root) throws SchematicParseException {
        try {
            return switch (format) {
                case SPONGE -> new SpongeSchematicReader().read(fileName, format, root);
                case MCEDIT -> new MceditSchematicReader(new LegacyBlockIdTable()).read(fileName, format, root);
                case LITEMATIC -> new LitematicSchematicReader().read(fileName, format, root);
                default -> throw new SchematicParseException("Unsupported schematic format.");
            };
        } catch (SchematicParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SchematicParseException("Could not read " + fileName + ": " + shortReason(e), e);
        }
    }

    private NbtNode readNbt(Path path, String fileName) throws SchematicParseException {
        try (InputStream raw = Files.newInputStream(path);
             InputStream buffered = new BufferedInputStream(raw);
             InputStream decompressed = maybeGzip(buffered);
             DataInputStream data = new DataInputStream(decompressed)) {
            NbtCompound compound = NbtIo.readCompound(data, NbtSizeTracker.ofUnlimitedBytes());
            if (compound == null) {
                throw new SchematicParseException("Could not read " + fileName + ": the file is not valid NBT.");
            }
            return NbtBridge.toNode(compound);
        } catch (IOException e) {
            throw new SchematicParseException("Could not read " + fileName + ": " + shortReason(e), e);
        } catch (RuntimeException e) {
            throw new SchematicParseException("Could not read " + fileName + ": " + shortReason(e), e);
        }
    }

    /**
     * Litematic files are gzip-compressed; Sponge files are often gzipped too. Files without the
     * magic bytes are read as-is, so an uncompressed schematic still loads.
     */
    private static InputStream maybeGzip(InputStream in) throws IOException {
        in.mark(2);
        int b1 = in.read();
        int b2 = in.read();
        in.reset();
        if (b1 == 0x1F && b2 == 0x8B) return new GZIPInputStream(in);
        return in;
    }

    private static String shortReason(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}

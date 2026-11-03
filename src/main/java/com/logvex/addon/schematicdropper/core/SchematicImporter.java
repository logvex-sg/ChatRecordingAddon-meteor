package com.logvex.addon.schematicdropper.core;

/**
 * Turns a schematic file into the Minecraft-free {@link Schematic} model.
 *
 * <p>Implementations are selected by {@link SchematicFormat}. The Minecraft adapter converts the
 * game's NBT into an {@link NbtNode} before calling a reader, so readers never reference Minecraft
 * classes and remain unit testable.
 */
public interface SchematicImporter {
    /** True when this importer can handle the given format. */
    boolean supports(SchematicFormat format);

    /**
     * Reads a schematic.
     *
     * @param name   display name (usually the file name)
     * @param format the detected format
     * @param root   the file's root NBT compound
     * @return the parsed schematic
     * @throws SchematicParseException when the file is not a valid schematic of this format
     */
    Schematic read(String name, SchematicFormat format, NbtNode root) throws SchematicParseException;
}

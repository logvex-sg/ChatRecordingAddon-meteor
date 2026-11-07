package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, Minecraft-free NBT tree.
 *
 * <p>Schematic files are NBT containers, but parsing them must not drag Minecraft types into the
 * core. The Minecraft adapter converts the game's {@code NbtCompound} into this tree once, and
 * every format reader then works on plain Java values and stays unit testable.
 */
public final class NbtNode {
    public enum Type {
        COMPOUND, LIST, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, STRING, BYTE_ARRAY, INT_ARRAY, LONG_ARRAY
    }

    private final Type type;
    private final Map<String, NbtNode> children = new LinkedHashMap<>();
    private final List<NbtNode> items = new ArrayList<>();
    private long number;
    private String text;
    private int[] ints;
    private byte[] bytes;
    private long[] longs;

    private NbtNode(Type type) {
        this.type = type;
    }

    public static NbtNode compound() {
        return new NbtNode(Type.COMPOUND);
    }

    public static NbtNode list() {
        return new NbtNode(Type.LIST);
    }

    public static NbtNode number(Type type, long value) {
        NbtNode node = new NbtNode(type);
        node.number = value;
        return node;
    }

    public static NbtNode string(String value) {
        NbtNode node = new NbtNode(Type.STRING);
        node.text = value;
        return node;
    }

    public static NbtNode byteArray(byte[] value) {
        NbtNode node = new NbtNode(Type.BYTE_ARRAY);
        node.bytes = value == null ? new byte[0] : value.clone();
        return node;
    }

    public static NbtNode intArray(int[] value) {
        NbtNode node = new NbtNode(Type.INT_ARRAY);
        node.ints = value == null ? new int[0] : value.clone();
        return node;
    }

    public static NbtNode longArray(long[] value) {
        NbtNode node = new NbtNode(Type.LONG_ARRAY);
        node.longs = value == null ? new long[0] : value.clone();
        return node;
    }

    public Type type() {
        return type;
    }

    /** Adds or replaces a child of a compound. */
    public NbtNode put(String key, NbtNode child) {
        if (child != null) children.put(key, child);
        return this;
    }

    /** Appends an element of a list. */
    public NbtNode add(NbtNode child) {
        if (child != null) items.add(child);
        return this;
    }

    public boolean has(String key) {
        return children.containsKey(key);
    }

    public NbtNode get(String key) {
        return children.get(key);
    }

    public Map<String, NbtNode> children() {
        return children;
    }

    public List<NbtNode> items() {
        return items;
    }

    public boolean isCompound() {
        return type == Type.COMPOUND;
    }

    public boolean isList() {
        return type == Type.LIST;
    }

    public boolean isString() {
        return type == Type.STRING;
    }

    public boolean isNumber() {
        return switch (type) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    public long asLong() {
        return number;
    }

    public int asInt() {
        return (int) number;
    }

    public double asDouble() {
        if (type == Type.FLOAT) return Float.intBitsToFloat((int) number);
        if (type == Type.DOUBLE) return Double.longBitsToDouble(number);
        return number;
    }

    public String asString() {
        return text == null ? "" : text;
    }

    public int[] asIntArray() {
        return ints == null ? new int[0] : ints;
    }

    public byte[] asByteArray() {
        return bytes == null ? new byte[0] : bytes;
    }

    public long[] asLongArray() {
        return longs == null ? new long[0] : longs;
    }

    // ------------------------------------------------------------- convenience

    public int getInt(String key, int fallback) {
        NbtNode child = children.get(key);
        return child != null && child.isNumber() ? child.asInt() : fallback;
    }

    public long getLong(String key, long fallback) {
        NbtNode child = children.get(key);
        return child != null && child.isNumber() ? child.asLong() : fallback;
    }

    public String getString(String key, String fallback) {
        NbtNode child = children.get(key);
        return child != null && child.isString() ? child.asString() : fallback;
    }

    public NbtNode getCompound(String key) {
        NbtNode child = children.get(key);
        return child != null && child.isCompound() ? child : null;
    }

    public NbtNode getList(String key) {
        NbtNode child = children.get(key);
        return child != null && child.isList() ? child : null;
    }

    public int[] getIntArray(String key) {
        NbtNode child = children.get(key);
        return child != null ? child.asIntArray() : new int[0];
    }

    public byte[] getByteArray(String key) {
        NbtNode child = children.get(key);
        return child != null ? child.asByteArray() : new byte[0];
    }

    public long[] getLongArray(String key) {
        NbtNode child = children.get(key);
        return child != null ? child.asLongArray() : new long[0];
    }

    /**
     * Reads an int array, accepting either a {@code TAG_Int_Array} or a {@code TAG_List} of numbers.
     * Some schematic writers (and hand-edited files) store {@code Size} as a list of ints rather than
     * an int array, which would otherwise be read as empty and reject an otherwise valid file.
     */
    public int[] getIntArrayLoose(String key) {
        NbtNode child = children.get(key);
        if (child == null) return new int[0];
        if (child.type == Type.INT_ARRAY) return child.asIntArray();
        if (child.isList()) {
            int[] out = new int[child.items.size()];
            for (int i = 0; i < out.length; i++) {
                NbtNode item = child.items.get(i);
                out[i] = item != null && item.isNumber() ? item.asInt() : 0;
            }
            return out;
        }
        return new int[0];
    }

    /**
     * Reads a long array, accepting either a {@code TAG_Long_Array} or a {@code TAG_List} of longs,
     * for the same reason as {@link #getIntArrayLoose}.
     */
    public long[] getLongArrayLoose(String key) {
        NbtNode child = children.get(key);
        if (child == null) return new long[0];
        if (child.type == Type.LONG_ARRAY) return child.asLongArray();
        if (child.isList()) {
            long[] out = new long[child.items.size()];
            for (int i = 0; i < out.length; i++) {
                NbtNode item = child.items.get(i);
                out[i] = item != null && item.isNumber() ? item.asLong() : 0L;
            }
            return out;
        }
        return new long[0];
    }

    /** The number of children of a compound or items of a list; 0 for any other type. */
    public int childCount(String key) {
        NbtNode child = children.get(key);
        if (child == null) return 0;
        if (child.isCompound()) return child.children.size();
        if (child.isList()) return child.items.size();
        return 0;
    }

    @Override
    public String toString() {
        return switch (type) {
            case COMPOUND -> "compound(" + children.size() + ")";
            case LIST -> "list(" + items.size() + ")";
            case STRING -> text;
            default -> Long.toString(number);
        };
    }
}

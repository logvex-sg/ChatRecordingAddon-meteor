package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.NbtNode;
import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtByteArray;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.NbtShort;
import net.minecraft.nbt.NbtString;

/**
 * Converts Minecraft's NBT into the Minecraft-free {@link NbtNode} tree.
 *
 * <p>This is the only place the schematic readers meet Minecraft: everything above it (the format
 * readers, comparison, solver, placement engine) works on plain Java values, which is what keeps the
 * pipeline unit testable without a running client.
 */
public final class NbtBridge {
    private NbtBridge() {
    }

    public static NbtNode toNode(NbtElement element) {
        if (element == null) return null;

        if (element instanceof NbtCompound compound) {
            NbtNode node = NbtNode.compound();
            for (String key : compound.getKeys()) {
                NbtNode child = toNode(compound.get(key));
                if (child != null) node.put(key, child);
            }
            return node;
        }

        if (element instanceof NbtList list) {
            NbtNode node = NbtNode.list();
            for (NbtElement item : list) {
                NbtNode child = toNode(item);
                if (child != null) node.add(child);
            }
            return node;
        }

        if (element instanceof NbtString string) {
            return NbtNode.string(string.asString());
        }
        if (element instanceof NbtByte b) {
            return NbtNode.number(NbtNode.Type.BYTE, b.byteValue());
        }
        if (element instanceof NbtShort s) {
            return NbtNode.number(NbtNode.Type.SHORT, s.shortValue());
        }
        if (element instanceof NbtInt i) {
            return NbtNode.number(NbtNode.Type.INT, i.intValue());
        }
        if (element instanceof NbtLong l) {
            return NbtNode.number(NbtNode.Type.LONG, l.longValue());
        }
        if (element instanceof NbtFloat f) {
            return NbtNode.number(NbtNode.Type.FLOAT, Float.floatToRawIntBits(f.floatValue()));
        }
        if (element instanceof NbtDouble d) {
            return NbtNode.number(NbtNode.Type.DOUBLE, Double.doubleToRawLongBits(d.doubleValue()));
        }
        if (element instanceof NbtByteArray a) {
            return NbtNode.byteArray(a.getByteArray());
        }
        if (element instanceof NbtIntArray a) {
            return NbtNode.intArray(a.getIntArray());
        }
        if (element instanceof NbtLongArray a) {
            return NbtNode.longArray(a.getLongArray());
        }
        return null;
    }
}

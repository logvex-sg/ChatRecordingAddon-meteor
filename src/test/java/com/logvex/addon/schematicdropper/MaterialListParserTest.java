package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.MaterialListParser;
import com.logvex.addon.schematicdropper.core.Requirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialListParserTest {
    @Test
    @DisplayName("parses id and quantity")
    void parsesQuantity() {
        Requirement r = MaterialListParser.parseLine("minecraft:cobblestone 128");
        assertNotNull(r);
        assertEquals("minecraft:cobblestone", r.itemId());
        assertEquals(128, r.required());
    }

    @Test
    @DisplayName("accepts an x-prefixed quantity")
    void parsesXQuantity() {
        Requirement r = MaterialListParser.parseLine("cobblestone x32");
        assertNotNull(r);
        assertEquals("minecraft:cobblestone", r.itemId());
        assertEquals(32, r.required());
    }

    @Test
    @DisplayName("defaults the quantity when it is omitted")
    void defaultsQuantity() {
        Requirement r = MaterialListParser.parseLine("minecraft:stone");
        assertNotNull(r);
        assertEquals(MaterialListParser.DEFAULT_QUANTITY, r.required());
    }

    @Test
    @DisplayName("keeps a human readable name when one is supplied")
    void keepsDisplayName() {
        Requirement r = MaterialListParser.parseLine("minecraft:oak_planks 64 Oak Planks");
        assertNotNull(r);
        assertEquals(64, r.required());
        assertEquals("Oak Planks", r.displayName());
    }

    @Test
    @DisplayName("skips blank lines and comments")
    void skipsNoise() {
        assertNull(MaterialListParser.parseLine(""));
        assertNull(MaterialListParser.parseLine("   "));
        assertNull(MaterialListParser.parseLine("# a comment"));
    }

    @Test
    @DisplayName("rejects malformed ids")
    void rejectsMalformed() {
        assertNull(MaterialListParser.parseLine(":cobblestone 10"));
        assertNull(MaterialListParser.parseLine("minecraft: 10"));
        assertNull(MaterialListParser.parseLine("bad:item:name 10"));
    }

    @Test
    @DisplayName("duplicate ids keep the last quantity and stay ordered")
    void duplicatesCollapse() {
        List<Requirement> parsed = MaterialListParser.parse(List.of(
            "minecraft:cobblestone 64",
            "minecraft:stone 32",
            "minecraft:cobblestone 128"
        ));
        assertEquals(2, parsed.size());
        assertEquals("minecraft:cobblestone", parsed.get(0).itemId());
        assertEquals(128, parsed.get(0).required());
    }

    @Test
    @DisplayName("a bare id gets a prettified display name")
    void prettyName() {
        Requirement r = MaterialListParser.parseLine("minecraft:oak_planks 64");
        assertNotNull(r);
        assertEquals("Oak Planks", r.displayName());
    }

    @Test
    @DisplayName("empty input yields an empty plan")
    void emptyInput() {
        assertTrue(MaterialListParser.parse(List.of()).isEmpty());
        assertTrue(MaterialListParser.parse(null).isEmpty());
    }
}

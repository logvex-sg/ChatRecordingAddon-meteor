package com.logvex.addon.command;

import com.logvex.addon.bot.core.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskArgsTest {

    @Test
    @DisplayName("task names match case-insensitively and ignore surrounding space")
    void parsesTaskTypes() {
        assertEquals(TaskType.GATHERING, TaskArgs.parseType("gathering"));
        assertEquals(TaskType.GATHERING, TaskArgs.parseType("  GATHERING "));
        assertEquals(TaskType.WOODCUTTING, TaskArgs.parseType("WoodCutting"));
        assertEquals(TaskType.FISHING, TaskArgs.parseType("fishing"));
    }

    @Test
    @DisplayName("unknown and blank task names return null rather than throwing")
    void rejectsUnknownTaskTypes() {
        assertNull(TaskArgs.parseType("teleport"));
        assertNull(TaskArgs.parseType(""));
        assertNull(TaskArgs.parseType("   "));
        assertNull(TaskArgs.parseType(null));
    }

    @Test
    @DisplayName("the accepted-name list contains every task type")
    void listsEveryType() {
        String list = TaskArgs.typeNames();
        assertNotNull(list);
        for (TaskType type : TaskType.values()) {
            assertTrue(list.contains(type.name().toLowerCase(java.util.Locale.ROOT)),
                "missing " + type);
        }
        // Comma separated, lower-cased, no trailing separator.
        assertTrue(list.startsWith("gathering"));
        assertTrue(!list.endsWith(", "));
    }

    @Test
    @DisplayName("bare item names gain the minecraft namespace")
    void namespacesBareItems() {
        assertEquals("minecraft:oak_log", TaskArgs.normaliseItem("oak_log"));
        assertEquals("minecraft:oak_log", TaskArgs.normaliseItem("OAK_LOG"));
        assertEquals("minecraft:oak_log", TaskArgs.normaliseItem("  Oak_Log  "));
        assertEquals("minecraft:oak_log", TaskArgs.normaliseItem("oak log"));
    }

    @Test
    @DisplayName("namespaced item names are kept and lower-cased")
    void keepsNamespaces() {
        assertEquals("minecraft:oak_log", TaskArgs.normaliseItem("Minecraft:Oak_Log"));
        assertEquals("mymod:thing", TaskArgs.normaliseItem("mymod:thing"));
    }

    @Test
    @DisplayName("blank item names produce an empty string, never a bare namespace")
    void rejectsBlankItems() {
        assertEquals("", TaskArgs.normaliseItem(null));
        assertEquals("", TaskArgs.normaliseItem(""));
        assertEquals("", TaskArgs.normaliseItem("   "));
    }
}

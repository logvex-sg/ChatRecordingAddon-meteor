package com.logvex.addon.framework.systems.modules;

import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.StringListSetting;
import com.logvex.addon.framework.systems.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configuration must survive a restart, tolerate a damaged file, and never reset a value the
 * user chose. These tests exercise the real save/load path against a temp file.
 */
class ConfigManagerTest {

    private static final Category CATEGORY = new Category("Test");

    /** A module with the three setting shapes the config has to round-trip. */
    private static class SampleModule extends Module {
        final Setting<Boolean> enabled;
        final Setting<Integer> limit;
        final Setting<List<String>> names;

        SampleModule(String name) {
            super(CATEGORY, name, "test module");
            enabled = settings.getDefaultGroup().add(new BoolSetting.Builder()
                .name("enabled").defaultValue(false).build());
            limit = settings.getDefaultGroup().add(new IntSetting.Builder()
                .name("limit").defaultValue(5).min(0).max(100).build());
            names = settings.getDefaultGroup().add(new StringListSetting.Builder()
                .name("names").defaultValue("a", "b").build());
        }
    }

    private final ConfigManager config = ConfigManager.get();

    @AfterEach
    void tearDown() {
        config.setFile(null);
    }

    @Test
    @DisplayName("settings round-trip through save and load")
    void roundTrip(@TempDir Path dir) {
        Path file = dir.resolve("config.json");
        config.setFile(file);

        SampleModule module = new SampleModule("round-trip") {};
        Modules.get().add(module);
        module.enabled.set(true);
        module.limit.set(42);
        module.names.set(List.of("x", "y", "z"));
        config.save();

        assertTrue(Files.isRegularFile(file), "the config file should have been written");

        // Simulate a restart: change every value, then load the file back over them.
        module.enabled.set(false);
        module.limit.set(1);
        module.names.set(List.of("changed"));

        config.load();

        assertTrue(module.enabled.get(), "the boolean should be restored");
        assertEquals(42, module.limit.get(), "the integer should be restored");
        assertEquals(List.of("x", "y", "z"), module.names.get(), "the list should be restored");
    }

    @Test
    @DisplayName("a missing config file leaves every default untouched")
    void missingFileKeepsDefaults(@TempDir Path dir) {
        config.setFile(dir.resolve("does-not-exist.json"));

        SampleModule module = new SampleModule("missing-file") {};
        Modules.get().add(module);

        config.load();

        assertFalse(module.enabled.get());
        assertEquals(5, module.limit.get());
        assertEquals(List.of("a", "b"), module.names.get());
    }

    @Test
    @DisplayName("a corrupt config file is ignored rather than crashing the addon")
    void corruptFileIsIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{ this is not json ");
        config.setFile(file);

        SampleModule module = new SampleModule("corrupt-file") {};
        Modules.get().add(module);

        config.load();

        assertEquals(5, module.limit.get(), "defaults must survive a corrupt file");
    }

    @Test
    @DisplayName("an unknown key is preserved across a save")
    void unknownKeysArePreserved(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        config.setFile(file);

        SampleModule module = new SampleModule("unknown-keys") {};
        Modules.get().add(module);
        config.save();

        // Add a key a newer build might have written, then save again.
        String json = Files.readString(file).replace("\"modules\"", "\"future-feature\": 123, \"modules\"");
        Files.writeString(file, json);

        config.load();
        config.save();

        assertTrue(Files.readString(file).contains("future-feature"),
            "a key this build does not understand must not be dropped");
    }

    @Test
    @DisplayName("saveIfDirty only writes after a change")
    void saveIfDirtyRespectsFlag(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        config.setFile(file);

        SampleModule module = new SampleModule("dirty-flag") {};
        Modules.get().add(module);

        config.save();
        assertFalse(config.isDirty());

        config.markDirty();
        assertTrue(config.isDirty());
        config.saveIfDirty();
        assertFalse(config.isDirty());
        assertTrue(Files.isRegularFile(file));
    }
}

package com.logvex.addon.profiles;

import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profile capture, apply, persistence and the tolerance rules that keep an old profile valid when
 * settings are added or removed.
 */
class ProfileManagerTest {
    private static Settings settingsWithDefaults() {
        Settings settings = new Settings();
        settings.getDefaultGroup().add(new IntSetting.Builder()
            .name("gather-radius").description("how far to look").defaultValue(32).min(1).max(128).build());
        settings.getDefaultGroup().add(new IntSetting.Builder()
            .name("max-scan-results").description("scan cap").defaultValue(512).min(1).max(4096).build());
        return settings;
    }

    private static int intValue(Settings settings, String name) {
        return settings.get(name).toJson().getAsInt();
    }

    private static void setInt(Settings settings, String name, int value) {
        settings.get(name).fromJson(value);
    }

    @Test
    @DisplayName("captures only changed settings and applies them back")
    void captureAndApply() {
        Settings settings = settingsWithDefaults();
        ProfileManager manager = new ProfileManager();

        setInt(settings, "gather-radius", 64);
        setInt(settings, "max-scan-results", 1024);

        BotProfile profile = manager.capture("My Setup", settings);
        assertNotNull(profile);
        assertEquals(2, profile.size());
        assertFalse(profile.builtin());

        settings.get("gather-radius").reset();
        settings.get("max-scan-results").reset();
        assertEquals(32, intValue(settings, "gather-radius"));

        int changed = manager.apply(profile, settings);
        assertEquals(2, changed);
        assertEquals(64, intValue(settings, "gather-radius"));
        assertEquals(1024, intValue(settings, "max-scan-results"));
    }

    @Test
    @DisplayName("a setting absent from the profile is left untouched")
    void applyLeavesUnmentionedSettingsAlone() {
        Settings settings = settingsWithDefaults();
        ProfileManager manager = new ProfileManager();

        setInt(settings, "gather-radius", 64);
        BotProfile profile = manager.capture("Radius Only", settings);
        setInt(settings, "max-scan-results", 2048);
        settings.get("gather-radius").reset();

        manager.apply(profile, settings);

        assertEquals(64, intValue(settings, "gather-radius"));
        // max-scan-results was never in the profile, so it keeps the value the user just set.
        assertEquals(2048, intValue(settings, "max-scan-results"));
    }

    @Test
    @DisplayName("the builtin Default profile resets every changed setting")
    void defaultProfileResets() {
        Settings settings = settingsWithDefaults();
        ProfileManager manager = new ProfileManager();

        setInt(settings, "gather-radius", 96);
        setInt(settings, "max-scan-results", 3000);

        int changed = manager.apply(manager.get("Default"), settings);
        assertEquals(2, changed);
        assertEquals(32, intValue(settings, "gather-radius"));
        assertEquals(512, intValue(settings, "max-scan-results"));
    }

    @Test
    @DisplayName("builtins cannot be overwritten or removed")
    void builtinsAreProtected() {
        Settings settings = settingsWithDefaults();
        ProfileManager manager = new ProfileManager();

        assertNull(manager.capture("Default", settings));
        assertFalse(manager.remove("Default"));
        assertTrue(manager.get("Default").builtin());
    }

    @Test
    @DisplayName("recapturing the same name replaces the user profile")
    void captureReplacesUserProfile() {
        Settings settings = settingsWithDefaults();
        ProfileManager manager = new ProfileManager();
        setInt(settings, "gather-radius", 10);
        manager.capture("P", settings);
        setInt(settings, "gather-radius", 70);
        manager.capture("P", settings);
        assertEquals(70, manager.get("P").get("gather-radius").getAsInt());
        assertEquals(1, manager.userProfiles().size());
    }

    @Test
    @DisplayName("user profiles round-trip through disk and builtins are not written")
    void persistenceRoundTrip(@TempDir Path dir) throws Exception {
        Settings settings = settingsWithDefaults();
        ProfileManager manager = new ProfileManager();
        setInt(settings, "gather-radius", 48);
        manager.capture("Saved", settings);

        assertTrue(manager.save(dir));
        assertTrue(Files.isRegularFile(ProfileStore.fileIn(dir)));
        String text = Files.readString(ProfileStore.fileIn(dir));
        assertFalse(text.contains("\"Default\""), "builtins must not be persisted");

        ProfileManager reloaded = new ProfileManager();
        reloaded.load(dir);
        assertNotNull(reloaded.get("Saved"));
        assertEquals(48, reloaded.get("Saved").get("gather-radius").getAsInt());
        assertTrue(reloaded.get("Default").builtin());
    }

    @Test
    @DisplayName("a corrupt profiles file loads as empty rather than throwing")
    void corruptFileIsTolerated(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(ProfileStore.fileIn(dir), "{ not valid json");
        ProfileManager manager = new ProfileManager();
        manager.load(dir);
        assertEquals(0, manager.userProfiles().size());
        assertNotNull(manager.get("Default"));
    }

    @Test
    @DisplayName("shipped builtins are present and marked built-in")
    void builtinsPresent() {
        List<BotProfile> builtins = ProfileManager.builtins();
        assertFalse(builtins.isEmpty());
        for (BotProfile profile : builtins) {
            assertTrue(profile.builtin());
        }
    }

    @Test
    @DisplayName("nothing is changed when applying an unknown-named profile")
    void applyMissingProfileChangesNothing() {
        Settings settings = settingsWithDefaults();
        ProfileManager manager = new ProfileManager();
        setInt(settings, "gather-radius", 55);
        assertEquals(0, manager.apply(manager.get("does-not-exist"), settings));
        assertEquals(55, intValue(settings, "gather-radius"));
    }
}

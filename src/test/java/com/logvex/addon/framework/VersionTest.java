package com.logvex.addon.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mod version must never be a second, hand-maintained copy of {@code mod_version}.
 *
 * <p>It was one once, and it drifted to 17.2.0 while the build said 18.1.0. The updater then
 * compared releases against a number three versions old, so it offered an "upgrade" to something
 * already installed, and the GUI rail displayed the wrong version.
 *
 * <p>{@code LogClientContext} cannot be loaded in a plain JVM — its static initialiser reaches
 * {@code MeteorClient} — so these tests assert the invariants in the source and build files
 * instead of reading the constant. That is the same drift, checked where it can actually happen.
 */
class VersionTest {

    @Test
    @DisplayName("the version is never a hardcoded literal that can drift from the build")
    void versionIsNotHardcoded() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/com/logvex/addon/framework/LogClientContext.java"));

        assertTrue(source.contains("getModContainer(MOD_ID)"),
            "the version must be read from the loaded mod metadata");
        assertFalse(source.matches("(?s).*VERSION\\s*=\\s*\"\\d.*"),
            "a literal version string here drifts from gradle.properties; read the metadata instead");
    }

    @Test
    @DisplayName("the version read at runtime matches the one the build stamped")
    void buildVersionIsTheOnlySource() throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(Path.of("gradle.properties"))) {
            properties.load(in);
        }
        String declared = properties.getProperty("mod_version");
        assertTrue(declared != null && !declared.isBlank(),
            "gradle.properties must declare mod_version");

        // The runtime value comes from fabric.mod.json, which the build expands from mod_version.
        // Asserting the file is expanded from the property, rather than restating the number, is
        // what keeps this test from becoming the very duplicate it guards against.
        String modJson = Files.readString(Path.of("src/main/resources/fabric.mod.json"));
        assertTrue(modJson.contains("\"version\": \"${version}\""),
            "fabric.mod.json must take its version from the build, not a literal");
    }
}

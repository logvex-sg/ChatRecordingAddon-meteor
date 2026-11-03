package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.ItemResolver;
import com.logvex.addon.schematicdropper.core.MaterialListImporter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An {@link ItemResolver} backed by the real Minecraft 1.21.1 item id list captured in
 * {@code src/test/resources/minecraft-1.21.1-items.txt}. It lets the importer tests validate
 * against the actual registry without booting the game.
 */
final class FixtureRegistry implements ItemResolver {
    private static final Set<String> IDS = new HashSet<>();
    private static final Map<String, String> BY_NAME = new HashMap<>();

    static {
        try (InputStream in = FixtureRegistry.class.getResourceAsStream("/minecraft-1.21.1-items.txt")) {
            if (in != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String path = line.trim();
                    if (path.isEmpty()) continue;
                    String id = "minecraft:" + path;
                    IDS.add(id);
                    BY_NAME.putIfAbsent(normalise(path), id);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not load the item id fixture", e);
        }
    }

    static int size() {
        return IDS.size();
    }

    @Override
    public boolean isValidId(String itemId) {
        return itemId != null && IDS.contains(itemId);
    }

    @Override
    public String resolveName(String name) {
        if (name == null) return null;
        return BY_NAME.get(normalise(name));
    }

    private static String normalise(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
    }
}

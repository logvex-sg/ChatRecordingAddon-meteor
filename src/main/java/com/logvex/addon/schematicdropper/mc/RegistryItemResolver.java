package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.ItemResolver;
import com.logvex.addon.schematicdropper.core.MaterialListImporter;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves material names against the live Minecraft 1.21.1 item registry.
 *
 * <p>Ids are validated with {@link Registries#ITEM}, so an entry that is syntactically fine but
 * names an item that does not exist is reported as invalid instead of silently entering the
 * queue. Human readable names are matched by their underscored path, so {@code quartz pillar}
 * resolves to {@code minecraft:quartz_pillar}.
 */
public final class RegistryItemResolver implements ItemResolver {
    private final Map<String, String> byName = new HashMap<>();

    public RegistryItemResolver() {
        for (Identifier id : Registries.ITEM.getIds()) {
            byName.putIfAbsent(normalise(id.getPath()), id.toString());
        }
    }

    @Override
    public boolean isValidId(String itemId) {
        if (!MaterialListImporter.isSyntacticallyValidId(itemId)) return false;
        Identifier id = Identifier.tryParse(itemId);
        return id != null && Registries.ITEM.containsId(id);
    }

    @Override
    public String resolveName(String name) {
        if (name == null) return null;
        return byName.get(normalise(name));
    }

    private static String normalise(String text) {
        String lower = text.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean lastSpace = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '_' || c == ' ' || c == '-' || c == '\t') {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastSpace = true;
                }
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        int end = sb.length();
        if (end > 0 && sb.charAt(end - 1) == ' ') sb.setLength(end - 1);
        return sb.toString();
    }
}

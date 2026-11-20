package com.logvex.addon.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Module names must be unique, both among our modules and against Meteor's.
 *
 * <p>Meteor's {@code Modules.add} keys instances by class but de-duplicates by name: adding a module
 * whose name already exists removes the earlier one. Reusing a Meteor name therefore deletes
 * Meteor's module from its own registry. Meteor's mixins look modules up by class and use the
 * result without a null check, so the deletion is not cosmetic — while this addon held
 * {@code auto-reconnect}, {@code DisconnectedScreenMixin} read
 * {@code Modules.get().get(AutoReconnect.class).time} on a null and crashed the client on every
 * disconnect.
 *
 * <p>These tests read the sources rather than the live registry because Meteor's registry only
 * exists inside a running game. Modules are found by following {@code extends} to Meteor's
 * {@code Module}, so a module behind an intermediate base is still checked while commands, which
 * also call {@code super("name")}, are not.
 */
class ModuleNameTest {

    private static final Pattern TYPE_DECL = Pattern.compile(
        "\\b(?:class|record|enum)\\s+(\\w+)[^{]*?\\bextends\\s+([\\w.]+)");
    private static final Pattern SUPER_CALL = Pattern.compile("super\\s*\\(");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Meteor's own module base class, and this addon's, which itself extends Meteor's. */
    private static final Set<String> MODULE_BASES = Set.of("Module", "AddonModule");

    @Test
    @DisplayName("no two of our modules share a name")
    void ourNamesAreUnique() throws IOException {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        ourModules().forEach((className, name) ->
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(className));

        List<String> clashes = new ArrayList<>();
        byName.forEach((name, classes) -> {
            if (classes.size() > 1) clashes.add(name + " -> " + classes);
        });

        assertTrue(clashes.isEmpty(),
            "these names are used by more than one module, so Meteor keeps only one of each: " + clashes);
    }

    @Test
    @DisplayName("no module reuses a name Meteor already registered")
    void namesDoNotShadowMeteor() throws IOException {
        Set<String> meteor = meteorNames();
        assertTrue(meteor.size() > 100,
            "the Meteor name list looks truncated; it should hold every shipped module name");

        List<String> clashes = new ArrayList<>();
        ourModules().forEach((className, name) -> {
            if (meteor.contains(name)) clashes.add(className + " -> " + name);
        });

        assertTrue(clashes.isEmpty(),
            "these modules reuse a Meteor module name, which deletes Meteor's module from its"
                + " registry and can crash it: " + clashes);
    }

    /** Every addon module that registers a name, as class name -> registry name. */
    private static Map<String, String> ourModules() throws IOException {
        Map<String, String> parentOf = new HashMap<>();
        Map<String, String> sourceOf = new HashMap<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher decl = TYPE_DECL.matcher(source);
                if (!decl.find()) continue;
                String className = decl.group(1);
                parentOf.put(className, simpleName(decl.group(2)));
                sourceOf.put(className, source);
            }
        }

        Map<String, String> found = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sourceOf.entrySet()) {
            if (!isModule(entry.getKey(), parentOf)) continue;
            String name = registeredName(entry.getValue());
            if (name != null) found.put(entry.getKey(), name);
        }

        assertTrue(found.size() > 30, "expected to find the addon's modules, found " + found.size());
        return found;
    }

    /** True when the class extends Meteor's {@code Module}, directly or through our own bases. */
    private static boolean isModule(String className, Map<String, String> parentOf) {
        Set<String> seen = new HashSet<>();
        String current = parentOf.get(className);
        while (current != null && seen.add(current)) {
            if (MODULE_BASES.contains(current)) return true;
            current = parentOf.get(current);
        }
        return false;
    }

    /** The name the module registers in its {@code super(...)} call, or null if it has none. */
    private static String registeredName(String source) {
        Matcher matcher = SUPER_CALL.matcher(source);
        while (matcher.find()) {
            List<String> literals = stringLiterals(argumentsAt(source, matcher.end()));
            if (!literals.isEmpty() && isRegistryName(literals.get(0))) return literals.get(0);
        }
        return null;
    }

    private static String simpleName(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    private static String argumentsAt(String source, int from) {
        int depth = 1;
        StringBuilder out = new StringBuilder();
        for (int i = from; i < source.length() && depth > 0; i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth > 0) out.append(c);
        }
        return out.toString();
    }

    private static List<String> stringLiterals(String args) {
        List<String> out = new ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(args);
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    /** Module names are lower-case words joined by hyphens; a description is not a name. */
    private static boolean isRegistryName(String text) {
        return text.matches("[a-z0-9][a-z0-9-]*");
    }

    private static Set<String> meteorNames() throws IOException {
        Set<String> names = new HashSet<>();
        try (InputStream in = ModuleNameTest.class.getResourceAsStream("/meteor-module-names.txt")) {
            assertTrue(in != null, "meteor-module-names.txt is missing from the test resources");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) names.add(trimmed);
            }
        }
        return names;
    }
}

package com.logvex.addon.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Numeric settings that drive a timer must carry a hard {@code range()}, not only a sliderRange().
 *
 * <p>Meteor's {@code sliderRange} bounds the widget, not the value: {@code IntSetting} defaults its
 * {@code min}/{@code max} to {@code Integer.MIN_VALUE}/{@code Integer.MAX_VALUE}, so a value typed
 * into the text field — or restored from an older config — is accepted and saved. A shipped config
 * held {@code max-attempts = 40000}, which turned Auto Reconnect's bounded backoff into an endless
 * reconnect loop; the controller only clamps the floor. Auto Log's {@code rearm-seconds} had the
 * same hole in the other direction: a negative value made its guard never trip.
 *
 * <p>This reads the sources because a module cannot be constructed without a bootstrapped
 * Minecraft, and the bound is a property of the builder call rather than of any runtime value.
 */
class SettingBoundsTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** Settings whose value feeds a timer or a repeated action, so an absurd value matters. */
    private static final List<String> TIMED_SETTINGS = List.of(
        "base-delay-seconds",
        "max-delay-seconds",
        "max-attempts",
        "grace-seconds",
        "rearm-seconds",
        "interval-seconds",
        "cooldown-seconds",
        "min-interval-seconds",
        "max-interval-seconds"
    );

    private static final Pattern BUILDER = Pattern.compile(
        "new (?:Int|Double|Long)Setting\\.Builder\\(\\)(.*?)\\.build\\(\\)", Pattern.DOTALL);

    private record Found(String file, String name, String body) {}

    private static List<Found> numericSettings() throws IOException {
        List<Found> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                Matcher builder = BUILDER.matcher(source);
                while (builder.find()) {
                    String body = builder.group(1);
                    Matcher name = Pattern.compile("\\.name\\(\"([^\"]+)\"\\)").matcher(body);
                    if (name.find()) found.add(new Found(path.getFileName().toString(), name.group(1), body));
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("every timed numeric setting has a hard range(), not just a sliderRange()")
    void timedSettingsAreHardBounded() throws IOException {
        List<String> unbounded = new ArrayList<>();
        for (Found setting : numericSettings()) {
            if (!TIMED_SETTINGS.contains(setting.name())) continue;
            boolean slider = setting.body().contains(".sliderRange(");
            boolean hard = setting.body().contains(".range(")
                || (setting.body().contains(".min(") && setting.body().contains(".max("));
            if (slider && !hard) unbounded.add(setting.file() + " -> " + setting.name());
        }
        assertTrue(unbounded.isEmpty(),
            "these settings can hold an out-of-slider value, so a timer may never fire or fire "
                + "every tick:\n  " + String.join("\n  ", unbounded));
    }

    @Test
    @DisplayName("the auto reconnect backoff cannot be given an unbounded attempt budget")
    void reconnectBudgetIsBounded() throws IOException {
        Found attempts = numericSettings().stream()
            .filter(s -> s.name().equals("max-attempts") && s.file().equals("AutoReconnectModule.java"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("AutoReconnectModule no longer has a max-attempts setting"));

        assertTrue(attempts.body().contains(".range("),
            "max-attempts must be hard-bounded: sliderRange alone accepted 40000 and turned the "
                + "bounded backoff into an endless reconnect loop");
    }
}

package com.logvex.addon.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The local player's own state: what the client reads off its own player object.
 *
 * <p>Split out of {@link ServerStats} because these figures share a lifetime — they all come from
 * {@code mc.player} and are all absent together when there is no player. Keeping them together is
 * what stops the server record growing a field per gadget, and it makes the "no player" case one
 * null check instead of twenty.
 *
 * <p>Every value is what the client already knows. A figure the client has not been told (an
 * unopened inventory slot, a target the player is not aiming at) is {@link #EMPTY}-shaped rather
 * than a plausible default, so the renderer can print {@code n/a} instead of inventing something.
 */
public record PlayerView(
    float health,
    float maxHealth,
    float absorption,
    int food,
    int gamemode,
    String gamemodeName,
    double x,
    double y,
    double z,
    String facing,
    double speed,
    String heldItem,
    String offhandItem,
    List<String> armor,
    List<String> effects,
    String target,
    boolean targetIsEntity,
    float saturation,
    int level,
    float xpProgress,
    int air,
    int totems
) {
    /** No player: every figure is absent. */
    public static final PlayerView EMPTY = new PlayerView(
        0f, 0f, 0f, 0, -1, "", 0.0, 0.0, 0.0, "", 0.0, "", "", List.of(), List.of(), "", false,
        0f, 0, 0f, 0, 0);

    /**
     * The original seventeen-field form.
     *
     * <p>Kept so a caller that predates the extra player figures still compiles, and so a test can
     * describe only what it cares about. The added fields take their "absent" values.
     */
    public PlayerView(float health, float maxHealth, float absorption, int food, int gamemode,
                      String gamemodeName, double x, double y, double z, String facing,
                      double speed, String heldItem, String offhandItem, List<String> armor,
                      List<String> effects, String target, boolean targetIsEntity) {
        this(health, maxHealth, absorption, food, gamemode, gamemodeName, x, y, z, facing, speed,
            heldItem, offhandItem, armor, effects, target, targetIsEntity, 0f, 0, 0f, 0, 0);
    }

    public PlayerView {
        if (gamemodeName == null) gamemodeName = "";
        if (facing == null) facing = "";
        if (heldItem == null) heldItem = "";
        if (offhandItem == null) offhandItem = "";
        if (target == null) target = "";
        if (armor == null) armor = List.of();
        if (effects == null) effects = List.of();
        armor = List.copyOf(armor);
        effects = List.copyOf(effects);
        if (health < 0f) health = 0f;
        if (maxHealth < 0f) maxHealth = 0f;
        if (absorption < 0f) absorption = 0f;
        if (food < 0) food = 0;
        if (speed < 0.0) speed = 0.0;
        if (saturation < 0f) saturation = 0f;
        if (level < 0) level = 0;
        if (xpProgress < 0f) xpProgress = 0f;
        if (xpProgress > 1f) xpProgress = 1f;
        if (air < 0) air = 0;
        if (totems < 0) totems = 0;
    }

    /** True when there is no player to read. */
    public boolean absent() {
        return gamemode < 0;
    }

    /** True when the player has any absorption hearts to show. */
    public boolean hasAbsorption() {
        return absorption > 0f;
    }

    /** Health including absorption, which is how the vanilla bar reads. */
    public float totalHealth() {
        return health + absorption;
    }

    /** The game mode as a lower-case name, or {@code n/a} when unknown. */
    public String gamemodeLabel() {
        return gamemodeName.isBlank() ? "n/a" : gamemodeName;
    }

    /** The armour slots that hold something, as {@code label: item} entries. */
    public List<String> wornArmor() {
        List<String> out = new ArrayList<>(armor.size());
        for (String piece : armor) {
            if (piece != null && !piece.isBlank()) out.add(piece);
        }
        return List.copyOf(out);
    }

    /** True when the player is holding nothing in either hand. */
    public boolean handsEmpty() {
        return heldItem.isBlank() && offhandItem.isBlank();
    }

    /** The block or entity being looked at, or {@code n/a} when the player is not aiming at one. */
    public String targetLabel() {
        return target.isBlank() ? "n/a" : target;
    }

    /** Health as {@code 18.5/20}, one decimal, matching the vanilla HUD. */
    public String healthLabel() {
        if (absent()) return "n/a";
        String base = String.format(Locale.ROOT, "%.1f/%.0f", health, maxHealth);
        if (!hasAbsorption()) return base;
        return base + " (+" + String.format(Locale.ROOT, "%.1f", absorption) + ")";
    }

    /** The three coordinates, rounded to whole blocks. */
    public String coordsLabel() {
        return String.format(Locale.ROOT, "%.0f, %.0f, %.0f", x, y, z);
    }
}

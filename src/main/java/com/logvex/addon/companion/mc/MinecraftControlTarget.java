package com.logvex.addon.companion.mc;

import com.logvex.addon.companion.core.CompanionControlTarget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import java.util.List;
import java.util.Locale;

/**
 * Remote control of the running client through the game's own key bindings.
 *
 * <p>Nothing here synthesises a keyboard event. Movement is applied with {@code KeyBinding.setPressed}
 * on the very bindings the player's own keys drive, which means the game's normal movement code runs
 * unchanged: sprinting still needs food, sneaking still slows you, and a bound key that the player has
 * rebound still works because this class asks the binding, not the key code.
 *
 * <p>Mouse buttons are held in {@link MinecraftClient#options} the same way, so attack and use behave
 * exactly as a held mouse button does. Looking is applied by turning the player's yaw and pitch,
 * which is what the mouse ultimately does.
 *
 * <p>Remote control is off unless the client's configuration enables it. That check lives here rather
 * than in the router because it is a property of this client, and because a request that arrives
 * while the setting is off must be refused by the component that would act on it.
 */
public final class MinecraftControlTarget implements CompanionControlTarget {

    private static final List<String> INPUTS = CompanionControlTarget.inputNames();
    private final java.util.function.BooleanSupplier permitted;

    /** A target that is always permitted, for a build with no module behind it. */
    public MinecraftControlTarget() {
        this(() -> true);
    }

    /**
     * A target that asks {@code permitted} before every action.
     *
     * <p>The supplier is read on each call rather than captured once, so switching the module's
     * remote-control setting off stops the very next move instead of the next reconnect.
     */
    public MinecraftControlTarget(java.util.function.BooleanSupplier permitted) {
        this.permitted = permitted == null ? () -> false : permitted;
    }

    @Override
    public boolean enabled() {
        MinecraftClient mc = MinecraftClient.getInstance();
        // The module owns the switch; without the module there is nothing to drive.
        return mc != null && mc.player != null && mc.options != null && permitted.getAsBoolean();
    }

    @Override
    public String setInputs(List<String> held) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null || mc.player == null) {
            return "You are not in a world.";
        }
        // Validate the whole set before touching anything: a request naming one bad input must not
        // half-apply the good ones and leave the player walking.
        List<String> wanted = held == null ? List.of() : held;
        for (String name : wanted) {
            if (name == null || !INPUTS.contains(name.toLowerCase(Locale.ROOT))) {
                return "\"" + name + "\" is not a movement input. Use: " + String.join(", ", INPUTS) + ".";
            }
        }
        releaseAll();
        for (String name : wanted) {
            KeyBinding binding = bindingFor(mc, name.toLowerCase(Locale.ROOT));
            if (binding != null) binding.setPressed(true);
        }
        return null;
    }

    private static KeyBinding bindingFor(MinecraftClient mc, String name) {
        return switch (name) {
            case "forward" -> mc.options.forwardKey;
            case "back" -> mc.options.backKey;
            case "left" -> mc.options.leftKey;
            case "right" -> mc.options.rightKey;
            case "jump" -> mc.options.jumpKey;
            case "sneak" -> mc.options.sneakKey;
            case "sprint" -> mc.options.sprintKey;
            default -> null;
        };
    }

    @Override
    public String look(double deltaYaw, double deltaPitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return "You are not in a world.";
        if (!isFinite(deltaYaw) || !isFinite(deltaPitch)) {
            return "A look request needs two finite numbers.";
        }
        // A single request may not snap the view around: a browser pointer can report a huge delta
        // after a tab switch, and applying it would spin the player. Sixty degrees is a full swipe.
        double yaw = clamp(deltaYaw, -60.0, 60.0);
        double pitch = clamp(deltaPitch, -60.0, 60.0);
        float newYaw = (float) (mc.player.getYaw() + yaw);
        float newPitch = (float) clamp(mc.player.getPitch() + pitch, -90.0, 90.0);
        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);
        return null;
    }

    @Override
    public String mouse(int button, boolean hold) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return "You are not in a world.";
        KeyBinding binding = switch (button) {
            case 0 -> mc.options.attackKey;
            case 1 -> mc.options.useKey;
            // Pick block is not a held binding in the options; it is an edge-triggered action, so a
            // "hold" has no meaning and is refused rather than pretended.
            case 2 -> null;
            default -> null;
        };
        if (button == 2) {
            return "Pick block is not a held button. Use the world tab's block list instead.";
        }
        if (binding == null) return "A mouse button must be 0 (attack) or 1 (use).";
        binding.setPressed(hold);
        return null;
    }

    @Override
    public String selectHotbar(int slot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return "You are not in a world.";
        if (slot < 0 || slot > 8) return "A hotbar slot must be between 0 and 8.";
        mc.player.getInventory().selectedSlot = slot;
        return null;
    }

    @Override
    public void releaseAll() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return;
        for (String name : INPUTS) {
            KeyBinding binding = bindingFor(mc, name);
            if (binding != null) binding.setPressed(false);
        }
        mc.options.attackKey.setPressed(false);
        mc.options.useKey.setPressed(false);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

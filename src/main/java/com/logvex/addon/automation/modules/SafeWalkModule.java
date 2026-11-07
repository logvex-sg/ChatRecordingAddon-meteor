package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.SafeWalkController;
import com.logvex.addon.automation.mc.AutomationSupport;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import net.minecraft.util.math.BlockPos;

/**
 * Stops the player walking off a ledge while sneaking is not held.
 *
 * <p>It works by releasing the forward key at the edge of a drop, which is exactly what a cautious
 * player does by hand, so no server sees anything unusual. It only ever removes movement input, so
 * the worst case is that the player walks more slowly. The decision is a pure function in
 * {@link SafeWalkController}.
 */
public class SafeWalkModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> maxDrop = sgGeneral.add(new IntSetting.Builder()
        .name("max-safe-drop")
        .description("A drop of at most this many blocks is considered safe.")
        .defaultValue(1)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Boolean> sneakAtEdge = sgGeneral.add(new BoolSetting.Builder()
        .name("sneak-at-edge")
        .description("Also hold sneak while stopped at an edge, for extra safety.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> respectSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-when-sneaking")
        .description("Do not intervene while the player is already sneaking (they are in control).")
        .defaultValue(true)
        .build()
    );

    private final SafeWalkController controller = new SafeWalkController(1, false);

    private boolean suppressed;

    public SafeWalkModule() {
        super("safe-walk", "Stops you walking off ledges by releasing forward at the edge.");
    }

    @Override
    public void onActivate() {
        controller.setEnabled(true);
        controller.setMaxDrop(maxDrop.get());
        controller.setSneakAtEdge(sneakAtEdge.get());
        info("Safe Walk on (max drop %d).", maxDrop.get());
    }

    @Override
    public void onDeactivate() {
        controller.setEnabled(false);
        restore();
        info("Safe Walk off.");
    }

    @Override
    protected void onWorldLeave() {
        restore();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready() || mc.options == null) return;
        controller.setMaxDrop(maxDrop.get());
        controller.setSneakAtEdge(sneakAtEdge.get());

        if (respectSneak.get() && mc.player.isSneaking()) {
            restore();
            return;
        }

        boolean forward = mc.options.forwardKey.isPressed();
        if (!forward) {
            restore();
            return;
        }

        BlockPos below = mc.player.getBlockPos().down();
        BlockPos ahead = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
        boolean groundBelow = AutomationSupport.solidAt(below);
        boolean aheadSolid = AutomationSupport.solidAt(ahead);
        int drop = AutomationSupport.dropBelow(ahead, 8);

        boolean dangerous = controller.wouldFall(groundBelow, aheadSolid, drop,
            mc.player.isOnGround(), true);
        if (dangerous) {
            mc.options.forwardKey.setPressed(false);
            if (controller.sneakAtEdge()) mc.options.sneakKey.setPressed(true);
            suppressed = true;
        } else if (suppressed) {
            restore();
        }
    }

    /** Undoes any input change the module made. */
    private void restore() {
        if (!suppressed) return;
        suppressed = false;
        // The keys are edge-triggered by the user, so only re-enable what we disabled.
        if (mc.options != null) mc.options.sneakKey.setPressed(false);
    }

    @Override
    public String getInfoString() {
        return controller.enabled() ? "on" : "off";
    }
}

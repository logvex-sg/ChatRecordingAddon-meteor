package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.AntiAfkController;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;

/**
 * Performs small actions so the player is not kicked for idling.
 *
 * <p>Every action is a genuine client action — looking around, jumping, sneaking, swinging or a
 * brief step forward — so it cannot be distinguished from a normal player by the server. The
 * schedule lives in {@link AntiAfkController}, which is unit-tested; this class only performs the
 * action it hands out.
 */
public class AntiAfkModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgActions = settings.createGroup("Actions");

    private final Setting<Integer> minInterval = sgGeneral.add(new IntSetting.Builder()
        .name("min-interval-seconds")
        .description("Shortest wait between two actions.")
        .defaultValue(20)
        .sliderRange(5, 300)
        .build()
    );

    private final Setting<Integer> maxInterval = sgGeneral.add(new IntSetting.Builder()
        .name("max-interval-seconds")
        .description("Longest wait between two actions. A random value in this range is used.")
        .defaultValue(40)
        .sliderRange(5, 600)
        .build()
    );

    private final Setting<Boolean> look = sgActions.add(new BoolSetting.Builder()
        .name("look-around").description("Occasionally look around.").defaultValue(true).build());
    private final Setting<Boolean> jump = sgActions.add(new BoolSetting.Builder()
        .name("jump").description("Occasionally jump.").defaultValue(true).build());
    private final Setting<Boolean> sneak = sgActions.add(new BoolSetting.Builder()
        .name("sneak").description("Occasionally tap sneak.").defaultValue(false).build());
    private final Setting<Boolean> swing = sgActions.add(new BoolSetting.Builder()
        .name("swing").description("Occasionally swing the hand.").defaultValue(true).build());
    private final Setting<Boolean> walk = sgActions.add(new BoolSetting.Builder()
        .name("step-forward").description("Occasionally take a small step forward.").defaultValue(false).build());
    private final Setting<Boolean> cycleHotbar = sgActions.add(new BoolSetting.Builder()
        .name("cycle-hotbar").description("Occasionally move to the next hotbar slot.").defaultValue(false).build());

    private final AntiAfkController controller = new AntiAfkController(20_000, 40_000, null);

    /** Tick at which a held key (sneak/forward) must be released, or -1 when none is held. */
    private int releaseKeyAt = -1;

    public AntiAfkModule() {
        super("anti-afk", "Performs small actions so you are not kicked for idling.");
    }

    @Override
    public void onActivate() {
        controller.setEnabled(true);
        controller.setIntervals(minInterval.get() * 1000L, maxInterval.get() * 1000L);
        controller.setActions(selectedActions());
        controller.arm(System.currentTimeMillis());
        info("Anti AFK on. Acting every %d-%d seconds.", minInterval.get(), maxInterval.get());
    }

    @Override
    public void onDeactivate() {
        controller.setEnabled(false);
        controller.reset();
        releaseInputs();
        info("Anti AFK off.");
    }

    @Override
    protected void onWorldLeave() {
        controller.reset();
        releaseInputs();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready()) return;

        // A key pressed by an earlier action is held for a few ticks, then released, so the player
        // never keeps sneaking or walking after the action ends.
        if (releaseKeyAt >= 0 && mc.player != null && mc.player.age >= releaseKeyAt) {
            releaseInputs();
        }

        controller.setIntervals(minInterval.get() * 1000L, maxInterval.get() * 1000L);
        controller.setActions(selectedActions());

        AntiAfkController.Action action = controller.next(System.currentTimeMillis());
        if (action == null) return;
        perform(action);
    }

    private List<AntiAfkController.Action> selectedActions() {
        List<AntiAfkController.Action> out = new ArrayList<>();
        if (look.get()) out.add(AntiAfkController.Action.LOOK_AROUND);
        if (jump.get()) out.add(AntiAfkController.Action.JUMP);
        if (sneak.get()) out.add(AntiAfkController.Action.SNEAK);
        if (swing.get()) out.add(AntiAfkController.Action.SWING);
        if (walk.get()) out.add(AntiAfkController.Action.WALK_FORWARD);
        if (cycleHotbar.get()) out.add(AntiAfkController.Action.CYCLE_HOTBAR);
        return out;
    }

    private void perform(AntiAfkController.Action action) {
        if (mc.player == null || mc.options == null) return;
        switch (action) {
            case LOOK_AROUND -> {
                float yaw = mc.player.getYaw() + (float) ((Math.random() - 0.5) * 90);
                mc.player.setYaw(yaw);
            }
            case JUMP -> {
                if (mc.player.isOnGround()) mc.player.jump();
            }
            case SNEAK -> {
                mc.options.sneakKey.setPressed(true);
                releaseKeyAt = mc.player.age + 10;
            }
            case SWING -> mc.player.swingHand(Hand.MAIN_HAND);
            case WALK_FORWARD -> {
                mc.options.forwardKey.setPressed(true);
                releaseKeyAt = mc.player.age + 10;
            }
            case CYCLE_HOTBAR -> {
                int next = (mc.player.getInventory().selectedSlot + 1) % 9;
                mc.player.getInventory().selectedSlot = next;
            }
        }
    }

    /** Releases any key the module pressed, so it never leaves the player stuck moving. */
    private void releaseInputs() {
        releaseKeyAt = -1;
        if (mc.options == null) return;
        mc.options.sneakKey.setPressed(false);
        mc.options.forwardKey.setPressed(false);
    }

    @Override
    public String getInfoString() {
        return controller.enabled()
            ? controller.millisUntilNext(System.currentTimeMillis()) / 1000 + "s" : "off";
    }
}

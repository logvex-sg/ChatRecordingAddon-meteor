package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.ArmorGuardController;
import com.logvex.addon.automation.mc.AutomationSupport;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.notify.core.NotificationType;

import java.util.List;

/**
 * Warns when a piece of armour is close to breaking.
 *
 * <p>It only reads the four armour slots and warns; it never moves or replaces armour, so it cannot
 * surprise the player. A slot with no armour is reported as unavailable rather than as "0%
 * durability", and each piece only warns once per condition so the chat is not flooded.
 */
public class ArmorGuardModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> warnPercent = sgGeneral.add(new IntSetting.Builder()
        .name("warn-percent")
        .description("Warn when a piece has this percentage of durability or less.")
        .defaultValue(25)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Integer> criticalPercent = sgGeneral.add(new IntSetting.Builder()
        .name("critical-percent")
        .description("Report a piece as critical at or below this percentage.")
        .defaultValue(10)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> chatWarning = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-warning")
        .description("Print a chat warning, in addition to the notification.")
        .defaultValue(false)
        .build()
    );

    private final ArmorGuardController controller = new ArmorGuardController(25, 10);

    /** Per-slot condition from the previous check, so a warning is only raised on a change. */
    private final ArmorGuardController.PieceState[] lastStates = new ArmorGuardController.PieceState[4];

    public ArmorGuardModule() {
        super("armor-guard", "Warns before a piece of armour breaks.");
    }

    @Override
    public void onActivate() {
        controller.setEnabled(true);
        controller.setThresholds(warnPercent.get(), criticalPercent.get());
        java.util.Arrays.fill(lastStates, null);
        info("Armor Guard on (warn %d%%, critical %d%%).", warnPercent.get(), criticalPercent.get());
    }

    @Override
    public void onDeactivate() {
        controller.setEnabled(false);
        info("Armor Guard off.");
    }

    @Override
    protected void onWorldLeave() {
        java.util.Arrays.fill(lastStates, null);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready()) return;
        controller.setThresholds(warnPercent.get(), criticalPercent.get());

        List<ArmorGuardController.Piece> pieces = AutomationSupport.armorPieces();
        for (int i = 0; i < pieces.size() && i < lastStates.length; i++) {
            ArmorGuardController.Piece piece = pieces.get(i);
            ArmorGuardController.PieceState state = controller.classify(piece.durability(), piece.maxDurability());
            if (state == lastStates[i]) continue;
            lastStates[i] = state;
            if (state == ArmorGuardController.PieceState.WARNING
                || state == ArmorGuardController.PieceState.CRITICAL) {
                report(piece, state);
            }
        }
    }

    private void report(ArmorGuardController.Piece piece, ArmorGuardController.PieceState state) {
        int percent = (int) Math.round(piece.fraction() * 100);
        String label = state == ArmorGuardController.PieceState.CRITICAL ? "critical" : "low";
        String message = piece.slotName() + " durability is " + label + " (" + percent + "%).";
        if (chatWarning.get()) warning("%s", message);
        notifyUser(NotificationType.EQUIPMENT_WARNING, "Armor Guard", message);
    }

    @Override
    public String getInfoString() {
        return controller.enabled() ? "on" : "off";
    }
}

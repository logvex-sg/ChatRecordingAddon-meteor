package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.AutoToolController;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Swaps to the best tool for the block the player is looking at.
 *
 * <p>It only changes the selected hotbar slot; it never moves items between slots, so it is safe in
 * Survival and on servers that reject inventory reordering. The choice of slot lives in
 * {@link AutoToolController}, which is unit-tested; this class only scores the tools the player
 * actually holds.
 */
public class AutoToolModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Return to the previous slot once the block is broken.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minDurability = sgGeneral.add(new com.logvex.addon.framework.settings.IntSetting.Builder()
        .name("min-durability")
        .description("Never swap to a tool with this much durability or less left.")
        .defaultValue(10)
        .min(0)
        .max(1000)
        .build()
    );

    private final Setting<Boolean> onlyWhenBreaking = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-breaking")
        .description("Only swap while a block is actually being mined, not just looked at.")
        .defaultValue(true)
        .build()
    );

    private final AutoToolController controller = new AutoToolController();

    private boolean swapped;
    private int slotBeforeSwap = -1;

    public AutoToolModule() {
        super("auto-tool", "Swaps to the best tool for the block you are mining.");
    }

    @Override
    public void onActivate() {
        controller.setEnabled(true);
        controller.setSwapBack(swapBack.get());
        info("Auto Tool on.");
    }

    @Override
    public void onDeactivate() {
        controller.setEnabled(false);
        restorePreviousSlot();
        info("Auto Tool off.");
    }

    @Override
    protected void onWorldLeave() {
        swapped = false;
        slotBeforeSwap = -1;
        controller.reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready()) return;
        controller.setSwapBack(swapBack.get());

        BlockState target = targetedBlock();
        if (target == null || target.isAir()) {
            restorePreviousSlot();
            return;
        }
        if (onlyWhenBreaking.get() && (mc.interactionManager == null
            || !mc.interactionManager.isBreakingBlock())) {
            return;
        }

        int current = mc.player.getInventory().selectedSlot;
        List<AutoToolController.Candidate> candidates = scoreTools(target);
        int chosen = controller.chooseSlot(current, candidates, 0);
        if (chosen != current) {
            controller.rememberSwap(current);
            slotBeforeSwap = current;
            mc.player.getInventory().selectedSlot = chosen;
            swapped = true;
        }
    }

    private void restorePreviousSlot() {
        if (!swapped || !swapBack.get()) return;
        if (slotBeforeSwap >= 0 && slotBeforeSwap <= 8 && mc.player != null) {
            mc.player.getInventory().selectedSlot = slotBeforeSwap;
        }
        swapped = false;
        slotBeforeSwap = -1;
    }

    private BlockState targetedBlock() {
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return null;
        if (mc.world == null) return null;
        return mc.world.getBlockState(((BlockHitResult) mc.crosshairTarget).getBlockPos());
    }

    /** Scores each hotbar tool for the target block: suitability first, then speed. */
    private List<AutoToolController.Candidate> scoreTools(BlockState target) {
        List<AutoToolController.Candidate> out = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isDamageable()) continue;
            if (stack.getMaxDamage() - stack.getDamage() <= minDurability.get()) continue;

            double score = 0;
            if (stack.isSuitableFor(target)) score += 1000;
            score += stack.getMiningSpeedMultiplier(target);
            out.add(new AutoToolController.Candidate(i,
                Registries.ITEM.getId(stack.getItem()).toString(), score));
        }
        return out;
    }

    @Override
    public String getInfoString() {
        return controller.enabled() ? "on" : "off";
    }
}

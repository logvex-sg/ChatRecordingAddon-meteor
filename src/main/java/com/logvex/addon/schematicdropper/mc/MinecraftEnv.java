package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.EnvPort;
import com.logvex.addon.schematicdropper.core.Rotation;
import com.logvex.addon.schematicdropper.core.RotationMath;
import com.logvex.addon.schematicdropper.core.StackEntry;
import com.logvex.addon.schematicdropper.core.TargetMode;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.logvex.addon.framework.MeteorClient.mc;

/**
 * Reads the live client inventory and player state. Never mutates anything.
 */
public final class MinecraftEnv implements EnvPort {
    private final Supplier<TargetMode> targetModeSupplier;
    private final float pitchOffset;

    public MinecraftEnv(Supplier<TargetMode> targetModeSupplier, float pitchOffset) {
        this.targetModeSupplier = targetModeSupplier;
        this.pitchOffset = pitchOffset;
    }

    @Override
    public boolean canRun() {
        return mc.player != null && mc.world != null && mc.interactionManager != null;
    }

    @Override
    public boolean canInteract() {
        return mc.player != null && mc.player.currentScreenHandler != null;
    }

    @Override
    public int countOf(String itemId) {
        if (mc.player == null) return 0;
        Item item = item(itemId);
        if (item == null) return 0;

        int total = 0;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    @Override
    public List<StackEntry> stacksOf(String itemId) {
        List<StackEntry> out = new ArrayList<>();
        if (mc.player == null) return out;
        Item item = item(itemId);
        if (item == null) return out;

        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) out.add(new StackEntry(i, stack.getCount()));
        }
        return out;
    }

    @Override
    public Map<String, Integer> inventorySnapshot() {
        Map<String, Integer> out = new HashMap<>();
        if (mc.player == null) return out;

        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(stack.getItem());
            out.merge(id.toString(), stack.getCount(), Integer::sum);
        }
        return out;
    }

    @Override
    public Rotation currentRotation() {
        if (mc.player == null) return new Rotation(0f, 0f);
        return new Rotation(mc.player.getYaw(), mc.player.getPitch());
    }

    @Override
    public Rotation desiredRotation() {
        if (mc.player == null) return null;

        TargetMode mode = targetModeSupplier == null ? TargetMode.PLAYER_POSITION : targetModeSupplier.get();
        if (mode == TargetMode.LOOKED_AT_ENTITY) {
            Entity target = lookedAtEntity();
            if (target != null) return rotationToEntity(target);
        }
        return rotationToFeet();
    }

    /** Aim at the block just in front of the player's feet, at a slight downward pitch. */
    private Rotation rotationToFeet() {
        ClientPlayerEntity player = mc.player;
        double yawRad = Math.toRadians(player.getYaw());
        Vec3d dir = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));

        double tx = player.getX() + dir.x;
        double ty = player.getY() + pitchOffset;
        double tz = player.getZ() + dir.z;

        return RotationMath.rotationTo(
            player.getX(), player.getEyeY(), player.getZ(),
            tx, ty, tz
        );
    }

    private Rotation rotationToEntity(Entity target) {
        ClientPlayerEntity player = mc.player;
        return RotationMath.rotationTo(
            player.getX(), player.getEyeY(), player.getZ(),
            target.getX(), target.getY() + target.getHeight() * 0.5, target.getZ()
        );
    }

    private Entity lookedAtEntity() {
        if (mc.crosshairTarget instanceof EntityHitResult hit) {
            Entity e = hit.getEntity();
            if (isValidTarget(e)) return e;
        }
        return null;
    }

    private boolean isValidTarget(Entity e) {
        if (e == null || e == mc.player) return false;
        if (!e.isAlive() || e.isRemoved()) return false;
        double dx = e.getX() - mc.player.getX();
        double dy = e.getY() - mc.player.getY();
        double dz = e.getZ() - mc.player.getZ();
        return RotationMath.isFinite(dx) && RotationMath.isFinite(dy) && RotationMath.isFinite(dz)
            && (dx * dx + dy * dy + dz * dz) > 1.0e-4;
    }

    private Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }
}

package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.BlockStateSpec;
import com.logvex.addon.schematicdropper.core.PendingPlacement;
import com.logvex.addon.schematicdropper.core.PlacementAction;
import com.logvex.addon.schematicdropper.core.PlacementFailure;
import com.logvex.addon.schematicdropper.core.PlacementRequest;
import com.logvex.addon.framework.utils.player.FindItemResult;
import com.logvex.addon.framework.utils.player.InvUtils;
import com.logvex.addon.framework.utils.world.BlockUtils;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import static com.logvex.addon.framework.MeteorClient.mc;

/**
 * Performs real block placements through Meteor's {@link BlockUtils#place}, then confirms them from
 * the world.
 *
 * <p>Nothing here trusts the click: {@code place} only sends the vanilla interaction (select the
 * item, aim at a face, use it). {@link Pending#poll()} reports CONFIRMED only once the world at the
 * target position matches the requested state, and reports REJECTED with a specific reason when it
 * does not, so a client-only prediction can never be mistaken for a real placement.
 */
public final class MinecraftPlacementAction implements PlacementAction {
    /** How long a placement may stay unconfirmed before it is treated as a timeout. */
    private static final long TIMEOUT_MS = 1500;

    private final MinecraftPlacementEnv env;
    private Pending current;

    public MinecraftPlacementAction(MinecraftPlacementEnv env) {
        this.env = env;
    }

    @Override
    public PendingPlacement begin(PlacementRequest request) {
        if (request == null || !request.valid()) {
            return new Immediate(PendingPlacement.Status.REJECTED, PlacementFailure.INVALID_PLACEMENT);
        }
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return new Immediate(PendingPlacement.Status.REJECTED, PlacementFailure.CHUNK_UNLOADED);
        }

        BlockPos pos = MinecraftBlockTraits.toBlockPos(request.worldPos());
        if (!mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return new Immediate(PendingPlacement.Status.REJECTED, PlacementFailure.CHUNK_UNLOADED);
        }

        Item item = item(request.itemId());
        if (item == null) {
            return new Immediate(PendingPlacement.Status.REJECTED, PlacementFailure.NO_MATERIAL);
        }

        FindItemResult found = InvUtils.find(item);
        if (!found.found()) {
            return new Immediate(PendingPlacement.Status.REJECTED, PlacementFailure.NO_MATERIAL);
        }

        Pending pending = new Pending(request, pos);
        current = pending;

        // Meteor rotates, swaps, clicks the neighbour and swaps back. A false result means it could
        // not even find a face to click, which is a support problem rather than a rejection.
        boolean started = BlockUtils.place(pos, found, true, 50, false, true, true);
        if (!started) {
            current = null;
            return new Immediate(PendingPlacement.Status.REJECTED, PlacementFailure.NO_SUPPORT);
        }
        return pending;
    }

    @Override
    public void cancelPending() {
        current = null;
    }

    private static Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }

    private record Immediate(PendingPlacement.Status status, PlacementFailure failure) implements PendingPlacement {
        @Override
        public Status poll() {
            return status;
        }
    }

    /** Waits for the world to report the requested state, then classifies whatever it finds. */
    private final class Pending implements PendingPlacement {
        private final PlacementRequest request;
        private final BlockPos pos;
        private final long startedAt = System.currentTimeMillis();

        Pending(PlacementRequest request, BlockPos pos) {
            this.request = request;
            this.pos = pos;
        }

        @Override
        public Status poll() {
            if (mc.world == null) return Status.REJECTED;

            BlockStateSpec actual = MinecraftBlockTraits.toSpec(mc.world.getBlockState(pos));
            if (request.target().matches(actual)) {
                current = null;
                return Status.CONFIRMED;
            }
            if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
                current = null;
                return Status.TIMEOUT;
            }
            return Status.PENDING;
        }

        @Override
        public PlacementFailure failure() {
            BlockStateSpec actual = MinecraftBlockTraits.toSpec(mc.world.getBlockState(pos));
            // Something unrelated occupies the cell: that is a conflict, not a retryable failure.
            if (!actual.isAir() && !actual.sameBlock(request.target())) return PlacementFailure.WORLD_CHANGED;
            return PlacementFailure.SERVER_REJECTED;
        }
    }
}

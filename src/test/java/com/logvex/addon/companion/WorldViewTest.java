package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionJson;
import com.logvex.addon.companion.core.CompanionSnapshot;
import com.logvex.addon.companion.core.CompanionSnapshot.BlockView;
import com.logvex.addon.companion.core.CompanionSnapshot.EntityView;
import com.logvex.addon.companion.core.CompanionSnapshot.WorldView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The World tab's payload: it must be readable by the app and cheap to send.
 *
 * <p>The values come from the game, which cannot run here, so these tests pin the contract the app
 * depends on — the shape of the JSON, the emptiness of the "not in a world" case, and the fact that
 * the view carries only what it was given rather than inventing data.
 */
class WorldViewTest {

    @Test
    @DisplayName("a client outside a world reports an empty view, not zeros that look real")
    void emptyView() {
        WorldView view = WorldView.empty();

        assertFalse(view.inWorld(), "an empty view is not in a world");
        assertEquals("", view.dimension(), "an unknown dimension is blank, not 'overworld'");
        assertTrue(view.blocks().isEmpty());
        assertTrue(view.entities().isEmpty());
    }

    @Test
    @DisplayName("the view serialises to the field names the app reads")
    void serialisesForTheApp() {
        WorldView view = new WorldView(true, "minecraft:overworld", 12.5, 64.0, -30.25, 90f, 0f,
            12, 64, -31, "minecraft:stone", 15, 18, 6000L, false, false,
            List.of(new BlockView(12, 65, -31, "minecraft:oak_log", 1.0)),
            List.of(new EntityView("minecraft:zombie", "Zombie", "hostile", 8.3, 20)));

        String json = CompanionJson.write(view);

        assertTrue(json.contains("\"inWorld\":true"), json);
        assertTrue(json.contains("\"dimension\":\"minecraft:overworld\""), json);
        assertTrue(json.contains("\"blockBelow\":\"minecraft:stone\""), json);
        assertTrue(json.contains("\"id\":\"minecraft:oak_log\""), json);
        assertTrue(json.contains("\"kind\":\"hostile\""), json);
        // The app sorts and colours by these, so their absence would silently flatten the map.
        assertTrue(json.contains("\"distance\":1.0"), json);
        assertTrue(json.contains("\"health\":20"), json);
    }

    @Test
    @DisplayName("the snapshot carries the world view, so one poll feeds every tab")
    void snapshotCarriesTheView() {
        CompanionSnapshot snapshot = CompanionSnapshot.empty("n1", "Node", "1.0.0");

        assertEquals(WorldView.empty(), snapshot.world(), "an empty snapshot has an empty world view");
        assertTrue(CompanionJson.write(snapshot).contains("\"world\":"), "the field is always present");
    }

    @Test
    @DisplayName("a view round-trips through JSON without losing the block list")
    void roundTripsThroughJson() {
        WorldView view = new WorldView(true, "minecraft:the_nether", 0, 32, 0, 0f, 0f,
            0, 32, 0, "minecraft:netherrack", 7, 20, 1000L, false, false,
            List.of(new BlockView(0, 33, 0, "minecraft:lava", 1.0)), List.of());

        WorldView parsed = new com.google.gson.Gson().fromJson(CompanionJson.write(view), WorldView.class);

        assertEquals(view, parsed, "the app and the client must agree on every field");
    }
}

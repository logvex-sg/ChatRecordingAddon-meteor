package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionJson;
import com.logvex.addon.companion.core.CompanionSnapshot;
import com.logvex.addon.companion.core.CompanionSnapshot.InventoryView;
import com.logvex.addon.companion.core.CompanionSnapshot.ItemView;
import com.logvex.addon.companion.core.CompanionSnapshot.MetricView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot fields the metrics and inventory panels read.
 *
 * <p>They are pinned by name because the app reads them as plain properties: renaming a component
 * would not fail to compile anywhere, it would just leave a panel blank.
 */
class SnapshotMetricsInventoryTest {

    @Test
    @DisplayName("an unreadable inventory says so instead of pretending to have empty slots")
    void emptyInventoryIsNotAFullGrid() {
        InventoryView inventory = InventoryView.unavailable();

        assertFalse(inventory.available(), "an unreadable inventory reports itself unavailable");
        assertTrue(inventory.slots().isEmpty(), "and carries no slots to misread as empty");
        assertTrue(inventory.selectedSlot() < 0, "and no selected slot");
    }

    @Test
    @DisplayName("the metrics and inventory fields reach the wire under the names the app reads")
    void fieldsSerialise() {
        CompanionSnapshot base = CompanionSnapshot.empty("n1", "Node", "1.0.0");
        CompanionSnapshot snapshot = new CompanionSnapshot(
            base.node(), List.of(), List.of(), List.of(), List.of(),
            base.stats(), base.storage(), base.bot(), base.world(), List.of(),
            List.of(new MetricView("fps", List.of(1000L, 2000L), List.of(60.0, 61.0), 61.0, 60.0, 61.0, 60.5)),
            new InventoryView(true, 3, List.of(
                new ItemView(0, "minecraft:diamond", "Diamond", 12, 64, 0, false))));

        String json = CompanionJson.write(snapshot);

        assertTrue(json.contains("\"metrics\":"), json);
        assertTrue(json.contains("\"name\":\"fps\""), json);
        assertTrue(json.contains("\"values\":[60.0,61.0]"), json);
        assertTrue(json.contains("\"latest\":61.0"), json);
        assertTrue(json.contains("\"inventory\":"), json);
        assertTrue(json.contains("\"available\":true"), json);
        assertTrue(json.contains("\"itemId\":\"minecraft:diamond\""), json);
        assertTrue(json.contains("\"count\":12"), json);
    }
}

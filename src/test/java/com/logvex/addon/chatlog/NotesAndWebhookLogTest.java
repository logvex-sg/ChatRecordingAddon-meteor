package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.PlayerNote;
import com.logvex.addon.chatlog.core.WebhookDeliveryLog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesAndWebhookLogTest {

    // ------------------------------------------------------------------ PlayerNote.Store

    @Test
    void storeAddsAndFindsNotesCaseInsensitively() {
        PlayerNote.Store store = new PlayerNote.Store();
        assertNotNull(store.put("Notch", "friendly", 100));
        assertNotNull(store.get("notch"));
        assertEquals("friendly", store.get("NOTCH").text());
        assertEquals(1, store.size());
    }

    @Test
    void storeUpdatesAnExistingNoteRatherThanDuplicating() {
        PlayerNote.Store store = new PlayerNote.Store();
        store.put("Notch", "first", 100);
        store.put("notch", "second", 200);
        assertEquals(1, store.size());
        assertEquals("second", store.get("Notch").text());
        assertEquals(200, store.get("Notch").updatedAt());
    }

    @Test
    void storeRejectsBlankPlayerNames() {
        PlayerNote.Store store = new PlayerNote.Store();
        assertNull(store.put("", "text", 1));
        assertNull(store.put(null, "text", 1));
        assertEquals(0, store.size());
    }

    @Test
    void storeRemovesNotes() {
        PlayerNote.Store store = new PlayerNote.Store();
        store.put("Notch", "x", 1);
        assertTrue(store.remove("notch"));
        assertFalse(store.remove("notch"));
        assertEquals(0, store.size());
    }

    @Test
    void storeSearchMatchesPlayerAndBodyAndSortsNewestFirst() {
        PlayerNote.Store store = new PlayerNote.Store();
        store.put("Notch", "old note", 100);
        store.put("Alex", "new note", 300);
        store.put("Herobrine", "unrelated", 200);

        List<PlayerNote> all = store.search("");
        assertEquals(3, all.size());
        assertEquals("Alex", all.get(0).player(), "newest note first");

        assertEquals(1, store.search("alex").size());
        assertEquals(1, store.search("old").size());
        assertEquals(2, store.search("note").size());
    }

    @Test
    void storeIsBoundedAndDropsOldestInsertion() {
        PlayerNote.Store store = new PlayerNote.Store(3);
        for (int i = 0; i < 6; i++) store.put("p" + i, "n", i);
        assertEquals(3, store.size());
        assertNull(store.get("p0"));
        assertNotNull(store.get("p5"));
    }

    // ------------------------------------------------------------------ WebhookDeliveryLog

    @Test
    void deliveryLogIsBoundedAndNewestFirst() {
        WebhookDeliveryLog log = new WebhookDeliveryLog(3);
        for (int i = 0; i < 5; i++) log.add(i, "discord.com", true, 1, "ok");
        assertEquals(3, log.size());
        assertEquals(4, log.records().get(0).timestamp());
    }

    @Test
    void deliveryLogCapacityCanChangeAtRuntime() {
        WebhookDeliveryLog log = new WebhookDeliveryLog(10);
        for (int i = 0; i < 8; i++) log.add(i, "h", true, 1, "ok");
        assertEquals(8, log.size());
        log.setCapacity(3);
        assertEquals(3, log.size());
        assertEquals(3, log.capacity());
    }

    @Test
    void hostOfStripsTheSecretPathSegment() {
        assertEquals("discord.com", WebhookDeliveryLog.hostOf(
            "https://discord.com/api/webhooks/1234/SUPER-SECRET-TOKEN"));
        assertEquals("example.org", WebhookDeliveryLog.hostOf("http://example.org/hook/abc"));
        assertEquals("", WebhookDeliveryLog.hostOf(null));
        assertEquals("", WebhookDeliveryLog.hostOf("  "));
    }

    @Test
    void hostOfNeverReturnsTheCredentialForAUrlWithoutScheme() {
        String host = WebhookDeliveryLog.hostOf("discord.com/api/webhooks/1234/token");
        assertEquals("discord.com", host);
        assertFalse(host.contains("token"));
    }

    @Test
    void deliveryLogRecordsCarryNoUrl() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        log.add(1, "discord.com", false, 2, "HTTP 500");
        String rendered = log.records().toString();
        assertFalse(rendered.contains("http://"), rendered);
        assertFalse(rendered.contains("https://"), rendered);
    }

    @Test
    void deliveryLogClearAndRecentLimit() {
        WebhookDeliveryLog log = new WebhookDeliveryLog(50);
        for (int i = 0; i < 10; i++) log.add(i, "h", true, 1, "ok");
        assertEquals(4, log.recent(4).size());
        log.clear();
        assertEquals(0, log.size());
    }
}

package com.logvex.addon.automation;

import com.logvex.addon.automation.core.LoginCredentialStore;
import com.logvex.addon.common.SecretRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The credential store, the redactor, and the ways a password could leak.
 *
 * <p>The store's job is to keep the value out of anything that gets shared. These tests check the
 * round trip, the file permissions, and that the masking helpers never hand back the value.
 */
class LoginCredentialStoreTest {

    @TempDir
    Path dir;

    @AfterEach
    void clearRedactor() {
        SecretRedactor.clear();
    }

    private LoginCredentialStore store() {
        return new LoginCredentialStore(dir.resolve("autologin.properties"));
    }

    @Test
    @DisplayName("a stored password survives a save and reload")
    void roundTrip() {
        LoginCredentialStore first = store();
        first.put("play.example.com", "s3cret-pass");
        first.save();

        LoginCredentialStore second = store();
        second.load();
        assertEquals("s3cret-pass", second.get("play.example.com"));
        assertTrue(second.has("play.example.com"));
    }

    @Test
    @DisplayName("server keys ignore case and the default port")
    void keysNormalise() {
        LoginCredentialStore store = store();
        store.put("Play.Example.com:25565", "pw123");

        assertEquals("pw123", store.get("play.example.com"));
        assertEquals("pw123", store.get("PLAY.EXAMPLE.COM:25565"));
        assertNull(store.get("other.example.com"));
    }

    @Test
    @DisplayName("removing a password leaves the others alone")
    void removeAndClear() {
        LoginCredentialStore store = store();
        store.put("a.example.com", "aaa111");
        store.put("b.example.com", "bbb222");

        assertTrue(store.remove("a.example.com"));
        assertFalse(store.remove("a.example.com"));
        assertEquals(1, store.size());
        assertEquals("bbb222", store.get("b.example.com"));

        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("values containing the separator survive a round trip")
    void escapesSeparators() {
        LoginCredentialStore store = store();
        store.put("play.example.com", "pa=ss\\word");
        store.save();

        LoginCredentialStore reloaded = store();
        reloaded.load();
        assertEquals("pa=ss\\word", reloaded.get("play.example.com"));
    }

    @Test
    @DisplayName("the file is readable only by its owner")
    void fileIsOwnerOnly() throws Exception {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"),
            "POSIX permissions are not available here");
        LoginCredentialStore store = store();
        store.put("play.example.com", "s3cret-pass");
        store.save();

        assertEquals(PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(store.file()));
    }

    @Test
    @DisplayName("the masked form never contains the password")
    void maskingNeverLeaks() {
        String masked = LoginCredentialStore.masked("hunter2");
        assertFalse(masked.contains("hunter2"));
        assertTrue(masked.contains("7"), "reports the length so the user can confirm it is set");
        assertEquals("not set", LoginCredentialStore.masked(null));
        assertEquals("not set", LoginCredentialStore.masked(""));
    }

    @Test
    @DisplayName("a stored password is redacted from recorded text")
    void storedPasswordIsRedacted() {
        LoginCredentialStore store = store();
        store.put("play.example.com", "s3cret-pass");

        assertEquals("you said [redacted] out loud",
            SecretRedactor.redact("you said s3cret-pass out loud"));
        assertTrue(SecretRedactor.containsSecret("oops s3cret-pass"));
    }

    @Test
    @DisplayName("redaction happens even before the store is saved")
    void putRegistersImmediately() {
        store().put("play.example.com", "instant-secret");
        assertEquals("[redacted]", SecretRedactor.redact("instant-secret"));
    }

    @Test
    @DisplayName("the longer secret wins when one contains another")
    void longestSecretFirst() {
        SecretRedactor.register("abc");
        SecretRedactor.register("abcdef");
        // Replacing "abc" first would leave "def" behind, half of the real secret.
        assertEquals("[redacted]", SecretRedactor.redact("abcdef"));
    }

    @Test
    @DisplayName("very short values are not registered, so chat is not mangled")
    void shortValuesIgnored() {
        SecretRedactor.register("ab");
        assertEquals("ab is a common word", SecretRedactor.redact("ab is a common word"));
        assertEquals(0, SecretRedactor.size());
    }

    @Test
    @DisplayName("redaction is a no-op when nothing is registered")
    void noSecretsIsNoOp() {
        assertEquals("nothing to hide", SecretRedactor.redact("nothing to hide"));
        assertEquals("", SecretRedactor.redact(""));
        assertNull(SecretRedactor.redact(null));
    }
}

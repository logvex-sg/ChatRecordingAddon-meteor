package com.logvex.addon.automation;

import com.logvex.addon.automation.core.LoginCredentialStore;
import com.logvex.addon.common.SecretRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A save must never be able to destroy the passwords already on disk.
 *
 * <p>The store wrote straight over the live file. A crash, a full disk or a killed game between the
 * truncate and the last byte left an empty or half-written file, and every stored password was gone
 * with no way to recover it. Every other store in the addon already wrote a temporary file and
 * renamed it into place.
 *
 * <p>The property is observed through the inode. Rewriting a file in place keeps its inode;
 * renaming another file over it changes the inode. So an unchanged inode across a save is proof
 * that the bytes went into the file the user depends on.
 */
class LoginCredentialStoreAtomicityTest {

    @TempDir
    Path dir;

    @AfterEach
    void clearRedactor() {
        SecretRedactor.clear();
    }

    private LoginCredentialStore store() {
        return new LoginCredentialStore(dir.resolve("autologin.properties"));
    }

    /** The file's identity, or -1 when the platform cannot report it. */
    private static long inode(Path file) throws Exception {
        Object value = Files.getAttribute(file, "unix:ino");
        return value instanceof Number n ? n.longValue() : -1L;
    }

    @Test
    @DisplayName("a save replaces the file instead of rewriting it in place")
    void saveReplacesTheFile() throws Exception {
        LoginCredentialStore store = store();
        store.put("play.example.com", "s3cret-pass");
        store.save();

        long before = inode(store.file());
        assumeTrue(before >= 0, "this filesystem does not report an inode");

        store.put("other.example.com", "another-pass");
        store.save();

        assertNotEquals(before, inode(store.file()),
            "the live file kept its inode, so it was truncated and rewritten in place: a crash"
                + " mid-write would leave it empty and lose every password");
    }

    @Test
    @DisplayName("a save leaves no temporary file behind")
    void saveLeavesNoTempFile() throws Exception {
        LoginCredentialStore store = store();
        store.put("play.example.com", "s3cret-pass");
        store.save();

        try (var files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("autologin.properties"), names,
                "the staged file must be renamed into place, not left beside the real one");
        }
    }

    @Test
    @DisplayName("the saved file is complete and holds each password once")
    void savedFileIsComplete() throws Exception {
        LoginCredentialStore store = store();
        store.put("a.example.com", "aaa111");
        store.put("b.example.com", "bbb222");
        store.save();

        String text = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(text.contains("a.example.com=aaa111"), "first password must be written");
        assertTrue(text.contains("b.example.com=bbb222"), "second password must be written");
        assertEquals(1, text.lines().filter(l -> l.startsWith("a.example.com=")).count(),
            "a staged-then-renamed file must not duplicate a line");
    }

    @Test
    @DisplayName("a save over an existing store keeps the entries it did not change")
    void secondSaveKeepsExistingEntries() {
        LoginCredentialStore store = store();
        store.put("a.example.com", "aaa111");
        store.save();

        store.put("b.example.com", "bbb222");
        store.save();

        LoginCredentialStore reloaded = store();
        reloaded.load();
        assertEquals("aaa111", reloaded.get("a.example.com"));
        assertEquals("bbb222", reloaded.get("b.example.com"));
    }

    @Test
    @DisplayName("the saved file is owner-only, so a password is never world readable")
    void savedFileIsOwnerOnly() throws Exception {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"),
            "POSIX permissions are not available here");

        LoginCredentialStore store = store();
        store.put("play.example.com", "s3cret-pass");
        store.save();

        assertEquals(PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(store.file()));
    }

    @Test
    @DisplayName("a save that cannot be completed reports the failure")
    void failedSaveIsReported() throws Exception {
        // The parent is a regular file, so staging a sibling file cannot succeed.
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory");

        LoginCredentialStore store = new LoginCredentialStore(blocked.resolve("autologin.properties"));
        store.put("play.example.com", "s3cret-pass");

        RuntimeException failure = assertThrows(RuntimeException.class, store::save,
            "a save that could not be completed must not look like it worked");
        assertTrue(failure.getMessage() != null && failure.getMessage().contains("autologin"),
            "the failure must name the file so the user can find it, got: " + failure.getMessage());
    }

    @Test
    @DisplayName("a password containing the separator or a backslash round trips through a rename")
    void escapesSurviveStaging() {
        LoginCredentialStore store = store();
        store.put("play.example.com", "pa=ss\\word");
        store.save();

        LoginCredentialStore reloaded = store();
        reloaded.load();
        assertEquals("pa=ss\\word", reloaded.get("play.example.com"));
    }

    @Test
    @DisplayName("the file is a properties file, one server per line")
    void fileFormatIsLinePerServer() throws Exception {
        LoginCredentialStore store = store();
        store.put("a.example.com", "aaa111");
        store.save();

        List<String> content = Files.readAllLines(store.file(), StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank() && !l.startsWith("#"))
            .toList();
        assertEquals(List.of("a.example.com=aaa111"), content);
    }
}

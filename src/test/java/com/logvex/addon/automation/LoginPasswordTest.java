package com.logvex.addon.automation;

import com.logvex.addon.automation.core.LoginPassword;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The password rules that stand between a typed value and {@code /login <password>}.
 *
 * <p>Each rejected case is a real way the login fails silently: a trailing newline from a password
 * manager, a pasted line with a space, or a whole file dropped into the field.
 */
class LoginPasswordTest {

    @Test
    @DisplayName("an ordinary password is usable")
    void ordinaryPasswordIsUsable() {
        assertEquals(LoginPassword.Problem.OK, LoginPassword.check("hunter2"));
        assertTrue(LoginPassword.isUsable("hunter2"));
    }

    @Test
    @DisplayName("punctuation, mixed case and unicode are all allowed")
    void unusualButValidCharactersAreAllowed() {
        for (String value : new String[]{"p@ss!word", "Correct-Horse_battery", "Ünïcödé99", "a.b,c/d?e=f"}) {
            assertEquals(LoginPassword.Problem.OK, LoginPassword.check(value), value);
        }
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed rather than rejected")
    void surroundingWhitespaceIsTrimmed() {
        assertEquals("hunter2", LoginPassword.tidy("  hunter2\n"));
        assertEquals(LoginPassword.Problem.OK, LoginPassword.check("  hunter2  "));
    }

    @Test
    @DisplayName("a trailing newline pasted from a password manager is removed by tidy")
    void trailingNewlineIsTrimmed() {
        // The failure mode this covers: the user copies "hunter2\n", the newline is invisible, and
        // the server reports a wrong password.
        assertEquals("hunter2", LoginPassword.tidy("hunter2\n"));
        assertTrue(LoginPassword.isUsable("hunter2\n"));
    }

    @Test
    @DisplayName("a line break in the middle is refused, because it would start a second command")
    void embeddedNewlineIsRefused() {
        assertEquals(LoginPassword.Problem.CONTROL_CHARACTER, LoginPassword.check("hunter2\n/op me"));
    }

    @Test
    @DisplayName("a space is refused, because it would split the login command")
    void spaceIsRefused() {
        assertEquals(LoginPassword.Problem.CONTAINS_SPACE, LoginPassword.check("my password"));
    }

    @Test
    @DisplayName("an empty or blank value is refused")
    void emptyIsRefused() {
        assertEquals(LoginPassword.Problem.EMPTY, LoginPassword.check(null));
        assertEquals(LoginPassword.Problem.EMPTY, LoginPassword.check(""));
        assertEquals(LoginPassword.Problem.EMPTY, LoginPassword.check("   "));
    }

    @Test
    @DisplayName("a value longer than the cap is refused")
    void overlongIsRefused() {
        assertEquals(LoginPassword.Problem.TOO_LONG,
            LoginPassword.check("a".repeat(LoginPassword.MAX_LENGTH + 1)));
        assertEquals(LoginPassword.Problem.OK,
            LoginPassword.check("a".repeat(LoginPassword.MAX_LENGTH)));
    }

    @Test
    @DisplayName("a tab is treated as a separator and refused")
    void tabIsRefused() {
        assertEquals(LoginPassword.Problem.CONTAINS_SPACE, LoginPassword.check("my\tpassword"));
    }

    @Test
    @DisplayName("every problem carries a message the user can act on")
    void everyProblemHasAMessage() {
        for (LoginPassword.Problem problem : LoginPassword.Problem.values()) {
            if (problem == LoginPassword.Problem.OK) continue;
            assertFalse(problem.message().isBlank(), problem.name() + " needs a message");
        }
        assertTrue(LoginPassword.Problem.OK.message().isEmpty());
    }
}

package com.logvex.addon.automation;

import com.logvex.addon.automation.core.LoginAttemptPolicy;
import com.logvex.addon.automation.core.LoginPromptDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auto-login safety contract.
 *
 * <p>These tests exist because the failure mode is not a crash: it is the password being sent when
 * it should not have been, or sent forever when the server said no. Both are silent until someone
 * reads the chat or the account is banned, so the rules are pinned here.
 */
class LoginPromptDetectorTest {

    @Nested
    @DisplayName("recognises real prompts")
    class Prompts {
        @Test
        void commonLoginPromptForms() {
            assertTrue(LoginPromptDetector.isLoginPrompt("Please login with /login <password>"));
            assertTrue(LoginPromptDetector.isLoginPrompt("Please log in to continue"));
            assertTrue(LoginPromptDetector.isLoginPrompt("Use /login <password> to play"));
            assertTrue(LoginPromptDetector.isLoginPrompt("Type /login to authenticate"));
            assertTrue(LoginPromptDetector.isLoginPrompt("Enter your password:"));
        }

        @Test
        void aRegistrationPromptIsNotALoginPrompt() {
            // /login against a /register prompt fails, and burns one of the few allowed attempts.
            assertTrue(LoginPromptDetector.isRegisterPrompt("/register <password> <password>"));
            assertFalse(LoginPromptDetector.isLoginPrompt("/register <password> <password>"));
            assertFalse(LoginPromptDetector.isLoginPrompt("Please register with /register"));
            assertFalse(LoginPromptDetector.isRegisterPrompt("Please login with /login"));
        }

        @Test
        void aLineOfferingBothIsALoginPrompt() {
            assertTrue(LoginPromptDetector.isLoginPrompt("Use /login or /register to continue"));
        }

        @Test
        void toleratesDecorationAroundThePrompt() {
            assertTrue(LoginPromptDetector.isLoginPrompt("  >> Please LOGIN with /login  << "));
            assertTrue(LoginPromptDetector.isLoginPrompt("[Auth] Password please"));
        }
    }

    @Nested
    @DisplayName("never treats player chat as a prompt")
    class PlayerChat {
        @Test
        void aPlayerSayingLoginIsNotAPrompt() {
            // The whole point: a player must not be able to make the client send the password.
            assertFalse(LoginPromptDetector.isLoginPrompt("hey use /login to get in", true));
            assertFalse(LoginPromptDetector.isLoginPrompt("please login with /login", true));
        }

        @Test
        void aQuestionAboutThePasswordIsNotAPrompt() {
            assertFalse(LoginPromptDetector.isLoginPrompt("what is the password for this server?"));
            assertFalse(LoginPromptDetector.isLoginPrompt("i forgot my password"));
            assertFalse(LoginPromptDetector.isLoginPrompt("the password is hunter2"));
        }

        @Test
        void ordinaryChatIsNotAPrompt() {
            assertFalse(LoginPromptDetector.isLoginPrompt("anyone selling diamonds?"));
            assertFalse(LoginPromptDetector.isLoginPrompt("lol"));
            assertFalse(LoginPromptDetector.isLoginPrompt(""));
            assertFalse(LoginPromptDetector.isLoginPrompt(null));
        }

        @Test
        void longProseIsNotAPrompt() {
            String prose = "please log in ".repeat(40);
            assertFalse(LoginPromptDetector.isLoginPrompt(prose));
        }

        @Test
        void theAdditionalServerWordingsAreRecognised() {
            assertTrue(LoginPromptDetector.isLoginPrompt("Login required"));
            assertTrue(LoginPromptDetector.isLoginPrompt("LOGIN REQUIRED TO PLAY"));
            assertTrue(LoginPromptDetector.isLoginPrompt("You must log in to play here"));
            assertTrue(LoginPromptDetector.isLoginPrompt("You need to login first"));
            assertTrue(LoginPromptDetector.isLoginPrompt("Click here to login"));
            assertTrue(LoginPromptDetector.isLoginPrompt("Log in required before you can move"));
        }

        @Test
        void aPlayerSayingThoseWordingsIsStillRefused() {
            // The extra phrases must not open a hole: a player must never be able to make the
            // client send a password, whatever they type.
            assertFalse(LoginPromptDetector.isLoginPrompt("login required lol", true));
            assertFalse(LoginPromptDetector.isLoginPrompt("you must log in", true));
            assertFalse(LoginPromptDetector.isLoginPrompt("click here to login", true));
        }

        @Test
        void aBareMentionOfLoginIsStillNotAPrompt() {
            assertFalse(LoginPromptDetector.isLoginPrompt("login"));
            assertFalse(LoginPromptDetector.isLoginPrompt("the login plugin is broken again"));
            assertFalse(LoginPromptDetector.isLoginPrompt("i love the login screen"));
        }
    }

    @Nested
    @DisplayName("recognises the outcome")
    class Outcome {
        @Test
        void successForms() {
            assertTrue(LoginPromptDetector.isLoginSuccess("Successfully logged in!"));
            assertTrue(LoginPromptDetector.isLoginSuccess("You are now logged in."));
            assertTrue(LoginPromptDetector.isLoginSuccess("Authentication successful"));
        }

        @Test
        void failureForms() {
            assertTrue(LoginPromptDetector.isLoginFailure("Wrong password!"));
            assertTrue(LoginPromptDetector.isLoginFailure("Invalid password, try again"));
            assertTrue(LoginPromptDetector.isLoginFailure("Too many failed attempts"));
        }

        @Test
        void successAndFailureAreDistinct() {
            assertFalse(LoginPromptDetector.isLoginSuccess("Wrong password!"));
            assertFalse(LoginPromptDetector.isLoginFailure("Successfully logged in!"));
        }

        @Test
        void aPlayerCannotFakeAnOutcome() {
            // Otherwise a player typing "wrong password" could switch auto-login off at will.
            assertFalse(LoginPromptDetector.isLoginFailure("wrong password", true));
            assertFalse(LoginPromptDetector.isLoginSuccess("successfully logged in", true));
            assertTrue(LoginPromptDetector.isLoginFailure("wrong password", false));
        }
    }

    @Nested
    @DisplayName("send schedule")
    class Policy {
        @Test
        void sendsUpToTheLimitThenStops() {
            LoginAttemptPolicy policy = new LoginAttemptPolicy(2, 0);
            assertTrue(policy.shouldSend(0));
            policy.markSent(0);
            assertTrue(policy.shouldSend(10));
            policy.markSent(10);
            assertFalse(policy.shouldSend(20), "must stop at the limit");
            assertTrue(policy.exhausted());
            assertTrue(policy.done());
        }

        @Test
        void respectsTheCooldown() {
            LoginAttemptPolicy policy = new LoginAttemptPolicy(5, 1000);
            assertTrue(policy.shouldSend(0));
            policy.markSent(0);
            assertFalse(policy.shouldSend(500), "inside the cooldown");
            assertTrue(policy.shouldSend(1000), "cooldown elapsed");
        }

        @Test
        void successStopsFurtherSends() {
            LoginAttemptPolicy policy = new LoginAttemptPolicy(5, 0);
            policy.markSent(0);
            policy.markSuccess();
            assertFalse(policy.shouldSend(1));
            assertTrue(policy.succeeded());
            assertTrue(policy.done());
        }

        @Test
        void aRejectedPasswordIsNotRetried() {
            // Retrying a wrong password looks like brute force and gets accounts banned.
            LoginAttemptPolicy policy = new LoginAttemptPolicy(10, 0);
            policy.markSent(0);
            policy.markFailure();
            assertFalse(policy.shouldSend(1));
            assertTrue(policy.done());
            assertFalse(policy.exhausted());
        }

        @Test
        void resetStartsAFreshSession() {
            LoginAttemptPolicy policy = new LoginAttemptPolicy(1, 0);
            policy.markSent(0);
            policy.markFailure();
            policy.reset();
            assertTrue(policy.shouldSend(0));
            assertFalse(policy.failed());
            assertFalse(policy.sawPrompt());
            assertTrue(policy.remaining() == 1);
        }
    }
}

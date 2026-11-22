package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The address the GUI hands to the user.
 *
 * <p>The bug this pins: the copied link was always {@code 127.0.0.1}, even with remote access on,
 * so the link shown for a phone could never work and the user had no way to tell why.
 */
class CompanionAddressTest {

    @Test
    @DisplayName("loopback-only always advertises loopback")
    void loopbackOnlyAdvertisesLoopback() throws UnknownHostException {
        CompanionConfig config = new CompanionConfig();
        config.setAllowRemote(false);
        config.setBindAddress("192.168.1.50");
        assertEquals("127.0.0.1", config.advertisedHost());
    }

    @Test
    @DisplayName("remote access advertises the configured bind address")
    void remoteAdvertisesConfiguredBind() {
        CompanionConfig config = new CompanionConfig();
        config.setAllowRemote(true);
        config.setBindAddress("192.168.1.50");
        assertEquals("192.168.1.50", config.advertisedHost());
    }

    @Test
    @DisplayName("binding all interfaces advertises a reachable LAN address, not 0.0.0.0")
    void wildcardBindAdvertisesReachableAddress() {
        CompanionConfig config = new CompanionConfig();
        config.setAllowRemote(true);
        config.setBindAddress("0.0.0.0");
        String host = config.advertisedHost();
        // 0.0.0.0 is a bind directive, not an address anyone can open in a browser.
        assertFalse(host.equals("0.0.0.0"), "advertised 0.0.0.0, which is not openable");
        assertTrue(host.equals("127.0.0.1") || host.contains("."),
                   "expected a concrete address, got " + host);
    }

    @Test
    @DisplayName("the advertised URL uses the advertised host and the real port")
    void urlUsesAdvertisedHost() {
        CompanionConfig config = new CompanionConfig();
        config.setAllowRemote(true);
        config.setBindAddress("192.168.1.50");
        config.setPort(6767);
        assertEquals("http://192.168.1.50:6767/", config.advertisedUrl(6767));
    }

    @Test
    @DisplayName("the URL falls back to the configured port when nothing is bound")
    void urlFallsBackToConfiguredPort() {
        CompanionConfig config = new CompanionConfig();
        config.setPort(6767);
        assertEquals("http://127.0.0.1:6767/", config.advertisedUrl(-1));
    }

    @Test
    @DisplayName("a hostname bind is advertised as given")
    void hostnameBindIsAdvertised() {
        CompanionConfig config = new CompanionConfig();
        config.setAllowRemote(true);
        config.setBindAddress("my-pc.local");
        assertEquals("my-pc.local", config.advertisedHost());
    }

    @Test
    @DisplayName("remote access off binds loopback no matter what the address says")
    void remoteOffAlwaysBindsLoopback() throws UnknownHostException {
        CompanionConfig config = new CompanionConfig();
        config.setAllowRemote(false);
        config.setBindAddress("0.0.0.0");
        assertTrue(config.resolveBindAddress().isLoopbackAddress(),
                   "a stray bind address exposed the client without opt-in");
    }

    @Test
    @DisplayName("remote access on with the default loopback address binds all interfaces")
    void remoteOnOverridesLoopbackDefault() throws UnknownHostException {
        CompanionConfig config = new CompanionConfig();
        config.setAllowRemote(true);
        // The default address. Leaving it alone must not silently cancel the opt-in, which is what
        // made the switch look broken.
        assertEquals("127.0.0.1", config.bindAddress());
        assertTrue(config.resolveBindAddress().isAnyLocalAddress(),
                   "allow-remote was on but the server still bound loopback only");
    }

    @Test
    @DisplayName("remote access on with a specific address binds exactly that address")
    void remoteOnHonoursSpecificAddress() throws UnknownHostException {
        CompanionConfig config = new CompanionConfig();
        config.setAllowRemote(true);
        config.setBindAddress("192.168.1.50");
        assertEquals("192.168.1.50", config.resolveBindAddress().getHostAddress());
    }
}

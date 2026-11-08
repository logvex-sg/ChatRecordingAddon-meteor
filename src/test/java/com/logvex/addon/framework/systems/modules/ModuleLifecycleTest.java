package com.logvex.addon.framework.systems.modules;

import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.framework.systems.modules.Modules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Structural and lifecycle checks over every registered module.
 *
 * <p>A module that touches {@code mc.player} cannot be activated outside a running client, so the
 * activation round trip is exercised on the shared {@link Module} base class (see {@link Probe}) and
 * everything else is checked structurally: a well-formed identity and settings the GUI can render.
 * Each module's in-game behaviour is covered by its own controller tests.
 */
class ModuleLifecycleTest {

    private static final List<Class<? extends Module>> MODULES = List.of(
        com.logvex.addon.chatlog.modules.ChatLoggerModule.class,
        com.logvex.addon.chatlog.modules.ChatMonitorModule.class,
        com.logvex.addon.chatlog.modules.KeywordAlertsModule.class,
        com.logvex.addon.chatlog.modules.PlayerActivityModule.class,
        com.logvex.addon.chatlog.modules.SessionStatsModule.class,
        com.logvex.addon.chatlog.modules.ChatFilterModule.class,
        com.logvex.addon.chatlog.modules.SessionTimelineModule.class,
        com.logvex.addon.chatlog.modules.ChatDigestModule.class,
        com.logvex.addon.chatlog.modules.WebhookMonitorModule.class,
        com.logvex.addon.chatlog.modules.PlayerNotesModule.class,
        com.logvex.addon.chatlog.modules.PlayerIdentityModule.class,
        com.logvex.addon.chatlog.modules.SessionHistoryModule.class,
        com.logvex.addon.assistant.modules.AssistantModule.class,
        com.logvex.addon.automation.modules.AutoCreativeModule.class,
        com.logvex.addon.automation.modules.AutoEatModule.class,
        com.logvex.addon.automation.modules.AutoToolModule.class,
        com.logvex.addon.automation.modules.AutoReplenishModule.class,
        com.logvex.addon.automation.modules.AutoSprintModule.class,
        com.logvex.addon.automation.modules.SafeWalkModule.class,
        com.logvex.addon.automation.modules.AntiAfkModule.class,
        com.logvex.addon.automation.modules.ArmorGuardModule.class,
        com.logvex.addon.automation.modules.AutoLogModule.class,
        com.logvex.addon.automation.modules.AutoReconnectModule.class,
        com.logvex.addon.automation.modules.ServerMonitorModule.class,
        com.logvex.addon.schematicdropper.SchematicDropperModule.class,
        com.logvex.addon.bot.BotModule.class,
        com.logvex.addon.world.modules.WorldIntelligenceModule.class,
        com.logvex.addon.notify.NotificationModule.class,
        com.logvex.addon.update.mc.UpdateModule.class,
        com.logvex.addon.companion.mc.CompanionModule.class
    );

    private static Module instantiate(Class<? extends Module> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            return fail("Could not construct " + type.getSimpleName() + ": " + e, e);
        }
    }

    /** A module with no side effects, used to prove the base-class lifecycle contract. */
    static final class Probe extends Module {
        int activations;
        int deactivations;

        Probe() {
            super(new Category("test"), "probe", "A test-only module.");
        }

        @Override
        public void onActivate() {
            activations++;
        }

        @Override
        public void onDeactivate() {
            deactivations++;
        }
    }

    @Test
    @DisplayName("toggling subscribes, unsubscribes, runs the hooks and is repeatable")
    void toggleContract() {
        Probe probe = new Probe();
        var bus = Modules.get().eventBus();

        assertFalse(probe.isActive());
        assertFalse(bus.isSubscribed(probe));

        probe.toggle();
        assertTrue(probe.isActive());
        assertTrue(bus.isSubscribed(probe), "an active module must be subscribed");
        assertEquals(1, probe.activations);
        bus.post(TickEvent.Post.get());

        probe.toggle();
        assertFalse(probe.isActive());
        assertFalse(bus.isSubscribed(probe), "a disabled module must not be subscribed");
        assertEquals(1, probe.deactivations);

        // A second round trip proves activation is repeatable, not one-shot.
        probe.toggle();
        assertTrue(probe.isActive());
        probe.toggle();
        assertFalse(probe.isActive());
        assertEquals(2, probe.activations);
        assertEquals(2, probe.deactivations);
    }

    @Test
    @DisplayName("disabling every module is idempotent and safe when none are active")
    void disableAllIsIdempotent() {
        Probe probe = new Probe();
        Modules.get().add(probe);
        probe.toggle();
        assertTrue(probe.isActive());
        Modules.get().disableAll();
        assertFalse(probe.isActive());
        Modules.get().disableAll();
        assertFalse(probe.isActive());
        assertSame(probe, Modules.get().get(Probe.class));
    }

    @Test
    @DisplayName("module identity is well formed: a unique name, a title and a description")
    void identityIsWellFormed() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Class<? extends Module> type : MODULES) {
            Module module = instantiate(type);
            assertNotNull(module.name, type.getSimpleName() + " needs a name");
            assertFalse(module.name.isBlank(), type.getSimpleName() + " needs a non-blank name");
            assertEquals(module.name, module.name.toLowerCase(java.util.Locale.ROOT),
                type.getSimpleName() + " name must be lower case");
            assertTrue(names.add(module.name),
                "two modules share the name '" + module.name + "'");
            assertNotNull(module.title, type.getSimpleName() + " needs a title");
            assertFalse(module.title.isBlank(), type.getSimpleName() + " needs a non-blank title");
            assertNotNull(module.description, type.getSimpleName() + " needs a description");
            assertFalse(module.description.isBlank(), type.getSimpleName() + " needs a non-blank description");
        }
    }

    @Test
    @DisplayName("every setting is named uniquely and documents itself for the GUI")
    void settingsAreDocumentedAndUnique() {
        for (Class<? extends Module> type : MODULES) {
            Module module = instantiate(type);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (SettingGroup group : module.settings.groups()) {
                for (Setting<?> setting : group.settings()) {
                    assertNotNull(setting.name, type.getSimpleName() + " has an unnamed setting");
                    assertFalse(setting.name.isBlank(), type.getSimpleName() + " has a blank setting name");
                    assertTrue(seen.add(setting.name),
                        type.getSimpleName() + " reuses the setting name '" + setting.name + "'");
                    assertNotNull(setting.description,
                        type.getSimpleName() + "." + setting.name + " needs a description");
                    assertFalse(setting.description.isBlank(),
                        type.getSimpleName() + "." + setting.name + " needs a non-blank description");
                }
            }
        }
    }
}

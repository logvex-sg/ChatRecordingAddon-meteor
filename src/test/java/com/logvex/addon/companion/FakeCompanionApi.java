package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionApi;
import com.logvex.addon.companion.core.CompanionSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fake {@link CompanionApi} that records what it was asked to do.
 *
 * <p>It exists so the router can be tested end to end without a Minecraft client: every action
 * returns a deterministic result and every call is recorded, so a test can assert both the response
 * and the side effect.
 */
public final class FakeCompanionApi implements CompanionApi {
    public final List<String> calls = new ArrayList<>();
    public CompanionSnapshot snapshot;
    public String lastChat;
    public String lastCommand;
    public String lastObjective;
    public String lastModule;
    public Boolean lastModuleActive;
    public String lastCancelledTask;
    public String lastNotify;

    public FakeCompanionApi() {
        this.snapshot = CompanionSnapshot.empty("node-1", "Test Node", "12.0.0");
    }

    @Override
    public CompanionSnapshot snapshot() {
        calls.add("snapshot");
        return snapshot;
    }

    @Override
    public ActionResult startBot() {
        calls.add("startBot");
        return ActionResult.ok("Bot started.");
    }

    @Override
    public ActionResult pauseBot() {
        calls.add("pauseBot");
        return ActionResult.ok("Bot paused.");
    }

    @Override
    public ActionResult resumeBot() {
        calls.add("resumeBot");
        return ActionResult.ok("Bot resumed.");
    }

    @Override
    public ActionResult stopBot() {
        calls.add("stopBot");
        return ActionResult.ok("Bot stopped.");
    }

    @Override
    public ActionResult emergencyStopBot() {
        calls.add("emergencyStopBot");
        return ActionResult.ok("Emergency stop.");
    }

    @Override
    public ActionResult enqueueTask(String objective) {
        calls.add("enqueueTask:" + objective);
        lastObjective = objective;
        return ActionResult.ok("Queued.");
    }

    @Override
    public ActionResult cancelTask(String taskId) {
        calls.add("cancelTask:" + taskId);
        lastCancelledTask = taskId;
        return ActionResult.ok("Cancelled.");
    }

    @Override
    public ActionResult setModule(String name, boolean active) {
        calls.add("setModule:" + name + "=" + active);
        lastModule = name;
        lastModuleActive = active;
        return ActionResult.ok("Module updated.");
    }

    /** Module name -> settings, so a test can drive the settings routes without a game. */
    public final Map<String, List<CompanionSnapshot.SettingView>> settings = new LinkedHashMap<>();
    public String lastSettingModule;
    public String lastSettingName;
    public com.google.gson.JsonElement lastSettingValue;

    @Override
    public List<CompanionSnapshot.SettingView> moduleSettings(String name) {
        calls.add("moduleSettings:" + name);
        return settings.get(name);
    }

    @Override
    public ActionResult setModuleSetting(String moduleName, String settingName,
                                         com.google.gson.JsonElement value) {
        calls.add("setModuleSetting:" + moduleName + ":" + settingName);
        lastSettingModule = moduleName;
        lastSettingName = settingName;
        lastSettingValue = value;
        if (!settings.containsKey(moduleName)) {
            return ActionResult.fail("No module is called \"" + moduleName + "\".");
        }
        return ActionResult.ok("Setting updated.");
    }

    @Override
    public ActionResult sendChat(String message) {
        calls.add("sendChat:" + message);
        lastChat = message;
        return ActionResult.ok("Sent.");
    }

    @Override
    public ActionResult sendCommand(String command) {
        calls.add("sendCommand:" + command);
        lastCommand = command;
        return ActionResult.ok("Sent.");
    }

    @Override
    public ActionResult notify(String message) {
        calls.add("notify:" + message);
        lastNotify = message;
        return ActionResult.ok("Notified.");
    }

    @Override
    public Map<String, Object> query(String name, Map<String, String> params) {
        calls.add("query:" + name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("params", params);
        return body;
    }

    @Override
    public List<String> queries() {
        return List.of("messages", "players", "keywords");
    }
}

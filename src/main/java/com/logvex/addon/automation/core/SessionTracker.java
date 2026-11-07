package com.logvex.addon.automation.core;

/**
 * Tracks one connection session: how long it lasted and the tallies the automation modules build up
 * while it is open.
 *
 * <p>It is deliberately Minecraft-free so it can be unit-tested, and every counter is a simple long
 * so it cannot grow without bound. The session is started on join and finished on disconnect, and
 * {@link #summary()} produces the one-line summary the report and the HUD use.
 */
public final class SessionTracker {
    private String serverName = "";
    private long startedAt;
    private long endedAt;
    private boolean open;

    private int itemsAcquired;
    private int itemsDropped;
    private int deaths;
    private int reconnects;
    private int afkActions;
    private int eats;

    /** Starts a new session, discarding the previous one's tallies. */
    public void start(String server, long now) {
        this.serverName = server == null ? "" : server;
        this.startedAt = now;
        this.endedAt = 0;
        this.open = true;
        itemsAcquired = 0;
        itemsDropped = 0;
        deaths = 0;
        reconnects = 0;
        afkActions = 0;
        eats = 0;
    }

    /** Marks the session closed at {@code now}. */
    public void end(long now) {
        if (!open) return;
        this.endedAt = now;
        this.open = false;
    }

    public boolean open() {
        return open;
    }

    public String serverName() {
        return serverName;
    }

    public long startedAt() {
        return startedAt;
    }

    /** How long the session has run, measured to {@code now} while it is still open. */
    public long duration(long now) {
        if (startedAt == 0) return 0;
        long end = open ? now : (endedAt == 0 ? now : endedAt);
        return Math.max(0, end - startedAt);
    }

    public void addAcquired(int amount) {
        if (amount > 0) itemsAcquired += amount;
    }

    public void addDropped(int amount) {
        if (amount > 0) itemsDropped += amount;
    }

    public void addDeath() {
        deaths++;
    }

    public void addReconnect() {
        reconnects++;
    }

    public void addAfkAction() {
        afkActions++;
    }

    public void addEat() {
        eats++;
    }

    public int itemsAcquired() {
        return itemsAcquired;
    }

    public int itemsDropped() {
        return itemsDropped;
    }

    public int deaths() {
        return deaths;
    }

    public int reconnects() {
        return reconnects;
    }

    public int afkActions() {
        return afkActions;
    }

    public int eats() {
        return eats;
    }

    /** A compact one-line summary of the session. */
    public String summary(long now) {
        if (startedAt == 0) return "No session yet.";
        StringBuilder out = new StringBuilder();
        out.append(com.logvex.addon.common.Messages.duration(duration(now)));
        if (!serverName.isBlank()) out.append(" on ").append(serverName);
        out.append(": ").append(itemsAcquired).append(" acquired");
        if (itemsDropped > 0) out.append(", ").append(itemsDropped).append(" dropped");
        if (deaths > 0) out.append(", ").append(deaths).append(" death").append(deaths == 1 ? "" : "s");
        if (reconnects > 0) out.append(", ").append(reconnects).append(" reconnect")
            .append(reconnects == 1 ? "" : "s");
        out.append('.');
        return out.toString();
    }
}

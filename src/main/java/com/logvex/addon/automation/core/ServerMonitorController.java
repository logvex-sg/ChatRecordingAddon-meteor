package com.logvex.addon.automation.core;

/**
 * Schedules periodic server reports and summarises the session.
 *
 * <p>It is a bounded timer: it hands out a "report now" signal every {@code intervalMs} and keeps a
 * small tally of what has happened, so the module that owns it only has to build and send the
 * message. Keeping the schedule here means the reporting cadence is testable without a clock.
 */
public final class ServerMonitorController {
    private boolean enabled;
    private long intervalMs;

    private long nextReportAt;
    private int reportsSent;

    public ServerMonitorController(long intervalMs) {
        this.intervalMs = Math.max(1_000, intervalMs);
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setIntervalMs(long value) {
        this.intervalMs = Math.max(1_000, value);
    }

    public long intervalMs() {
        return intervalMs;
    }

    public void reset() {
        nextReportAt = 0;
        reportsSent = 0;
    }

    /** Arms the schedule, e.g. on join. */
    public void arm(long now) {
        nextReportAt = now + intervalMs;
    }

    /** True when a report is due; advances the schedule when it returns true. */
    public boolean due(long now) {
        if (!enabled) return false;
        if (nextReportAt == 0) {
            arm(now);
            return false;
        }
        if (now < nextReportAt) return false;
        nextReportAt = now + intervalMs;
        reportsSent++;
        return true;
    }

    public int reportsSent() {
        return reportsSent;
    }

    /** Milliseconds until the next report, or -1 when not scheduled. */
    public long millisUntilNext(long now) {
        if (!enabled || nextReportAt == 0) return -1;
        return Math.max(0, nextReportAt - now);
    }

    public String status(long now) {
        if (!enabled) return "off";
        if (nextReportAt == 0) return "armed";
        return "next report in " + (millisUntilNext(now) / 1000) + "s (" + reportsSent + " sent)";
    }
}

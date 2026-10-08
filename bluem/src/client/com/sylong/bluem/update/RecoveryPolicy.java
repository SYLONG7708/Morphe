package com.sylong.bluem.update;

import java.util.ArrayDeque;

/** Monotonic recovery episodes, with a rolling budget shared across videos. */
public final class RecoveryPolicy {
    public enum Action { NONE, REFRESH, SWITCH, EXHAUSTED }
    private final ArrayDeque<Long> recoveries = new ArrayDeque<>();
    private String video = "";
    private long position = -1, stalledSince = -1, graceUntil, lastSwitch, healthySince = -1, retryAt;
    private int attempts;
    private boolean exhaustedReported, wasOffline;

    public Action sample(long now, String id, long time, boolean eligible, boolean online,
                         boolean paused, boolean loading, boolean error, long timeout) {
        if (!id.isEmpty() && !id.equals(video)) {
            video = id; position = time; resetEpisode();
            stalledSince = now; graceUntil = now + (error ? 2000 : 8000);
        }
        while (!recoveries.isEmpty() && now - recoveries.peekFirst() >= 600000) recoveries.removeFirst();
        if (!online) wasOffline = true;
        else if (wasOffline) {
            wasOffline = false; resetEpisode(); stalledSince = now; graceUntil = now + 2000;
        }
        if (!eligible || !online || paused || id.isEmpty() || time < 0) {
            if (!id.isEmpty() && time >= 0) position = time;
            stalledSince = -1; healthySince = -1;
            return Action.NONE;
        }
        long delta = time - position;
        // Explicit errors must not be hidden by stale controller positions.
        if (!error && Math.abs(delta) >= 250) {
            position = time; stalledSince = -1;
            if (!loading && delta > 0 && delta < 5000) {
                if (healthySince < 0) healthySince = now;
                if (now - healthySince >= 90000) resetEpisode();
            } else healthySince = -1;
            if (loading || delta < 0 || delta >= 5000) graceUntil = Math.max(graceUntil, now + timeout);
            return Action.NONE;
        }
        if (!loading && !error) { stalledSince = -1; return Action.NONE; }
        healthySince = -1;
        if (retryAt > 0 && now < retryAt) return Action.NONE;
        if (retryAt > 0) { resetEpisode(); stalledSince = now; }
        if (now < graceUntil) return Action.NONE;
        if (stalledSince < 0) stalledSince = now;
        long wait = error ? Math.min(2000, timeout) : timeout;
        if (now - stalledSince < wait) return Action.NONE;
        if (attempts >= 3 || recoveries.size() >= 6) {
            if (exhaustedReported) return Action.NONE;
            exhaustedReported = true;
            retryAt = recoveries.size() >= 6 ? recoveries.peekFirst() + 600000 : now + 60000;
            return Action.EXHAUSTED;
        }
        Action action = attempts++ == 0 ? Action.REFRESH : Action.SWITCH;
        recoveries.addLast(now);
        lastSwitch = now;
        graceUntil = now + 8000;
        stalledSince = now;
        return action;
    }
    private void resetEpisode() {
        attempts = 0; exhaustedReported = false; retryAt = 0; healthySince = -1;
    }
    public int attempts() { return attempts; }
    public long lastSwitch() { return lastSwitch; }
    public boolean waiting() { return retryAt > 0; }
}

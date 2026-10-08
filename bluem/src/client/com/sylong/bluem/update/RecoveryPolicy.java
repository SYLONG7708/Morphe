package com.sylong.bluem.update;

/** Deterministic stall detector. All times are monotonic milliseconds. */
public final class RecoveryPolicy {
    public enum Action { NONE, SWITCH, EXHAUSTED }
    private String video = "";
    private long position = -1, stalledSince = -1, graceUntil, lastSwitch;
    private int attempts;
    private boolean exhaustedReported;

    public Action sample(long now, String id, long time, boolean eligible, boolean online,
                         boolean paused, boolean loading, boolean error, long timeout) {
        if (!id.isEmpty() && !id.equals(video)) {
            video = id; position = time; attempts = 0; exhaustedReported = false;
            stalledSince = now; graceUntil = now + 8000;
        }
        if (!eligible || !online || paused || id.isEmpty() || time < 0) {
            if (!id.isEmpty() && time >= 0) position = time;
            stalledSince = -1;
            return Action.NONE;
        }
        // Seeking in either direction and actual progress invalidate the old stall timer.
        if (Math.abs(time - position) >= 250) {
            position = time; stalledSince = -1;
            if (loading) graceUntil = Math.max(graceUntil, now + timeout);
            return Action.NONE;
        }
        if (!loading && !error) { stalledSince = -1; return Action.NONE; }
        if (now < graceUntil) return Action.NONE;
        if (stalledSince < 0) stalledSince = now;
        long wait = error ? Math.min(2000, timeout) : timeout;
        if (now - stalledSince < wait) return Action.NONE;
        if (attempts >= 2) {
            if (exhaustedReported) return Action.NONE;
            exhaustedReported = true;
            return Action.EXHAUSTED;
        }
        attempts++;
        lastSwitch = now;
        graceUntil = now + 8000;
        stalledSince = now;
        return Action.SWITCH;
    }
    public int attempts() { return attempts; }
    public long lastSwitch() { return lastSwitch; }
}

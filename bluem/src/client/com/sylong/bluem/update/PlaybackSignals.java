package com.sylong.bluem.update;

import java.util.Locale;

/** Pure classification; does not change accounts or bypass content restrictions. */
public final class PlaybackSignals {
    public enum Failure { NONE, TRANSIENT, NETWORK, ACCOUNT, CONTENT }
    public static boolean watchActivity(String name) {
        return name.equals("com.google.android.apps.youtube.app.watchwhile.MainActivity")
            || name.equals("com.google.android.apps.youtube.app.watchwhile.InternalMainActivity");
    }
    public static Failure failure(boolean visible, String text, boolean stateError) {
        if (!visible) return stateError ? Failure.TRANSIENT : Failure.NONE;
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (contains(value, "sign in", "log in", "login", "verify your age", "confirm your age", "not a bot",
                "登入", "登录", "年齡", "年龄", "不是機器人", "不是机器人")) return Failure.ACCOUNT;
        if (contains(value, "private video", "video is private", "has been removed",
                "not available in your country", "not available in your region", "members-only", "members only",
                "purchase", "rent this", "copyright", "deleted", "私人影片", "私人视频",
                "已移除", "已刪除", "已删除", "會員專屬", "会员专享",
                "購買", "购买", "版權", "版权", "國家/地區", "国家/地区")) return Failure.CONTENT;
        if (contains(value, "no internet", "network", "offline", "connection", "connect to the internet",
                "網路", "网络", "離線", "离线", "連線", "连接")) return Failure.NETWORK;
        return Failure.TRANSIENT;
    }
    private static boolean contains(String value, String... phrases) {
        for (String phrase : phrases) if (value.contains(phrase)) return true;
        return false;
    }
    public static boolean paused(String state, int mediaState, boolean explicitError) {
        if (explicitError) return false;
        if (mediaState == 2 || mediaState == 1) return true;
        if (mediaState == 3 || mediaState == 6 || mediaState == 8) return false;
        return state.equals("PAUSED") || state.equals("ENDED");
    }
    public static boolean stateError(String state, int mediaState, boolean progressing) {
        return !progressing && (state.equals("RECOVERABLE_ERROR") || state.equals("UNRECOVERABLE_ERROR") || mediaState == 7);
    }
    public static boolean activePlayback(boolean paused, boolean progressing, boolean blocked,
            boolean recoveryWaiting, boolean error, boolean loading, boolean stalled, boolean playerOpen) {
        if (progressing) return true;
        if (paused || blocked || (recoveryWaiting && (error || loading || stalled))) return false;
        return error || loading || playerOpen;
    }
    public static long resumePosition(long position, long lastGood, boolean error) {
        return position < 0 || (error && position == 0 && lastGood > 0) ? Math.max(0, lastGood) : position;
    }
    private PlaybackSignals() {}
}

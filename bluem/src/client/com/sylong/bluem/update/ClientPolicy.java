package com.sylong.bluem.update;

import java.util.*;

/** Select only stream clients the installed runtime can actually execute. */
public final class ClientPolicy {
    public static final List<String> ALLOWED = Collections.unmodifiableList(Arrays.asList(
        "TV_SABR", "TV_SIMPLY", "ANDROID_CREATOR", "VISIONOS_1_02"));

    public static List<String> compatible(List<String> requested, Set<String> available,
            Set<String> requiresJs, boolean jsSupported) {
        List<String> candidates = new ArrayList<>();
        // A legacy signed order must not strand older WebViews on JS-only clients.
        if (!jsSupported) candidates.add("VISIONOS_1_02");
        candidates.addAll(requested);
        candidates.addAll(ALLOWED);
        List<String> result = new ArrayList<>();
        for (String value : candidates) {
            if (ALLOWED.contains(value) && available.contains(value) && !result.contains(value)
                    && (jsSupported || !requiresJs.contains(value))) result.add(value);
        }
        return result;
    }

    public static String choose(List<String> compatible, String healthy, String selected,
            Map<String, Long> blockedUntil, long now) {
        List<String> candidates = new ArrayList<>();
        candidates.add(healthy); candidates.add(selected); candidates.addAll(compatible);
        for (String value : candidates) {
            Long until = blockedUntil.get(value);
            if (compatible.contains(value) && (until == null || until <= now)) return value;
        }
        // Exhausted sources are retried only by the separate bounded recovery budget.
        return compatible.isEmpty() ? null : compatible.get(0);
    }

    private ClientPolicy() {}
}

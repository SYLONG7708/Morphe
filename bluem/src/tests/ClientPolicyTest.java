import java.util.*;
import com.sylong.bluem.update.ClientPolicy;
public final class ClientPolicyTest {
    private static int checks;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    public static void main(String[] args) {
        Set<String> available = new HashSet<>(ClientPolicy.ALLOWED);
        Set<String> js = new HashSet<>(Arrays.asList("TV_SABR", "TV_SIMPLY"));
        List<String> legacy = Arrays.asList("TV_SABR", "ANDROID_CREATOR", "TV_SIMPLY");
        List<String> old = ClientPolicy.compatible(legacy, available, js, false);
        check(old.equals(Arrays.asList("VISIONOS_1_02", "ANDROID_CREATOR")), "WebView 87 must never prefer or fallback to JS clients");
        Map<String,Long> failures = new HashMap<>();
        check("VISIONOS_1_02".equals(ClientPolicy.choose(old, "TV_SABR", "TV_SIMPLY", failures, 10)), "stale healthy and user defaults cannot override capability");
        check("ANDROID_CREATOR".equals(ClientPolicy.choose(old, "ANDROID_CREATOR", "TV_SIMPLY", failures, 10)), "actual healthy fallback is remembered across cold starts");
        failures.put("ANDROID_CREATOR", 100L);
        check("VISIONOS_1_02".equals(ClientPolicy.choose(old, "ANDROID_CREATOR", "ANDROID_CREATOR", failures, 10)), "failing remembered source cools down");
        check("ANDROID_CREATOR".equals(ClientPolicy.choose(old, "ANDROID_CREATOR", "", failures, 100)), "source becomes eligible after cooldown");
        List<String> modern = ClientPolicy.compatible(legacy, available, js, true);
        check(modern.get(0).equals("TV_SABR") && modern.contains("VISIONOS_1_02"), "modern runtime retains signed order and independent fallback");
        check(ClientPolicy.choose(Collections.emptyList(), "TV_SABR", "TV_SIMPLY", failures, 10)==null, "no compatible runtime fails closed");
        available.remove("VISIONOS_1_02");
        check(ClientPolicy.compatible(legacy, available, js, false).equals(Arrays.asList("ANDROID_CREATOR")), "removed upstream enums are never selected");
        check(!ClientPolicy.compatible(Arrays.asList("UNTRUSTED", "TV_SABR", "TV_SABR"), available, js, true).contains("UNTRUSTED"), "untrusted client rejected");
        System.out.println("ClientPolicyTest: " + checks + " checks PASS");
    }
}

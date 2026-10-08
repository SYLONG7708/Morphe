import com.sylong.bluem.update.UpdatePolicy;
import java.nio.file.*;
public class LegacyPolicyCheck {
    public static void main(String[] args) throws Exception {
        Path p = Path.of(args[0]);
        UpdatePolicy.Release release = UpdatePolicy.verify(Files.readAllBytes(p.resolve("blue-m-stable.json")),
            Files.readAllBytes(p.resolve("blue-m-stable.json.sig")), Files.readAllBytes(Path.of(args[1])), 2026092404L, false);
        if (release.items.size()!=2 || release.sequence<=2026092404L) throw new AssertionError("No update");
        for (UpdatePolicy.Item item : release.items) {
            if (!item.compatible(33,new String[]{"arm64-v8a"})) throw new AssertionError("Incompatible");
            long installed=item.pkg.equals(UpdatePolicy.YOUTUBE)?2026092404L:255034004L;
            if (item.code<=installed) throw new AssertionError("Not an upgrade");
        }
        System.out.println("PASS: existing September client accepts both signed recommended upgrades");
    }
}

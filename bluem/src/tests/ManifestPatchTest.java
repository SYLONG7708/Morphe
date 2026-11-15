import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Exercise the production patch through binary XML serialization with the selected CLI library. */
public final class ManifestPatchTest {
    static final int ICON = 0x7f080001;
    static final int NETWORK_CONFIG = 0x7f150001;
    static final int CODE = 2026100922;
    static final String VERSION = "21.23.492";

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static AndroidManifestBlock fixture() {
        AndroidManifestBlock manifest = new AndroidManifestBlock();
        ResXmlElement root = manifest.newElement("manifest");
        root.getOrCreateNamespace("http://schemas.android.com/apk/res/android", "android");
        ResXmlAttribute pkg = root.newAttribute();
        pkg.setName("package");
        pkg.setDataAsString("app.morphe.android.youtube");
        ResXmlElement app = manifest.getOrCreateApplicationElement();
        app.getOrCreateAndroidAttribute("icon", android.R.attr.icon)
                .setDataType(android.util.TypedValue.TYPE_REFERENCE);
        app.searchAttributeByResourceId(android.R.attr.icon).setData(ICON);
        app.getOrCreateAndroidAttribute("networkSecurityConfig", android.R.attr.networkSecurityConfig)
                .setDataType(android.util.TypedValue.TYPE_REFERENCE);
        app.searchAttributeByResourceId(android.R.attr.networkSecurityConfig).setData(NETWORK_CONFIG);
        app.getOrCreateAndroidAttribute("usesCleartextTraffic", android.R.attr.usesCleartextTraffic)
                .setValueAsBoolean(false);
        named(app, "activity", "com.google.android.apps.youtube.app.watchwhile.MainActivity");
        named(app, "service", "original.Service");
        named(root, "uses-permission", "android.permission.INTERNET");
        named(root, "uses-permission", "android.permission.REQUEST_INSTALL_PACKAGES");
        return manifest;
    }

    static ResXmlElement named(ResXmlElement parent, String tag, String name) {
        ResXmlElement element = parent.newElement(tag);
        element.getOrCreateAndroidAttribute("name", android.R.attr.name).setDataAsString(name);
        return element;
    }

    static ResXmlElement find(ResXmlElement parent, String tag, String name) {
        ResXmlElement found = null;
        for (Iterator<?> it = parent.getElements(tag); it.hasNext();) {
            ResXmlElement element = (ResXmlElement) it.next();
            if (name.equals(element.searchAttributeByResourceId(android.R.attr.name).getDataAsString())) {
                require(found == null, "Duplicate component: " + name);
                found = element;
            }
        }
        return found;
    }

    static void string(ResXmlElement element, int id, String expected) {
        require(element != null, "Missing element for " + expected);
        ResXmlAttribute attr = element.searchAttributeByResourceId(id);
        require(attr != null && attr.dataType() == android.util.TypedValue.TYPE_STRING
                && expected.equals(attr.getDataAsString()), "Incorrect string: " + expected);
    }

    static void typed(ResXmlElement element, int id, int type, int expected) {
        require(element != null, "Missing element for attribute " + id);
        ResXmlAttribute attr = element.searchAttributeByResourceId(id);
        require(attr != null && attr.dataType() == type && attr.getData() == expected,
                "Incorrect typed value: " + id);
    }

    static void verify(boolean qa) throws Exception {
        AndroidManifestBlock manifest = fixture();
        PatchApk.patchManifest(manifest, CODE, VERSION, qa);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manifest.writeBytes(out);
        manifest = AndroidManifestBlock.load(new ByteArrayInputStream(out.toByteArray()));
        ResXmlElement root = manifest.getManifestElement();
        ResXmlElement app = manifest.getApplicationElement();
        require("app.morphe.android.youtube".equals(manifest.getPackageName()), "Package changed");
        require(manifest.getVersionCode() == CODE && VERSION.equals(manifest.getVersionName()), "Version lost");
        typed(root, android.R.attr.versionCode, android.util.TypedValue.TYPE_INT_DEC, CODE);
        string(root, android.R.attr.versionName, VERSION);
        typed(app, android.R.attr.icon, android.util.TypedValue.TYPE_REFERENCE, ICON);
        require(find(app, "activity", "com.google.android.apps.youtube.app.watchwhile.MainActivity") != null,
                "Original activity lost");
        require(find(app, "service", "original.Service") != null, "Original service lost");
        require(manifest.getUsesPermissions().containsAll(List.of("android.permission.INTERNET",
                "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
                "android.permission.ACCESS_NETWORK_STATE")), "Permissions lost");
        require(find(root, "uses-permission", "android.permission.REQUEST_INSTALL_PACKAGES") != null,
                "Install permission missing");
        ResXmlElement setup = find(app, "activity", "com.sylong.bluem.update.SetupActivity");
        typed(setup, android.R.attr.exported, android.util.TypedValue.TYPE_INT_BOOLEAN, 0);
        typed(setup, android.R.attr.theme, android.util.TypedValue.TYPE_REFERENCE, android.R.style.Theme_Material_NoActionBar);
        string(setup, android.R.attr.taskAffinity, "app.morphe.android.youtube.bluem.setup");
        ResXmlElement provider = find(app, "provider", "com.sylong.bluem.update.UpdateProvider");
        typed(provider, android.R.attr.exported, android.util.TypedValue.TYPE_INT_BOOLEAN, 0);
        typed(provider, android.R.attr.grantUriPermissions, android.util.TypedValue.TYPE_INT_BOOLEAN, -1);
        string(provider, android.R.attr.authorities, "app.morphe.android.youtube.bluem.updates");
        typed(find(app, "receiver", "com.sylong.bluem.update.InstallResultReceiver"), android.R.attr.exported,
                android.util.TypedValue.TYPE_INT_BOOLEAN, 0);
        string(find(app, "meta-data", "com.sylong.bluem.UPDATE_CLIENT_REVISION"), android.R.attr.value, "11");
        string(find(app, "meta-data", "com.sylong.bluem.LIVE_BRIDGE"), android.R.attr.value, "1");
        typed(find(app, "service", "com.sylong.bluem.live.LiveResolveService"), android.R.attr.exported,
                android.util.TypedValue.TYPE_INT_BOOLEAN, -1);
        Set<String> partners = new HashSet<>();
        for (Iterator<?> queries = root.getElements("queries"); queries.hasNext();) {
            for (Iterator<?> packages = ((ResXmlElement) queries.next()).getElements("package"); packages.hasNext();) {
                partners.add(((ResXmlElement) packages.next()).searchAttributeByResourceId(android.R.attr.name).getDataAsString());
            }
        }
        require(partners.equals(qa ? Set.of("tw.com.sylong.tvcar", "tw.com.sylong.tvcar.direct")
                : Set.of("tw.com.sylong.tvcar")), "Partner visibility boundary changed");
        ResXmlElement receiver = find(app, "receiver", "com.sylong.bluem.update.QaReceiver");
        if (qa) {
            typed(receiver, android.R.attr.exported, android.util.TypedValue.TYPE_INT_BOOLEAN, -1);
            string(receiver, android.R.attr.permission, "android.permission.DUMP");
            require(app.searchAttributeByResourceId(android.R.attr.networkSecurityConfig) == null, "QA network policy retained");
        } else {
            require(receiver == null, "QA receiver exposed in production");
            typed(app, android.R.attr.networkSecurityConfig, android.util.TypedValue.TYPE_REFERENCE, NETWORK_CONFIG);
        }
        typed(app, android.R.attr.usesCleartextTraffic, android.util.TypedValue.TYPE_INT_BOOLEAN, qa ? -1 : 0);
    }

    public static void main(String[] args) throws Exception {
        verify(false);
        verify(true);
        AndroidManifestBlock wrong = fixture();
        wrong.getManifestElement().searchAttributeByName("package").setDataAsString("other.package");
        try {
            PatchApk.patchManifest(wrong, CODE, VERSION, false);
            throw new AssertionError("Wrong package accepted");
        } catch (IllegalArgumentException expected) {
            require("Wrong base package".equals(expected.getMessage()), "Unexpected rejection");
        }
        System.out.println("Manifest patch PASS: binary XML roundtrip, typed versions/resources, original components, production/QA boundaries");
    }
}

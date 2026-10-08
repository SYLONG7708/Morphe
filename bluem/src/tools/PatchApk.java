import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Touches the binary manifest and exactly one method; no resource recompilation. */
public final class PatchApk {
    static final String MAIN = "Lcom/google/android/apps/youtube/app/watchwhile/MainActivity;";
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        try (ZipFile apk = new ZipFile(args[0])) {
            AndroidManifestBlock m = AndroidManifestBlock.load(apk.getInputStream(apk.getEntry("AndroidManifest.xml")));
            if (!"app.morphe.android.youtube".equals(m.getPackageName())) throw new IllegalArgumentException("Wrong base package");
            int icon = m.getIconResourceId();
            ResXmlElement manifest = m.getManifestElement();
            manifest.getOrCreateAndroidAttribute("versionCode", android.R.attr.versionCode).setType((byte) android.util.TypedValue.TYPE_INT_DEC);
            manifest.getOrCreateAndroidAttribute("versionCode", android.R.attr.versionCode).setData(Integer.parseInt(args[2]));
            manifest.getOrCreateAndroidAttribute("versionName", android.R.attr.versionName).setValueAsString(args[3]);
            for (String permission : List.of("android.permission.REQUEST_INSTALL_PACKAGES",
                    "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION", "android.permission.ACCESS_NETWORK_STATE")) {
                if (!m.getUsesPermissions().contains(permission)) {
                    ResXmlElement element = manifest.newElement("uses-permission");
                    element.getOrCreateAndroidAttribute("name", android.R.attr.name).setValueAsString(permission);
                }
            }
            ResXmlElement app = m.getApplicationElement();
            ResXmlElement provider = app.newElement("provider");
            provider.getOrCreateAndroidAttribute("name", android.R.attr.name).setValueAsString("com.sylong.bluem.update.UpdateProvider");
            provider.getOrCreateAndroidAttribute("authorities", android.R.attr.authorities).setValueAsString("app.morphe.android.youtube.bluem.updates");
            provider.getOrCreateAndroidAttribute("exported", android.R.attr.exported).setValueAsBoolean(false);
            provider.getOrCreateAndroidAttribute("grantUriPermissions", android.R.attr.grantUriPermissions).setValueAsBoolean(true);
            ResXmlElement receiver = app.newElement("receiver");
            receiver.getOrCreateAndroidAttribute("name", android.R.attr.name).setValueAsString("com.sylong.bluem.update.InstallResultReceiver");
            receiver.getOrCreateAndroidAttribute("exported", android.R.attr.exported).setValueAsBoolean(false);
            ResXmlElement meta = app.newElement("meta-data");
            meta.getOrCreateAndroidAttribute("name", android.R.attr.name).setValueAsString("com.sylong.bluem.UPDATE_CLIENT_REVISION");
            meta.getOrCreateAndroidAttribute("value", android.R.attr.value).setValueAsString("4");
            if (Boolean.parseBoolean(args[4])) {
                ResXmlElement qaReceiver = app.newElement("receiver");
                qaReceiver.getOrCreateAndroidAttribute("name", android.R.attr.name).setValueAsString("com.sylong.bluem.update.QaReceiver");
                qaReceiver.getOrCreateAndroidAttribute("exported", android.R.attr.exported).setValueAsBoolean(true);
                qaReceiver.getOrCreateAndroidAttribute("permission", android.R.attr.permission).setValueAsString("android.permission.DUMP");
                // Local QA only; production preserves the original network security policy.
                app.removeAttributesWithId(android.R.attr.networkSecurityConfig);
                app.getOrCreateAndroidAttribute("usesCleartextTraffic", android.R.attr.usesCleartextTraffic).setValueAsBoolean(true);
            }
            m.refreshFull();
            if (icon == 0 || m.getIconResourceId() != icon) throw new IllegalStateException("Icon reference changed");
            try (OutputStream manifestOut = Files.newOutputStream(out.resolve("AndroidManifest.xml"))) { m.writeBytes(manifestOut); }
            int hits = 0;
            for (ZipEntry entry : Collections.list(apk.entries())) {
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                DexBackedDexFile dex;
                try (InputStream in = new BufferedInputStream(apk.getInputStream(entry))) {
                    dex = DexBackedDexFile.fromInputStream(null, in);
                }
                ClassDef main = null;
                for (ClassDef cls : dex.getClasses()) if (cls.getType().equals(MAIN)) main = cls;
                if (main == null) continue;
                List<Method> methods = new ArrayList<>();
                for (Method method : main.getMethods()) {
                    if (method.getName().equals("onCreate") && method.getParameterTypes().toString().equals("[Landroid/os/Bundle;]")) {
                        MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
                        body.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                            body.getRegisterCount() - 2, 1, new ImmutableMethodReference(
                            "Lcom/sylong/bluem/update/StandaloneUpdates;", "attach", List.of("Landroid/app/Activity;"), "V")));
                        methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body));
                        hits++;
                    } else methods.add(method);
                }
                ClassDef replacement = new ImmutableClassDef(main.getType(), main.getAccessFlags(), main.getSuperclass(),
                    main.getInterfaces(), main.getSourceFile(), main.getAnnotations(), main.getFields(), methods);
                DexPool pool = new DexPool(dex.getOpcodes());
                for (ClassDef cls : dex.getClasses()) pool.internClass(cls.getType().equals(MAIN) ? replacement : cls);
                FileDataStore store = new FileDataStore(out.resolve(entry.getName()).toFile());
                try { pool.writeTo(store); } finally { store.close(); }
                System.out.println("Patched MainActivity.onCreate in " + entry.getName());
            }
            if (hits != 1) throw new IllegalStateException("Expected one lifecycle hook, got " + hits);
        }
    }
}

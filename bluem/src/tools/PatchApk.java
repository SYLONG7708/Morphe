import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Hooks the activity and routes MicroG setup into the signed in-app updater. */
public final class PatchApk {
    static final String MAIN = "Lcom/google/android/apps/youtube/app/watchwhile/MainActivity;";
    static final String GMS = "Lapp/morphe/extension/shared/patches/GmsCoreSupportPatch;";
    static boolean isTlsGuard(ClassDef cls) {
        if (!"Ljavax/net/ssl/SSLSocketFactory;".equals(cls.getSuperclass()) &&
            !"Ljavax/net/ssl/SSLServerSocketFactory;".equals(cls.getSuperclass())) return false;
        for (Method method : cls.getMethods()) {
            if (method.getImplementation() == null) continue;
            for (Instruction ins : method.getImplementation().getInstructions()) {
                if (!(ins instanceof ReferenceInstruction)) continue;
                Reference ref = ((ReferenceInstruction) ins).getReference();
                if (ref instanceof StringReference && "Unable to find a default SSL provider.".equals(((StringReference) ref).getString())) return true;
            }
        }
        return false;
    }
    static Method redirect(Method method, String owner, String name) {
        int registers = method.getParameterTypes().size(); // all these static hooks use reference parameters
        MutableMethodImplementation body = new MutableMethodImplementation(registers);
        body.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, registers,
            new ImmutableMethodReference(owner, name, method.getParameterTypes(), "V")));
        body.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
    }
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
            ResXmlElement setup = app.newElement("activity");
            setup.getOrCreateAndroidAttribute("name", android.R.attr.name).setValueAsString("com.sylong.bluem.update.SetupActivity");
            setup.getOrCreateAndroidAttribute("exported", android.R.attr.exported).setValueAsBoolean(false);
            setup.getOrCreateAndroidAttribute("taskAffinity", android.R.attr.taskAffinity).setValueAsString("app.morphe.android.youtube.bluem.setup");
            setup.getOrCreateAndroidAttribute("theme", android.R.attr.theme).setType((byte)android.util.TypedValue.TYPE_REFERENCE);
            setup.getOrCreateAndroidAttribute("theme", android.R.attr.theme).setData(android.R.style.Theme_Material_NoActionBar);
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
            meta.getOrCreateAndroidAttribute("value", android.R.attr.value).setValueAsString("9");
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
            int hits = 0, gmsHooks = 0, tlsHooks = 0, tlsClasses = 0;
            int nextDexIndex = 1;
            for (ZipEntry entry : Collections.list(apk.entries())) {
                String name = entry.getName();
                if (!name.matches("classes[0-9]*\\.dex")) continue;
                int index = name.equals("classes.dex") ? 1 : Integer.parseInt(name.substring(7, name.length() - 4));
                nextDexIndex = Math.max(nextDexIndex, index + 1);
            }
            for (ZipEntry entry : Collections.list(apk.entries())) {
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                DexBackedDexFile dex;
                try (InputStream in = new BufferedInputStream(apk.getInputStream(entry))) {
                    dex = DexBackedDexFile.fromInputStream(null, in);
                }
                List<ClassDef> replacements = new ArrayList<>();
                boolean changed = false;
                for (ClassDef main : dex.getClasses()) {
                boolean tlsGuard = isTlsGuard(main);
                if (tlsGuard) tlsClasses++;
                if (!main.getType().equals(MAIN) && !main.getType().equals(GMS) && !tlsGuard) { replacements.add(main); continue; }
                List<Method> methods = new ArrayList<>();
                for (Method method : main.getMethods()) {
                    if (tlsGuard && method.getImplementation() != null) {
                        MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
                        boolean rewrittenTls = false;
                        for (int i = 0; i < body.getInstructions().size(); i++) {
                            Instruction instruction = body.getInstructions().get(i);
                            if (!(instruction instanceof ReferenceInstruction)) continue;
                            Reference ref = ((ReferenceInstruction) instruction).getReference();
                            if (!(ref instanceof MethodReference)) continue;
                            MethodReference called = (MethodReference) ref;
                            if (!called.getDefiningClass().equals("Ljavax/net/ssl/SSLContext;") ||
                                !called.getName().equals("getInstance") ||
                                !called.getParameterTypes().toString().equals("[Ljava/lang/String;]")) continue;
                            ImmutableMethodReference replacement = new ImmutableMethodReference(
                                "Lcom/sylong/bluem/update/TlsCompatibility;", "context", List.of("Ljava/lang/String;"), "Ljavax/net/ssl/SSLContext;");
                            if (instruction.getOpcode() == Opcode.INVOKE_STATIC && instruction instanceof FiveRegisterInstruction) {
                                FiveRegisterInstruction call = (FiveRegisterInstruction) instruction;
                                body.replaceInstruction(i, new BuilderInstruction35c(Opcode.INVOKE_STATIC,
                                    call.getRegisterCount(),call.getRegisterC(),call.getRegisterD(),call.getRegisterE(),call.getRegisterF(),call.getRegisterG(),replacement));
                            } else if (instruction.getOpcode() == Opcode.INVOKE_STATIC_RANGE && instruction instanceof RegisterRangeInstruction) {
                                RegisterRangeInstruction call = (RegisterRangeInstruction) instruction;
                                body.replaceInstruction(i,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,call.getStartRegister(),call.getRegisterCount(),replacement));
                            } else throw new IllegalStateException("Unexpected TLS factory invocation");
                            rewrittenTls = true; tlsHooks++; changed = true;
                        }
                        methods.add(rewrittenTls ? new ImmutableMethod(method.getDefiningClass(),method.getName(),method.getParameters(),
                            method.getReturnType(),method.getAccessFlags(),method.getAnnotations(),method.getHiddenApiRestrictions(),body) : method);
                    } else if (main.getType().equals(GMS)) {
                        if (method.getName().equals("open") && method.getParameterTypes().toString().equals("[Landroid/app/Activity;, Ljava/lang/String;]")) {
                            methods.add(redirect(method, "Lcom/sylong/bluem/update/StartupSupport;", "open")); gmsHooks++; changed = true;
                        } else if (method.getName().equals("checkForMicroGUpdate") && method.getParameterTypes().toString().equals("[Landroid/app/Activity;]")) {
                            methods.add(redirect(method, "Lcom/sylong/bluem/update/StandaloneUpdates;", "checkMicroG")); gmsHooks++; changed = true;
                        } else if (method.getName().equals("openGmsCoreDisableBatteryOptimizationsIntent") && method.getParameterTypes().toString().equals("[Landroid/app/Activity;]")) {
                            methods.add(redirect(method, "Lcom/sylong/bluem/update/StartupSupport;", "openBatterySettings")); gmsHooks++; changed = true;
                        } else methods.add(method);
                    } else if (method.getName().equals("onCreate") && method.getParameterTypes().toString().equals("[Landroid/os/Bundle;]")) {
                        MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
                        body.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                            body.getRegisterCount() - 2, 1, new ImmutableMethodReference(
                            "Lcom/sylong/bluem/update/StandaloneUpdates;", "attach", List.of("Landroid/app/Activity;"), "V")));
                        methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body));
                        hits++;
                        changed = true;
                    } else methods.add(method);
                }
                replacements.add(new ImmutableClassDef(main.getType(), main.getAccessFlags(), main.getSuperclass(),
                    main.getInterfaces(), main.getSourceFile(), main.getAnnotations(), main.getFields(), methods));
                }
                if (!changed) continue;
                nextDexIndex = DexPartitionWriter.write(out, entry.getName(), nextDexIndex,
                        dex.getOpcodes(), replacements);
                System.out.println("Patched update/startup hooks in " + entry.getName());
            }
            if (hits != 1) throw new IllegalStateException("Expected one lifecycle hook, got " + hits);
            if (gmsHooks != 3) throw new IllegalStateException("Expected three MicroG startup hooks, got " + gmsHooks);
            if (tlsClasses != 2 || tlsHooks != 2) throw new IllegalStateException("Expected two TLS guard backends; got classes="+tlsClasses+" calls="+tlsHooks);
        }
    }
}

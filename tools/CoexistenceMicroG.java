/* Derive the official runtime extension for our separately signed MicroG package.
 * dexlib2 rewrites the DEX tables/checksums; never overwrite raw string bytes.
 */
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.value.StringEncodedValue;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue;

public class CoexistenceMicroG {
    static final String TYPE = "Lapp/morphe/extension/shared/patches/GmsCoreSupportPatch;";
    static final String UPSTREAM_CERT = "0b6c9515afb195fac59601696ba0a7907a0b217ccf720b43148427ccf64343e7";
    static final String DOWNLOAD = "https://github.com/SYLONG7708/Morphe/releases/latest";

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static String string(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ref
                && ref.getReference() instanceof StringReference value ? value.getString() : null;
    }

    static Method withCode(Method method, MethodImplementation code) {
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                method.getHiddenApiRestrictions(), code);
    }

    static DexFile derive(DexFile dex, String certificate) {
        require(certificate.matches("[0-9a-f]{64}") && !certificate.equals(UPSTREAM_CERT)
                && !certificate.matches("0{64}"), "Expected a distinct pinned SHA-256 certificate");
        List<ClassDef> classes = new ArrayList<>();
        int targets = 0, certLoads = 0, downloadMethods = 0;
        for (ClassDef cls : dex.getClasses()) {
            if (!TYPE.equals(cls.getType())) {
                classes.add(cls);
                continue;
            }
            targets++;
            List<Field> fields = new ArrayList<>();
            for (Field field : cls.getFields()) {
                if (field.getInitialValue() instanceof StringEncodedValue value
                        && UPSTREAM_CERT.equals(value.getValue())) {
                    fields.add(new ImmutableField(field.getDefiningClass(), field.getName(),
                            field.getType(), field.getAccessFlags(), new ImmutableStringEncodedValue(certificate),
                            field.getAnnotations(), field.getHiddenApiRestrictions()));
                } else fields.add(field);
            }
            List<Method> methods = new ArrayList<>();
            for (Method method : cls.getMethods()) {
                MethodImplementation code = method.getImplementation();
                if (method.getName().equals("matchesAnySigningCert")) {
                    require(method.getParameterTypes().equals(List.of("[Landroid/content/pm/Signature;"))
                            && method.getReturnType().equals("Z") && code != null,
                            "Upstream certificate-check signature changed");
                    List<Instruction> instructions = new ArrayList<>();
                    for (Instruction instruction : code.getInstructions()) {
                        if (UPSTREAM_CERT.equals(string(instruction))) {
                            certLoads++;
                            int register = ((OneRegisterInstruction) instruction).getRegisterA();
                            var reference = new ImmutableStringReference(certificate);
                            // Keep the instruction width so branches, try blocks and debug addresses stay valid.
                            if (instruction.getOpcode() == Opcode.CONST_STRING) {
                                instructions.add(new ImmutableInstruction21c(Opcode.CONST_STRING, register, reference));
                            } else if (instruction.getOpcode() == Opcode.CONST_STRING_JUMBO) {
                                instructions.add(new ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, register, reference));
                            } else throw new IllegalArgumentException("Unexpected certificate instruction");
                        } else instructions.add(instruction);
                    }
                    methods.add(withCode(method, new ImmutableMethodImplementation(code.getRegisterCount(),
                            instructions, code.getTryBlocks(), code.getDebugItems())));
                } else if (method.getName().equals("getGmsCoreDownload")) {
                    require(method.getParameterTypes().isEmpty() && method.getReturnType().equals("Ljava/lang/String;")
                            && code != null, "Upstream download method signature changed");
                    downloadMethods++;
                    methods.add(withCode(method, new ImmutableMethodImplementation(1, List.of(
                            new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference(DOWNLOAD)),
                            new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), List.of(), List.of())));
                } else methods.add(method);
            }
            classes.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(),
                    cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), fields, methods));
        }
        require(targets == 1 && certLoads == 1 && downloadMethods == 1,
                "Upstream MicroG trust layout changed: classes=" + targets + ", certificates=" + certLoads
                        + ", downloads=" + downloadMethods);
        DexFile result = new ImmutableDexFile(dex.getOpcodes(), classes);
        for (ClassDef cls : result.getClasses()) if (TYPE.equals(cls.getType())) {
            for (Method method : cls.getMethods()) if (method.getImplementation() != null) {
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    require(!UPSTREAM_CERT.equals(string(instruction)), "Unadapted upstream certificate remains");
                }
            }
        }
        return result;
    }

    static Method fixtureMethod(String owner, String name, List<ImmutableMethodParameter> parameters,
                                String returns, String text) {
        List<Instruction> instructions = new ArrayList<>();
        instructions.add(new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference(text)));
        if (returns.equals("Z")) {
            instructions.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0));
            instructions.add(new ImmutableInstruction11x(Opcode.RETURN, 0));
        } else instructions.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
        return new ImmutableMethod(owner, name, parameters, returns, 10, Set.of(), Set.of(),
                new ImmutableMethodImplementation(2, instructions, List.of(), List.of()));
    }

    static DexFile fixture(String digest, boolean download) {
        List<Method> methods = new ArrayList<>();
        methods.add(fixtureMethod(TYPE, "matchesAnySigningCert",
                List.of(new ImmutableMethodParameter("[Landroid/content/pm/Signature;", Set.of(), null)), "Z", digest));
        // This method represents the package-name guard, which must remain untouched.
        methods.add(fixtureMethod(TYPE, "scanUser", List.of(), "Ljava/lang/String;", "package-name-guard"));
        if (download) methods.add(fixtureMethod(TYPE, "getGmsCoreDownload", List.of(), "Ljava/lang/String;", "old-download"));
        ClassDef target = new ImmutableClassDef(TYPE, 1, "Ljava/lang/Object;", List.of(), null, Set.of(), List.of(), methods);
        String other = "Lunrelated/Guard;";
        ClassDef unrelated = new ImmutableClassDef(other, 1, "Ljava/lang/Object;", List.of(), null, Set.of(), List.of(),
                List.of(fixtureMethod(other, "keepOriginal", List.of(), "Ljava/lang/String;", UPSTREAM_CERT)));
        return new ImmutableDexFile(Opcodes.forDexVersion(38), List.of(target, unrelated));
    }

    static void selfTest() throws Exception {
        String pin = "7cee829c140e3ba32767e541e98d99a87214b80c7b3095692549131a2d6ebf02";
        File output = Files.createTempFile("microg-trust-test-", ".dex").toFile();
        try {
            DexFileFactory.writeDexFile(output.toString(), derive(fixture(UPSTREAM_CERT, true), pin));
            DexFile roundTrip = DexFileFactory.loadDexFile(output, null);
            int pins = 0, preserved = 0, downloads = 0, guards = 0;
            for (ClassDef cls : roundTrip.getClasses()) for (Method method : cls.getMethods()) {
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    String value = string(instruction);
                    if (pin.equals(value)) pins++;
                    if (UPSTREAM_CERT.equals(value) && !TYPE.equals(cls.getType())) preserved++;
                    if (DOWNLOAD.equals(value)) downloads++;
                    if ("package-name-guard".equals(value)) guards++;
                }
            }
            require(pins == 1 && preserved == 1 && downloads == 1 && guards == 1,
                    "Certificate scope, package guard or DEX round-trip regression");
            int rejected = 0;
            for (DexFile invalid : List.of(fixture("unknown-upstream-key", true), fixture(UPSTREAM_CERT, false), roundTrip)) {
                try { derive(invalid, pin); } catch (IllegalArgumentException expected) { rejected++; }
            }
            for (String invalid : List.of("", "0".repeat(64), UPSTREAM_CERT)) {
                try { derive(fixture(UPSTREAM_CERT, true), invalid); } catch (IllegalArgumentException expected) { rejected++; }
            }
            require(rejected == 6, "Unknown layout or invalid trust configuration was accepted");
        } finally { Files.deleteIfExists(output.toPath()); }
        System.out.println("MicroG trust regression checks passed (DEX round-trip, scope, 6 rejection cases)");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        require(args.length == 3, "Usage: CoexistenceMicroG input.mpe output.mpe certificateSha256");
        require(!new File(args[1]).exists(), "Output must be new");
        DexFile result = derive(DexFileFactory.loadDexFile(new File(args[0]), null), args[2]);
        DexFileFactory.writeDexFile(args[1], result);
        DexFile parsed = DexFileFactory.loadDexFile(new File(args[1]), null);
        require(parsed.getClasses().size() == result.getClasses().size(), "DEX class count changed on write");
        System.out.println("Derived MicroG runtime trust; package and certificate checks retained");
    }
}

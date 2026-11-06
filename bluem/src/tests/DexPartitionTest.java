import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real DEX round trips at the method-index boundary from Blue M run 42285121411. */
public final class DexPartitionTest {
    static final Opcodes OPCODES = Opcodes.forApi(28);
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    static ClassDef type(String name, int references, boolean hook) {
        MutableMethodImplementation body = new MutableMethodImplementation(0);
        for (int i = 0; i < references; i++) {
            body.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                new ImmutableMethodReference("LExternal;", "m" + i, List.of(), "V")));
        }
        if (hook) body.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
            new ImmutableMethodReference("Lcom/sylong/bluem/update/StandaloneUpdates;", "attach", List.of(), "V")));
        body.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        Method method = new ImmutableMethod(name, "run", List.of(), "V", 9, Set.of(), Set.of(), body);
        return new ImmutableClassDef(name, 1, "Ljava/lang/Object;", List.of(), null, List.of(), List.of(), List.of(method));
    }
    static DexPool pool(List<ClassDef> classes) {
        DexPool pool = new DexPool(OPCODES);
        for (ClassDef cls : classes) pool.internClass(cls);
        return pool;
    }
    static Map<String, ClassDef> read(Path out, String... names) throws Exception {
        Map<String, ClassDef> classes = new HashMap<>();
        for (String name : names) {
            DexBackedDexFile dex;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(out.resolve(name)))) {
                dex = DexBackedDexFile.fromInputStream(OPCODES, in);
            }
            check(dex.getMethodSection().size() <= 65536, "method IDs fit their unsigned short indexes");
            for (ClassDef cls : dex.getClasses())
                check(classes.put(cls.getType(), cls) == null, "no duplicate class " + cls.getType());
        }
        return classes;
    }
    static void references(ClassDef cls, int expected, boolean hook) {
        Set<String> refs = new HashSet<>();
        for (Method method : cls.getMethods()) for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction) {
                MethodReference ref = (MethodReference)((ReferenceInstruction)instruction).getReference();
                refs.add(ref.getDefiningClass() + "->" + ref.getName());
            }
        }
        Set<String> wanted = new HashSet<>();
        for (int i = 0; i < expected; i++) wanted.add("LExternal;->m" + i);
        if (hook) wanted.add("Lcom/sylong/bluem/update/StandaloneUpdates;->attach");
        check(refs.equals(wanted), "all original references and the startup hook survive serialization");
    }
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        for (int refs : new int[]{65533, 65534}) {
            List<ClassDef> base = List.of(type("LDense;", refs, false), type("LHook;", 0, false));
            check(pool(base).getMethodReferences().size() == refs + 2, "fixture has exact boundary size");
            check(DexPartition.write(out, "classes2.dex", OPCODES, base, 10) == 10, "valid boundary stays in one DEX");
            Map<String, ClassDef> loaded = read(out, "classes2.dex");
            check(loaded.size() == 2, "both original classes retained");
            references(loaded.get("LDense;"), refs, false);
        }
        List<ClassDef> patched = List.of(type("LDense;", 65534, false), type("LHook;", 0, true));
        check(pool(patched).getMethodReferences().size() == 65537 && pool(patched).hasOverflowed(),
              "one new startup reference overflows the previously valid DEX");
        Files.writeString(out.resolve("classes9.dex"), "unchanged upstream DEX");
        int next = DexPartition.write(out, "classes2.dex", OPCODES, patched, 10);
        check(next == 11, "overflow uses the first index beyond every upstream DEX");
        List<ClassDef> second = List.of(type("LDense2;", 65534, false), type("LHook2;", 0, true));
        check(DexPartition.write(out, "classes7.dex", OPCODES, second, next) == 12,
              "a second overflowing DEX cannot overwrite the first spill");
        check(Files.readString(out.resolve("classes9.dex")).equals("unchanged upstream DEX"), "upstream DEX not overwritten");
        Map<String, ClassDef> loaded = read(out, "classes2.dex", "classes7.dex", "classes10.dex", "classes11.dex");
        check(loaded.keySet().equals(Set.of("LDense;", "LHook;", "LDense2;", "LHook2;")), "no class lost when splitting");
        references(loaded.get("LDense;"), 65534, false);
        references(loaded.get("LDense2;"), 65534, false);
        references(loaded.get("LHook;"), 0, true);
        references(loaded.get("LHook2;"), 0, true);
        try {
            DexPartition.write(out, "too-large.dex", OPCODES, List.of(type("LTooLarge;", 65536, false)), 12);
            throw new AssertionError("An indivisible oversized class must fail closed");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("LTooLarge;"), "incompatible single class identified");
        }
        check(!Files.exists(out.resolve("too-large.dex")), "no invalid DEX emitted");
        System.out.println("DexPartitionTest: " + checks + " boundary, split, hook and preservation checks PASS");
    }
}

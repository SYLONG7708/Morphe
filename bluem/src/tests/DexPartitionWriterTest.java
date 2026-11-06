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
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real DEX serialization at the 16-bit boundary, including an added update hook. */
public final class DexPartitionWriterTest {
    static final Opcodes OPCODES = Opcodes.forApi(28);

    static ClassDef fixture(String name, int references, boolean hook) {
        MutableMethodImplementation body = new MutableMethodImplementation(0);
        for (int i = 0; i < references; i++) {
            body.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                    new ImmutableMethodReference("LTarget;", name + i, List.of(), "V")));
        }
        if (hook) body.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                new ImmutableMethodReference("LUpdateHook;", "attach", List.of(), "V")));
        body.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        Method method = new ImmutableMethod("L" + name + ";", "run", List.of(), "V", 9,
                Set.of(), Set.of(), body);
        return new ImmutableClassDef("L" + name + ";", 1, "Ljava/lang/Object;", List.of(),
                null, Set.of(), List.of(), List.of(method));
    }

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void verify(Path file, ClassDef... expected) throws Exception {
        DexBackedDexFile dex;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            dex = DexBackedDexFile.fromInputStream(OPCODES, in);
        }
        require(dex.getMethodSection().size() <= 65536, "Method table overflow");
        Map<String, ClassDef> classes = new HashMap<>();
        for (ClassDef cls : dex.getClasses()) require(classes.put(cls.getType(), cls) == null, "Duplicate class");
        require(classes.size() == expected.length, "Class lost or duplicated");
        for (ClassDef original : expected) {
            ClassDef actual = classes.get(original.getType());
            require(actual != null, "Missing class " + original.getType());
            Iterator<? extends Method> methods = actual.getMethods().iterator();
            for (Method method : original.getMethods()) {
                require(methods.hasNext(), "Missing method");
                Method written = methods.next();
                require(method.getName().equals(written.getName()), "Changed method");
                Iterator<? extends Instruction> instructions = written.getImplementation().getInstructions().iterator();
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    require(instructions.hasNext(), "Missing instruction");
                    Instruction saved = instructions.next();
                    require(instruction.getOpcode() == saved.getOpcode(), "Changed opcode");
                    if (instruction instanceof ReferenceInstruction) require(
                            ((ReferenceInstruction) instruction).getReference().equals(((ReferenceInstruction) saved).getReference()),
                            "Changed method reference or lost hook");
                }
                require(!instructions.hasNext(), "Extra instruction");
            }
            require(!methods.hasNext(), "Extra method");
        }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        ClassDef first = fixture("A", 32767, false);
        ClassDef second = fixture("B", 32767, false);
        int next = DexPartitionWriter.write(out, "boundary.dex", 4, OPCODES, List.of(first, second));
        require(next == 4, "Exactly 65536 methods must fit without splitting");
        verify(out.resolve("boundary.dex"), first, second);

        ClassDef hooked = fixture("B", 32767, true);
        DexPool overflowing = new DexPool(OPCODES);
        overflowing.internClass(first);
        overflowing.internClass(hooked);
        require(overflowing.hasOverflowed(), "Fixture must reproduce the added-hook overflow");
        byte[] untouched = {1, 2, 3};
        Files.write(out.resolve("classes3.dex"), untouched);
        next = DexPartitionWriter.write(out, "classes.dex", next, OPCODES, List.of(first, hooked));
        require(next == 5, "One overflow DEX expected");
        verify(out.resolve("classes.dex"), first);
        verify(out.resolve("classes4.dex"), hooked);
        next = DexPartitionWriter.write(out, "classes2.dex", next, OPCODES,
                List.of(fixture("C", 32767, false), fixture("D", 32767, true)));
        require(next == 6, "Overflow indexes must advance across original DEX files");
        verify(out.resolve("classes2.dex"), fixture("C", 32767, false));
        verify(out.resolve("classes5.dex"), fixture("D", 32767, true));
        require(Arrays.equals(untouched, Files.readAllBytes(out.resolve("classes3.dex"))), "Unchanged DEX overwritten");

        ClassDef tooLarge = fixture("TooLarge", 65536, false);
        for (List<ClassDef> classes : List.of(List.of(tooLarge), List.of(first, tooLarge))) {
            try {
                DexPartitionWriter.write(out, "invalid.dex", next, OPCODES, classes);
                throw new AssertionError("An indivisible oversized class must fail");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().contains("Class exceeds a DEX reference limit"), "Unexpected failure");
            }
        }
        System.out.println("DexPartitionWriterTest: exact limit, added hook, multiple DEX files, instruction preservation, oversized class PASS");
    }
}

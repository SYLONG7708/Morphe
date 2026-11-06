import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.IOException;
import java.nio.file.Path;

/** Keep whole classes together when added hooks exhaust a DEX reference table. */
public final class DexPartitionWriter {
    static int write(Path directory, String originalName, int nextDexIndex,
                     Opcodes opcodes, Iterable<? extends ClassDef> classes) throws IOException {
        DexPool pool = new DexPool(opcodes);
        String name = originalName;
        int count = 0;
        for (ClassDef cls : classes) {
            pool.mark();
            pool.internClass(cls);
            if (pool.hasOverflowed()) {
                if (count == 0) throw tooLarge(cls);
                pool.reset();
                writePool(pool, directory.resolve(name));
                name = "classes" + nextDexIndex++ + ".dex";
                pool = new DexPool(opcodes);
                pool.internClass(cls);
                if (pool.hasOverflowed()) throw tooLarge(cls);
                count = 0;
            }
            count++;
        }
        writePool(pool, directory.resolve(name));
        return nextDexIndex;
    }

    private static IllegalStateException tooLarge(ClassDef cls) {
        return new IllegalStateException("Class exceeds a DEX reference limit: " + cls.getType());
    }

    private static void writePool(DexPool pool, Path path) throws IOException {
        FileDataStore store = new FileDataStore(path.toFile());
        try { pool.writeTo(store); } finally { store.close(); }
    }
}

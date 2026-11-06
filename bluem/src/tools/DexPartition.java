import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.IOException;
import java.nio.file.Path;

/** Keep every class when new hook references overflow an upstream DEX index. */
public final class DexPartition {
    static int write(Path out, String name, Opcodes opcodes,
                     Iterable<? extends ClassDef> classes, int nextDex) throws IOException {
        DexPool pool = new DexPool(opcodes);
        int count = 0;
        for (ClassDef cls : classes) {
            pool.mark();
            pool.internClass(cls);
            if (pool.hasOverflowed()) {
                pool.reset();
                if (count == 0) throw new IllegalStateException("Class exceeds DEX index limits: " + cls.getType());
                writePool(pool, out.resolve(name));
                pool = new DexPool(opcodes);
                pool.internClass(cls);
                if (pool.hasOverflowed()) throw new IllegalStateException("Class exceeds DEX index limits: " + cls.getType());
                name = "classes" + nextDex++ + ".dex";
                count = 0;
            }
            count++;
        }
        if (count > 0) writePool(pool, out.resolve(name));
        return nextDex;
    }

    private static void writePool(DexPool pool, Path path) throws IOException {
        FileDataStore store = new FileDataStore(path.toFile());
        try { pool.writeTo(store); } finally { store.close(); }
    }
}

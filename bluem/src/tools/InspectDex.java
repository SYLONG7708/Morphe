import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
public class InspectDex {
 public static void main(String[] args) throws Exception {
  try (ZipFile z = new ZipFile(args[0])) {
   for (ZipEntry e : Collections.list(z.entries())) {
    if (!e.getName().matches("classes[0-9]*\\.dex")) continue;
    DexBackedDexFile d = DexBackedDexFile.fromInputStream(null,new BufferedInputStream(z.getInputStream(e)));
    for (ClassDef c : d.getClasses()) {
     if (!c.getType().matches(args[1])) continue;
     System.out.println(c.getType());
     for (Method m:c.getMethods()) {
      System.out.println("  "+m.getName()+m.getParameterTypes()+m.getReturnType());
      if (args.length < 3 || !m.getName().matches(args[2]) || m.getImplementation()==null) continue;
      for (Instruction i:m.getImplementation().getInstructions()) {
       String s=i.getOpcode().name;
       if(i instanceof ReferenceInstruction) s+=" "+((ReferenceInstruction)i).getReference();
       if(i instanceof WideLiteralInstruction) s+=" #"+((WideLiteralInstruction)i).getWideLiteral();
       System.out.println("    "+s);
      }
     }
    }
   }
  }
 }
}

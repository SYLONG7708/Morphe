import com.reandroid.arsc.chunk.xml.*;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.*;import java.util.*;import java.util.zip.*;

/** Checks the actual packaged hooks, including the no-browser/no-exit startup path. */
public final class CheckUpdateContract {
 static void require(boolean yes,String message){if(!yes)throw new IllegalStateException(message);}
 public static void main(String[] args)throws Exception{
  Map<String,String> routes=Map.of("open","Lcom/sylong/bluem/update/StartupSupport;->open",
    "checkForMicroGUpdate","Lcom/sylong/bluem/update/StandaloneUpdates;->checkMicroG",
    "openGmsCoreDisableBatteryOptimizationsIntent","Lcom/sylong/bluem/update/StartupSupport;->openBatterySettings");
  int checked=0,mainHooks=0,tlsHooks=0;boolean setup=false,liveClass=false;
  try(ZipFile zip=new ZipFile(args[0])){
   for(String service:List.of("com.google.protobuf.GeneratedExtensionRegistryLoader","io.grpc.LoadBalancerProvider","io.grpc.NameResolverProvider","kotlinx.coroutines.CoroutineExceptionHandler","kotlinx.coroutines.internal.MainDispatcherFactory"))
    require(zip.getEntry("META-INF/services/"+service)!=null,"Original service loader entry missing: "+service);
   for(ZipEntry e:Collections.list(zip.entries())){
    if(!e.getName().matches("classes[0-9]*\\.dex"))continue;
    DexBackedDexFile dex=DexBackedDexFile.fromInputStream(null,new BufferedInputStream(zip.getInputStream(e)));
    for(ClassDef c:dex.getClasses()){
     boolean tlsGuard=PatchApk.isTlsGuard(c);
     if(c.getType().equals("Lcom/sylong/bluem/update/SetupActivity;"))setup=true;
     if(c.getType().equals("Lcom/sylong/bluem/live/LiveResolveService;"))liveClass=true;
     for(Method m:c.getMethods()){
      if(m.getImplementation()==null)continue;
      if(tlsGuard)for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction&&((ReferenceInstruction)i).getReference() instanceof MethodReference){
       MethodReference ref=(MethodReference)((ReferenceInstruction)i).getReference();
       require(!(ref.getDefiningClass().equals("Ljavax/net/ssl/SSLContext;")&&ref.getName().equals("getInstance")&&ref.getParameterTypes().toString().equals("[Ljava/lang/String;]")),"Recursive TLS lookup remains");
       if(ref.getDefiningClass().equals("Lcom/sylong/bluem/update/TlsCompatibility;")&&ref.getName().equals("context"))tlsHooks++;
      }
      if(c.getType().equals(PatchApk.GMS)&&routes.containsKey(m.getName())){
       int count=0;String target=null;
       for(Instruction i:m.getImplementation().getInstructions()){
        count++;
        if(i instanceof ReferenceInstruction && ((ReferenceInstruction)i).getReference() instanceof MethodReference){
         MethodReference ref=(MethodReference)((ReferenceInstruction)i).getReference();target=ref.getDefiningClass()+"->"+ref.getName();
        }
       }
       require(count==2&&routes.get(m.getName()).equals(target),"Unsafe original MicroG startup method remains: "+m.getName());checked++;
      }
      if(c.getType().equals(PatchApk.MAIN)&&m.getName().equals("onCreate")){
       for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction && ((ReferenceInstruction)i).getReference() instanceof MethodReference){
        MethodReference ref=(MethodReference)((ReferenceInstruction)i).getReference();
        if(ref.getDefiningClass().equals("Lcom/sylong/bluem/update/StandaloneUpdates;")&&ref.getName().equals("attach"))mainHooks++;
       }
      }
     }
    }
   }
   AndroidManifestBlock manifest=AndroidManifestBlock.load(zip.getInputStream(zip.getEntry("AndroidManifest.xml")));
   int qa=0,liveServices=0;boolean revision=false,setupManifest=false,liveMetadata=false;
   Iterator<?> children=manifest.getApplicationElement().getElements();
   while(children.hasNext()){
    ResXmlElement element=(ResXmlElement)children.next();ResXmlAttribute name=element.searchAttributeByResourceId(android.R.attr.name);
    if(name==null)continue;String n=name.getValueAsString();
    if("com.sylong.bluem.update.QaReceiver".equals(n))qa++;
    if("com.sylong.bluem.UPDATE_CLIENT_REVISION".equals(n))revision="11".equals(element.searchAttributeByResourceId(android.R.attr.value).getValueAsString());
    if("com.sylong.bluem.LIVE_BRIDGE".equals(n))liveMetadata="1".equals(element.searchAttributeByResourceId(android.R.attr.value).getValueAsString());
    if("com.sylong.bluem.live.LiveResolveService".equals(n)){
     liveServices++;require(element.searchAttributeByResourceId(android.R.attr.exported).getData()!=0,"Partner service must support explicit binding");
    }
    if("com.sylong.bluem.update.SetupActivity".equals(n)){
     setupManifest=true;require(element.searchAttributeByResourceId(android.R.attr.exported).getData()==0,"Setup activity must be private");
    }
   }
   require(qa==(Boolean.parseBoolean(args[1])?1:0),"QA receiver exposure mismatch");
   require(revision&&setupManifest&&setup&&checked==3&&mainHooks==1&&tlsHooks==2,"Incomplete startup/update/TLS contract");
   require(liveClass&&liveMetadata&&liveServices==1,"Incomplete authenticated TV live bridge");
  }
  System.out.println("In-app update contract PASS: 3 native routes, 2 safe TLS backends, no browser/exit, one startup hook, private setup, revision 11, original services, authenticated TV live bridge, QA boundary");
 }
}

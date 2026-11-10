import com.reandroid.arsc.chunk.xml.*;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
import java.util.Iterator;

/** Adds only the explicitly addressed partner service; no activity or private display hooks. */
public final class PatchLiveBridgeManifest {
    static void apply(AndroidManifestBlock manifest, boolean qa) {
        ResXmlElement app=manifest.getApplicationElement();
        for(Iterator<?> children=app.getElements();children.hasNext();) {
            ResXmlElement child=(ResXmlElement)children.next();
            ResXmlAttribute name=child.searchAttributeByResourceId(android.R.attr.name);
            if(name!=null && "com.sylong.bluem.UPDATE_CLIENT_REVISION".equals(name.getValueAsString()))
                child.getOrCreateAndroidAttribute("value",android.R.attr.value).setValueAsString("11");
        }
        ResXmlElement service=app.newElement("service");
        service.getOrCreateAndroidAttribute("name",android.R.attr.name).setValueAsString("com.sylong.bluem.live.LiveResolveService");
        service.getOrCreateAndroidAttribute("exported",android.R.attr.exported).setValueAsBoolean(true);
        ResXmlElement meta=app.newElement("meta-data");
        meta.getOrCreateAndroidAttribute("name",android.R.attr.name).setValueAsString("com.sylong.bluem.LIVE_BRIDGE");
        meta.getOrCreateAndroidAttribute("value",android.R.attr.value).setValueAsString("1");
        ResXmlElement queries=manifest.getManifestElement().newElement("queries");
        String[] partners=qa?new String[]{"tw.com.sylong.tvcar","tw.com.sylong.tvcar.direct"}:new String[]{"tw.com.sylong.tvcar"};
        for(String pkg:partners) {
            ResXmlElement item=queries.newElement("package");item.getOrCreateAndroidAttribute("name",android.R.attr.name).setValueAsString(pkg);
        }
    }
    public static void main(String[] args) throws Exception {
        try(ZipFile zip=new ZipFile(args[0])) {
            AndroidManifestBlock manifest=AndroidManifestBlock.load(zip.getInputStream(zip.getEntry("AndroidManifest.xml")));
            if(!"app.morphe.android.youtube".equals(manifest.getPackageName())) throw new IllegalArgumentException("Unexpected package");
            apply(manifest,args.length>2 && Boolean.parseBoolean(args[2]));
            manifest.refreshFull();
            try(OutputStream out=Files.newOutputStream(Path.of(args[1]))) {manifest.writeBytes(out);}
        }
    }
}

import com.sylong.bluem.update.TlsCompatibility;
import java.io.*;import java.net.*;import java.security.*;import javax.net.ssl.*;import com.sun.net.httpserver.*;
public class TlsHandshakeTest {
 public static void main(String[] args)throws Exception{
  KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream in=new FileInputStream(args[0])){keys.load(in,args[1].toCharArray());}
  KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,args[1].toCharArray());
  SSLContext serverContext=SSLContext.getInstance("TLS");serverContext.init(km.getKeyManagers(),null,null);
  HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
  server.createContext("/",exchange->{exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();
  int count=0;
  try{
   Security.insertProviderAt(new TlsCompatibilityTest.WaitingGuard(),1);
   SSLContext platform=TlsCompatibility.context("TLS");platform.init(null,null,null);
   try{request("localhost",server.getAddress().getPort(),platform);throw new AssertionError("untrusted issuer was accepted");}catch(SSLHandshakeException expected){count++;}
   KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("qa-localhost",keys.getCertificate("qa-localhost"));
   TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
   SSLContext scoped=TlsCompatibility.context("TLS");scoped.init(null,tm.getTrustManagers(),null);
   if(request("localhost",server.getAddress().getPort(),scoped)!=204)throw new AssertionError("explicit trusted localhost did not connect");count++;
   try{request("127.0.0.1",server.getAddress().getPort(),scoped);throw new AssertionError("wrong hostname was accepted");}catch(SSLHandshakeException expected){count++;}
  }finally{Security.removeProvider("Ssl_Guard");server.stop(0);}
  System.out.println("TlsHandshakeTest: "+count+" real loopback handshakes PASS (untrusted CA rejected, explicitly trusted CA accepted, wrong hostname rejected)");
 }
 static int request(String host,int port,SSLContext context)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://"+host+":"+port+"/").openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setSSLSocketFactory(context.getSocketFactory());try{return c.getResponseCode();}finally{c.disconnect();}
 }
}

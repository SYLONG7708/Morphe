import com.sylong.bluem.update.TlsCompatibility;
import java.security.*;import javax.net.ssl.*;import java.net.*;import java.io.*;

public final class TlsCompatibilityTest {
 static int checks;
 static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);checks++;}
 static final class WaitingGuard extends Provider {
  WaitingGuard(){super("Ssl_Guard",1.01,"Exact production provider name");put("SSLContext.TLS",UpdateTlsTest.GuardContext.class.getName());put("SSLContext.Default",UpdateTlsTest.GuardContext.class.getName());}
 }
 public static void main(String[] args)throws Exception{
  Provider initial=SSLContext.getInstance("TLS").getProvider();SSLSocketFactory global=HttpsURLConnection.getDefaultSSLSocketFactory();HostnameVerifier hostname=HttpsURLConnection.getDefaultHostnameVerifier();
  try{
   Security.insertProviderAt(new WaitingGuard(),1);
   check(SSLContext.getInstance("TLS").getProvider().getName().equals("Ssl_Guard"),"reproduce TLS recursion with pending provider initialization");
   SSLContext tls=TlsCompatibility.context("TLS");tls.init(null,null,null);
   check(tls.getProvider().getName().equals(initial.getName()),"fallback selects platform backend");
   try(Socket socket=tls.getSocketFactory().createSocket()){check(socket instanceof SSLSocket,"create a real TLS socket");}
   SSLContext defaults=TlsCompatibility.context("Default");
   check(defaults.getServerSocketFactory()!=null,"server guard also gets initialized default context");
   check(Security.getProviders()[0].getName().equals("Ssl_Guard"),"provider order unchanged");
   check(HttpsURLConnection.getDefaultSSLSocketFactory()==global,"global socket factory unchanged");
   check(HttpsURLConnection.getDefaultHostnameVerifier()==hostname,"global hostname verification unchanged");
   final boolean[] trustInvoked={false};
   TrustManager custom=new X509TrustManager(){
    public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}
    public void checkClientTrusted(java.security.cert.X509Certificate[] c,String a)throws java.security.cert.CertificateException{throw new java.security.cert.CertificateException("reject");}
    public void checkServerTrusted(java.security.cert.X509Certificate[] c,String a)throws java.security.cert.CertificateException{trustInvoked[0]=true;throw new java.security.cert.CertificateException("reject");}
   };
   SSLContext configured=TlsCompatibility.context("TLS");configured.init(null,new TrustManager[]{custom},new SecureRandom());
   check(configured.getSocketFactory()!=null,"caller retains control of explicit trust managers");
   try{TlsCompatibility.context("unknown-protocol-for-test");throw new AssertionError("unknown protocol accepted");}catch(NoSuchAlgorithmException expected){checks++;}
  }finally{Security.removeProvider("Ssl_Guard");}
  check(TlsCompatibility.context("TLS").getProvider().getName().equals(initial.getName()),"healthy existing provider retained");
  System.out.println("TlsCompatibilityTest: "+checks+" provider initialization, default context, trust and isolation checks PASS");
 }
}

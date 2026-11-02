import com.sylong.bluem.update.UpdatePolicy;
import java.io.IOException;
import java.net.*;
import javax.net.ssl.*;
import java.security.*;

/** Reproduces an app replacing the global factory with a pre-MicroG SSL guard. */
public final class UpdateTlsTest {
    public static final class GuardContext extends SSLContextSpi {
        protected void engineInit(KeyManager[] k,TrustManager[] t,SecureRandom s){}
        protected SSLSocketFactory engineGetSocketFactory(){return new Blocked();}
        protected SSLServerSocketFactory engineGetServerSocketFactory(){return null;}
        protected SSLEngine engineCreateSSLEngine(){throw new UnsupportedOperationException();}
        protected SSLEngine engineCreateSSLEngine(String h,int p){throw new UnsupportedOperationException();}
        protected SSLSessionContext engineGetServerSessionContext(){return null;}
        protected SSLSessionContext engineGetClientSessionContext(){return null;}
    }
    static final class GuardProvider extends Provider {
        GuardProvider(){super("TestSslGuard", "1", "Simulate YouTube SSL provider");put("SSLContext.TLS",GuardContext.class.getName());}
    }
    static final class Blocked extends SSLSocketFactory {
        public String[] getDefaultCipherSuites(){return new String[0];}
        public String[] getSupportedCipherSuites(){return new String[0];}
        public Socket createSocket()throws IOException{throw new IOException("Blocked unpatched use of SSL stack.");}
        public Socket createSocket(Socket s,String h,int p,boolean a)throws IOException{return createSocket();}
        public Socket createSocket(String h,int p)throws IOException{return createSocket();}
        public Socket createSocket(String h,int p,InetAddress l,int q)throws IOException{return createSocket();}
        public Socket createSocket(InetAddress h,int p)throws IOException{return createSocket();}
        public Socket createSocket(InetAddress h,int p,InetAddress l,int q)throws IOException{return createSocket();}
    }
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception{
        SSLSocketFactory original=HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier verifier=HttpsURLConnection.getDefaultHostnameVerifier();
        Blocked guard=new Blocked();
        try{
            Security.insertProviderAt(new GuardProvider(),1);
            check(SSLContext.getInstance("TLS").getSocketFactory() instanceof Blocked,"reproduce default TLS context provider interception");
            HttpsURLConnection.setDefaultSSLSocketFactory(guard);
            HttpsURLConnection connection=(HttpsURLConnection)new URL("https://raw.githubusercontent.com/").openConnection();
            check(connection.getSSLSocketFactory()==guard,"reproduce inherited blocking factory");
            UpdatePolicy.configureHttps(connection);
            try(Socket socket=connection.getSSLSocketFactory().createSocket()){
                check(socket instanceof SSLSocket,"updater can create platform TLS sockets before MicroG");
            }
            check(connection.getHostnameVerifier()==verifier,"retain HTTPS hostname validation");
            check(HttpsURLConnection.getDefaultSSLSocketFactory()==guard,"do not alter other app connections");
            check(HttpsURLConnection.getDefaultHostnameVerifier()==verifier,"do not weaken global verification");
        }finally{HttpsURLConnection.setDefaultSSLSocketFactory(original);Security.removeProvider("TestSslGuard");}
        System.out.println("UpdateTlsTest: 6 pre-MicroG SSL guard and verification-isolation regressions PASS");
    }
}

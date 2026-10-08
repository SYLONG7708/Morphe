import com.sylong.bluem.update.RecoveryPolicy;
public final class RecoveryPolicyTest {
    static void expect(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static RecoveryPolicy.Action s(RecoveryPolicy p, long now, long pos, boolean online, boolean paused, boolean loading) {
        return p.sample(now, "same-video", pos, true, online, paused, loading, false, 6000);
    }
    public static void main(String[] args) {
        RecoveryPolicy p = new RecoveryPolicy();
        s(p, 0, 59000, true, false, true);
        s(p, 6000, 59000, true, false, true);
        expect(s(p, 7999, 59000, true, false, true) == RecoveryPolicy.Action.NONE, "startup grace");
        expect(s(p, 8000, 59000, true, false, true) == RecoveryPolicy.Action.SWITCH, "persistent buffering");
        expect(s(p, 15000, 59000, true, false, true) == RecoveryPolicy.Action.NONE, "reload grace");
        p.sample(15500,"",-1,false,true,false,false,false,6000);
        s(p, 16000, 59000, true, false, true);
        expect(s(p, 22000, 59000, true, false, true) == RecoveryPolicy.Action.SWITCH, "same video after empty reload state");
        expect(s(p, 30000, 59000, true, false, true) == RecoveryPolicy.Action.EXHAUSTED, "bounded retries");
        expect(s(p, 50000, 59000, true, false, true) == RecoveryPolicy.Action.NONE, "no endless recovery");
        for (boolean offline : new boolean[]{true, false}) {
            p = new RecoveryPolicy();
            for (int t=0;t<120000;t+=1000)
                expect(s(p,t,59000,!offline,!offline,true)==RecoveryPolicy.Action.NONE,"offline or paused");
        }
        p = new RecoveryPolicy();
        for (int t=0;t<120000;t+=1000)
            expect(s(p,t,t,true,false,false)==RecoveryPolicy.Action.NONE,"normal playback");
        p = new RecoveryPolicy(); s(p,0,59000,true,false,true); s(p,6000,59000,true,false,true);
        expect(s(p,12000,180000,true,false,true)==RecoveryPolicy.Action.NONE,"seek resets stall");
        expect(s(p,16000,180000,true,false,true)==RecoveryPolicy.Action.NONE,"seek grace");
        p = new RecoveryPolicy();
        for(int t=0;t<30000;t+=1000)
            expect(p.sample(t,"same",0,false,true,false,true,false,6000)==RecoveryPolicy.Action.NONE,"no active player");
        System.out.println("RecoveryPolicyTest: startup, buffering, seek, pause, offline, progress, retry limit PASS");
    }
}

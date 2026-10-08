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
        expect(s(p, 8000, 59000, true, false, true) == RecoveryPolicy.Action.REFRESH, "refresh before changing source");
        expect(s(p, 15000, 59000, true, false, true) == RecoveryPolicy.Action.NONE, "reload grace");
        p.sample(15500,"",-1,false,true,false,false,false,6000);
        s(p, 16000, 59000, true, false, true);
        expect(s(p, 22000, 59000, true, false, true) == RecoveryPolicy.Action.SWITCH, "same video after empty reload state");
        expect(s(p, 30000, 59000, true, false, true) == RecoveryPolicy.Action.SWITCH, "second source fallback");
        expect(s(p, 38000, 59000, true, false, true) == RecoveryPolicy.Action.EXHAUSTED, "bounded retries");
        expect(s(p, 50000, 59000, true, false, true) == RecoveryPolicy.Action.NONE, "backoff after failed episode");
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
        p = new RecoveryPolicy();
        expect(error(p,0,2267000,true)==RecoveryPolicy.Action.NONE,"explicit error debounce");
        expect(error(p,2000,0,true)==RecoveryPolicy.Action.REFRESH,"error at 37:47 cannot be hidden by position reset");
        expect(error(p,10000,0,true)==RecoveryPolicy.Action.SWITCH,"error first fallback");
        expect(error(p,18000,0,true)==RecoveryPolicy.Action.SWITCH,"error second fallback");
        expect(error(p,26000,0,true)==RecoveryPolicy.Action.EXHAUSTED,"error retry ceiling");
        expect(error(p,85000,0,true)==RecoveryPolicy.Action.NONE,"error cooldown");
        error(p,86000,0,true);
        expect(error(p,88000,0,true)==RecoveryPolicy.Action.REFRESH,"recover after cooldown without relaunch");
        error(p,96000,0,true); error(p,104000,0,true);
        expect(error(p,112000,0,true)==RecoveryPolicy.Action.EXHAUSTED,"rolling six per ten minutes");
        expect(error(p,120000,0,false)==RecoveryPolicy.Action.NONE,"offline no reload");
        error(p,122000,0,true);
        expect(error(p,124000,0,true)==RecoveryPolicy.Action.EXHAUSTED,"network flaps do not reset global budget");
        p = new RecoveryPolicy(); error(p,0,0,true); error(p,2000,0,true);
        for(int t=3000;t<=98000;t+=1000)s(p,t,t,true,false,false);
        expect(p.attempts()==0,"long healthy playback opens a new recovery episode");
        // Preserve the same id across the healthy and subsequent failing portions.
        p.sample(100000,"same-video",98000,true,true,false,false,true,6000);
        expect(p.sample(102000,"same-video",98000,true,true,false,false,true,6000)==RecoveryPolicy.Action.REFRESH,
            "later error on a long video can recover again");
        p = new RecoveryPolicy();error(p,0,0,false);error(p,100000,0,false);
        expect(error(p,101000,0,true)==RecoveryPolicy.Action.NONE,"network return debounce");
        expect(error(p,103000,0,true)==RecoveryPolicy.Action.REFRESH,"network return resumes pending playback");
        System.out.println("RecoveryPolicyTest: buffering, explicit error, vanished position, long-video recovery, network return, backoff, rolling limit PASS");
    }
    static RecoveryPolicy.Action error(RecoveryPolicy p,long now,long position,boolean online) {
        return p.sample(now,"same-video",position,true,online,false,false,true,6000);
    }
}

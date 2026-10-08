import com.sylong.bluem.update.PlaybackSignals;
import com.sylong.bluem.update.PlaybackSignals.Failure;
public final class PlaybackSignalsTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {
        check(PlaybackSignals.watchActivity("com.google.android.apps.youtube.app.watchwhile.InternalMainActivity"),"internal playback page remains watched");
        check(!PlaybackSignals.watchActivity("com.google.android.apps.youtube.app.settings.SettingsActivity"),"settings page excluded");
        check(PlaybackSignals.failure(true,"播放時發生問題\n(播放 ID：example)\n輕觸以重試",false)==Failure.TRANSIENT,"real Chinese error without state hook");
        check(PlaybackSignals.failure(false,"播放時發生問題",false)==Failure.NONE,"hidden stale error is ignored");
        check(PlaybackSignals.failure(false,"",true)==Failure.TRANSIENT,"media session error without visible overlay");
        check(PlaybackSignals.failure(true,"No internet connection",false)==Failure.NETWORK,"network classification");
        check(PlaybackSignals.failure(true,"請登入以確認你不是機器人",true)==Failure.ACCOUNT,"login is not an automatic retry loop");
        check(PlaybackSignals.failure(true,"This video is private",true)==Failure.CONTENT,"private content is not retried");
        check(PlaybackSignals.failure(true,"影片已移除",true)==Failure.CONTENT,"removed video is not retried");
        check(PlaybackSignals.failure(true,"Video unavailable",false)==Failure.TRANSIENT,"generic unavailable may be transient");
        check(!PlaybackSignals.paused("PAUSED",2,true),"visible error overrides stale paused enum");
        check(PlaybackSignals.paused("PLAYING",2,false),"actual media pause beats stale enum");
        check(!PlaybackSignals.paused("PAUSED",3,false),"actual playback beats stale enum");
        check(PlaybackSignals.resumePosition(0,2267000,true)==2267000,"preserve last healthy long-video position");
        check(PlaybackSignals.resumePosition(0,2267000,false)==0,"intentional seek to beginning preserved");
        check(PlaybackSignals.resumePosition(-1,2267000,true)==2267000,"missing controller position preserved");
        check(!PlaybackSignals.stateError("UNRECOVERABLE_ERROR",7,true),"progress defeats an obsolete media notification error");
        check(PlaybackSignals.stateError("PLAYING",7,false),"actual media error defeats stale enum");
        check(!PlaybackSignals.activePlayback(false,false,false,true,false,true,false,true),"exhausted buffering can receive a repair update after idle grace");
        check(PlaybackSignals.activePlayback(false,true,false,true,false,true,false,true),"resumed playback still blocks installation");
        check(!PlaybackSignals.activePlayback(true,false,false,false,false,false,false,true),"paused player can update after idle grace");
        System.out.println("PlaybackSignalsTest: 21 lifecycle/error/media/position/update-idle regressions PASS");
    }
}

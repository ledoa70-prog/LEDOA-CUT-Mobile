package kr.ledoa.cut;

/** A support-query failure does not necessarily reject a recognition request. */
public final class SpeechStartGate {
    // android.speech.SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, API 33+.
    public static final int CANNOT_CHECK_SUPPORT=14;
    public interface Actions {
        boolean current();
        void start(boolean supportQueryUnavailable);
        void rejected(int code);
    }
    private final Actions actions;private boolean settled;
    public SpeechStartGate(Actions actions){this.actions=actions;}
    public synchronized void supported(){finish(0);}
    public synchronized void queryFailed(int code){finish(code);}
    private void finish(int code){
        if(settled)return;settled=true;
        if(!actions.current())return;
        if(code==0||code==CANNOT_CHECK_SUPPORT)actions.start(code==CANNOT_CHECK_SUPPORT);
        else actions.rejected(code);
    }
}

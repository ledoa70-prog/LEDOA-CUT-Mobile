package kr.ledoa.cut;
public final class SpeechStartGateTest {
    static final class Probe implements SpeechStartGate.Actions {
        boolean active=true,unchecked=false;int starts=0,failures=0,code=0;
        public boolean current(){return active;}
        public void start(boolean value){starts++;unchecked=value;}
        public void rejected(int value){failures++;code=value;}
    }
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    public static void main(String[] args){
        Probe p=new Probe();SpeechStartGate gate=new SpeechStartGate(p);gate.queryFailed(14);
        check(p.starts==1&&p.failures==0&&p.unchecked,"14 must attempt actual recognition, not claim unsupported");
        gate.queryFailed(14);gate.supported();gate.queryFailed(12);
        check(p.starts==1&&p.failures==0,"duplicate callbacks must not start twice or close an active session");
        for(int code:new int[]{1,2,5,9,12,13}){p=new Probe();gate=new SpeechStartGate(p);gate.queryFailed(code);check(p.starts==0&&p.failures==1&&p.code==code,"preserve real error "+code);}
        p=new Probe();gate=new SpeechStartGate(p);gate.supported();check(p.starts==1&&!p.unchecked,"normal supported request");
        p=new Probe();gate=new SpeechStartGate(p);p.active=false;gate.queryFailed(14);gate.supported();check(p.starts==0&&p.failures==0,"cancelled or stale request never restarts");
        System.out.println("PASS error 14, real errors, cancellation and duplicate support callbacks");
    }
}

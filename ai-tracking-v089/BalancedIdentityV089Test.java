package kr.ledoa.cut.aitest;
import java.util.*;

/** Exact regression: old v0.8.8 locked after 1.2s, and v0.8.7 switched
 * to a different person's face after the 9.5s -> 10.3s detector blackout.
 */
public final class BalancedIdentityV089Test {
    private static int checks=0;
    private static void ok(boolean pass,String label){
        checks++;if(!pass)throw new AssertionError(label);
    }
    private static float[] face(float colorOverlap){
        float[] a=new float[88];
        a[0]=colorOverlap;a[1]=1-colorOverlap;
        a[12]=colorOverlap;a[13]=1-colorOverlap;
        for(int i=24;i<88;i++)a[i]=(i%3==0?1f:-1f);
        return a;
    }
    private static FacePath.Box b(float x,int id,float[] appearance){
        return new FacePath.Box(x,.32f,.29f,.17f,id,appearance,true,false);
    }
    public static void main(String[]args){
        float[] original=face(1f),lightingShift=face(.52f);
        float similarity=FaceAppearance.score(original,lightingShift);
        ok(similarity>.63f&&similarity<.74f,
           "fixture reproduces accepted motion candidate whose color drift caused v0.8.8 lock");
        FacePath tracker=new FacePath(true);
        tracker.anchor(0,b(.31f,1,original));
        for(int t=100;t<=1100;t+=100){
            FacePath.Point tracked=tracker.step(t,Collections.singletonList(
                  b(.31f+t*.00004f,100+t,original)));
            ok(tracked.status==FacePath.Status.TRACKED,
               "normal subject continuous tracking "+t);
        }
        FacePath.Point at1200=tracker.step(1200,Collections.singletonList(
            b(.358f,204,lightingShift)));
        ok(at1200.status==FacePath.Status.TRACKED,
           "1.2 second appearance fluctuation no longer destroys all future masks");
        ok(!tracker.isIdentityLocked(),"transient lighting change cannot hard lock");
        ok(tracker.appearanceFluctuationFrames()==1,"fluctuation diagnostic increments");
        for(int t=1300;t<=2200;t+=100){
            FacePath.Point tracked=tracker.step(t,Collections.singletonList(
                  b(.358f+(t-1200)*.00003f,205+t,lightingShift)));
            ok(tracked.status==FacePath.Status.TRACKED,
               "selected person keeps coverage after appearance shift "+t);
        }
        ok(tracker.identityLockEvents()==0,"no unnecessary 1.2 second lock");
        // A user can still correct an uncertain identity without discarding
        // other manual keyframes; keep the correction API working.
        tracker.anchorManual(2400,b(.41f,55,original));
        ok(!tracker.isIdentityLocked(),"manual correction must be safe");

        FacePath differentPerson=new FacePath(true);
        differentPerson.anchor(9400,b(0f,1,original));
        ok(differentPerson.step(9500,Collections.singletonList(
            b(.01f,22,original))).status==FacePath.Status.TRACKED,
            "true chosen face still tracked before blackout");
        for(int t=9600;t<=9900;t+=100)
            differentPerson.step(t,Collections.emptyList());
        ok(!differentPerson.isIdentityLocked(),"400 ms miss alone is not a permanent lock");
        FacePath.Point noFace=differentPerson.step(10000,Collections.emptyList());
        ok(differentPerson.isIdentityLocked(),
           "500ms detector blackout locks identity against the 10.3 second stranger");
        ok(noFace.box==null,"gap cannot fabricate a face");
        FacePath.Point stranger=differentPerson.step(10300,Collections.singletonList(
            b(.18f,99,original)));
        ok(stranger.status!=FacePath.Status.TRACKED && stranger.box==null,
           "other person must never be silently accepted after long blackout");
        ok(differentPerson.identityLockEvents()==1,"exactly one long-gap lock");
        differentPerson.anchorManual(10400,b(.18f,99,original));
        ok(!differentPerson.isIdentityLocked(),
           "only explicit user intervention permits future target re-identification");
        differentPerson.reset();
        ok(differentPerson.appearanceFluctuationFrames()==0 &&
           differentPerson.identityLockEvents()==0,
           "new video resets identity diagnostics");
        System.out.println("PASS: "+checks+" v0.8.9 no-regression 1.2-second lighting drift + safe 10.3-second long-gap lock");
    }
}
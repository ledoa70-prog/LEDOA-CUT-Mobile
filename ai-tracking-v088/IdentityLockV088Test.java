package kr.ledoa.cut.aitest;
import java.util.*;
/** Identity must NEVER silently jump to a bystander after the selected face is lost. */
public final class IdentityLockV088Test {
    private static int checks=0;
    static void ok(boolean good,String description){
        checks++;if(!good)throw new AssertionError(description);
    }
    static FacePath.Box box(float x,float y,float w,float h,int id){
        return new FacePath.Box(x,y,w,h,id,null,true,false);
    }
    public static void main(String[] args){
        FacePath p=new FacePath(true);
        FacePath.Box selected=box(.30f,.32f,.30f,.18f,100);
        p.anchor(0,selected);
        FacePath.Point current=p.step(100,Collections.singletonList(
            box(.31f,.32f,.30f,.18f,100)));
        ok(current.status==FacePath.Status.TRACKED,"continue the selected person normally");
        p.step(200,Collections.emptyList());
        p.step(300,Collections.emptyList());
        ok(!p.isIdentityLocked(),"a 200ms miss can still recover normally");
        p.step(400,Collections.emptyList());
        ok(p.isIdentityLocked(),"300ms missing face locks identity");
        long reliable=p.lastReliableTimestamp();
        FacePath.Point stranger=p.step(500,Arrays.asList(
            box(.32f,.31f,.30f,.18f,20),
            box(.52f,.18f,.20f,.13f,21),
            box(.14f,.62f,.21f,.12f,22)));
        ok(stranger.box==null,"no other face can gain a mosaic in locked mode");
        ok(stranger.status==FacePath.Status.UNCERTAIN,"stranger frame requires review");
        ok(p.lastReliableTimestamp()==reliable,"new stranger cannot poison selected identity");
        FacePath.Point alsoStranger=p.step(600,Collections.singletonList(
            box(.35f,.32f,.30f,.18f,20)));
        ok(alsoStranger.box==null,"single appearance-matched bystander cannot unlock");
        ok(p.identityLockEvents()==1,"lock event counted once");
        ok(p.lockedFrames()>=2,"locked state persists across many frames");
        FacePath.Point before=p.exact(500);
        p.putContinuity(500,box(.3f,.31f,.3f,.18f,100),.93f,"FLOW");
        ok(p.exact(500)==before,"flow must not paint over a locked face");
        p.putMotionEstimate(500,box(.3f,.31f,.3f,.18f,100),.93f);
        ok(p.exact(500).box==null,"alternate motion path cannot override lock");
        p.anchorManual(650,box(.40f,.33f,.21f,.14f,100));
        ok(!p.isIdentityLocked(),"explicit user manual face selection unlocks tracker");
        ok(p.step(750,Collections.singletonList(
            box(.41f,.33f,.21f,.14f,100))).status==FacePath.Status.TRACKED,
            "chosen face can be tracked again after manual confirmation");
        FacePath huge=new FacePath(true);
        huge.anchor(0,box(.30f,.30f,.27f,.17f,7));
        FacePath.Point enlarged=huge.step(100,Collections.singletonList(
            box(0,.12f,.96f,.75f,9)));
        ok(enlarged.box==null,"suddenly enormous facial box cannot silently switch target");
        ok(huge.isIdentityLocked(),"high-risk geometry locks identity until user corrects");
        p.reset();
        ok(!p.isIdentityLocked()&&p.identityLockEvents()==0,"new video clears identity lock");
        System.out.println("PASS: "+checks+" selected-person-only and stranger-rejection checks");
    }
}
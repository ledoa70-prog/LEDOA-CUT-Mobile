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
        ok(!p.isIdentityLocked(),"300ms absence must NOT permanently lock");
        p.step(500,Collections.emptyList());
        ok(!p.isIdentityLocked(),"400ms gap still allows cautious continuity");
        long reliable=p.lastReliableTimestamp();
        FacePath.Point stranger=p.step(600,Arrays.asList(
            box(.32f,.31f,.30f,.18f,20),
            box(.52f,.18f,.20f,.13f,21),
            box(.14f,.62f,.21f,.12f,22)));
        ok(stranger.box==null,"no other face can gain a mosaic in locked mode");
        ok(stranger.status==FacePath.Status.UNCERTAIN,"stranger frame requires review");
        ok(p.lastReliableTimestamp()==reliable,"new stranger cannot poison selected identity");
        FacePath.Point alsoStranger=p.step(700,Collections.singletonList(
            box(.35f,.32f,.30f,.18f,20)));
        ok(alsoStranger.box==null,"single appearance-matched bystander cannot unlock");
        ok(p.identityLockEvents()==1,"lock event counted once");
        ok(p.lockedFrames()>=2,"locked state persists across many frames");
        FacePath.Point before=p.exact(600);
        p.putContinuity(600,box(.3f,.31f,.3f,.18f,100),.93f,"FLOW");
        ok(p.exact(600)==before,"flow must not paint over a locked face");
        p.putMotionEstimate(600,box(.3f,.31f,.3f,.18f,100),.93f);
        ok(p.exact(600).box==null,"alternate motion path cannot override lock");
        p.anchorManual(750,box(.40f,.33f,.21f,.14f,100));
        ok(!p.isIdentityLocked(),"explicit user manual face selection unlocks tracker");
        ok(p.step(850,Collections.singletonList(
            box(.41f,.33f,.21f,.14f,100))).status==FacePath.Status.TRACKED,
            "chosen face can be tracked again after manual confirmation");
        FacePath huge=new FacePath(true);
        huge.anchor(0,box(.30f,.30f,.27f,.17f,7));
        FacePath.Point enlarged=huge.step(100,Collections.singletonList(
            box(0,.12f,.96f,.75f,9)));
        ok(enlarged.box==null,"suddenly enormous facial box cannot silently switch target");
        ok(!huge.isIdentityLocked(),"one bad detector box must NOT poison the remaining video");
        p.reset();
        ok(!p.isIdentityLocked()&&p.identityLockEvents()==0,"new video clears identity lock");
        System.out.println("PASS: "+checks+" selected-person-only and stranger-rejection checks");
    }
}
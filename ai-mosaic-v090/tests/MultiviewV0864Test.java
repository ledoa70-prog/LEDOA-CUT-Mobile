package kr.ledoa.cut.aitest;
import java.util.*;
public final class MultiviewV0864Test {
    private static int checks;
    static void ok(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static float[] sig(double angle){
        float[] s=new float[88];
        s[0]=1;s[12]=1;
        s[24]=(float)Math.cos(angle);s[25]=(float)Math.sin(angle);
        return s;
    }
    static FacePath.Box box(float x,float y,float[] id){
        return new FacePath.Box(x,y,.2f,.2f,12,id);
    }
    static FacePath seed(boolean withFlow){
        float[] start=sig(0),profile=sig(Math.acos(.45));
        FacePath f=new FacePath();
        f.anchor(0,box(.20f,.22f,start));
        ok(f.step(100,List.of(box(.21f,.22f,start))).status==FacePath.Status.TRACKED,"0.1 sec original");
        ok(f.step(200,List.of(box(.22f,.22f,profile))).status==FacePath.Status.TRACKED,"legit pose during tracking");
        for(int t=300;t<=500;t+=100)f.step(t,Collections.emptyList());
        if(withFlow)ok(f.putContinuity(500,box(.25f,.22f,profile),.95f,
            "FORWARD_HEAD_MOTION_REVIEW").status==FacePath.Status.FLOW_ESTIMATED,"motion corroboration");
        return f;
    }
    static void recoveredPose(){
        FacePath f=seed(true);
        float[] turned=sig(Math.acos(-.90));
        ok(FaceAppearance.score(sig(0),turned)<.73f,"changed appearance misses old .73 threshold");
        ok(FaceAppearance.score(sig(Math.acos(.45)),turned)>.79f,"second trusted view supports recovery");
        for(int t:new int[]{600,700}){
            FacePath.Point p=f.step(t,List.of(box(.27f,.22f,turned)));
            ok(p.status!=FacePath.Status.TRACKED,"first two frames remain unconfirmed");
        }
        FacePath.Point p=f.step(800,List.of(box(.28f,.22f,turned)));
        ok(p.status==FacePath.Status.TRACKED,"3-frame independently guided recovered face");
        ok("FLOW_GUIDED_REACQUIRED_REVIEW".equals(p.reason),"recovery remains review-required");
        ok(f.flowGuidedRecoveryCount()==1,"counter increments");
        float[] evidence=f.similarityEvidence(800);
        ok(evidence!=null&&evidence[0]<.73f&&evidence[1]>.79f,"diagnostics explain old false-negative");
    }
    static void noFlowStaysUntrusted(){
        FacePath f=seed(false);
        float[] turned=sig(Math.acos(-.90));
        for(int t:new int[]{600,700,800}){
            FacePath.Point p=f.step(t,List.of(box(.27f,.22f,turned)));
            ok(p.status!=FacePath.Status.TRACKED,"appearance alone cannot change identity");
        }
    }
    static void distantGeometryStaysUntrusted(){
        FacePath f=seed(true);
        float[] turned=sig(Math.acos(-.90));
        for(int t:new int[]{600,700,800}){
            FacePath.Point p=f.step(t,List.of(box(.76f,.74f,turned)));
            ok(p.status!=FacePath.Status.TRACKED,"background face cannot use previous motion");
        }
    }
    static void resetEvidence(){
        FacePath f=seed(true);
        f.reset();
        ok(f.similarityEvidence(100)==null,"clears per-video evidence");
        ok(f.flowGuidedRecoveryCount()==0,"reset counts");
    }
    public static void main(String[] args){
        recoveredPose();noFlowStaysUntrusted();distantGeometryStaysUntrusted();resetEvidence();
        System.out.println("PASS: "+checks+" v0.8.6.4 multiview/flow privacy regressions");
    }
}
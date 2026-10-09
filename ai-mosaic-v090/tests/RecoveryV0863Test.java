package kr.ledoa.cut.aitest;
import java.util.*;
public final class RecoveryV0863Test {
  static int checks;
  static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
  static float[] ref(){
    int[] x=new int[576];
    for(int i=0;i<x.length;i++){
      int v=(i*23+(i%24)*7)%130;
      x[i]=0xff000000|((70+v)<<16)|((90+v)<<8)|(60+v);
    }
    return FaceAppearance.describe(x,24,24);
  }
  static float[] mediumMatch(float[] a){
    for(int k=1;k<=400;k++){
      float theta=(float)(Math.PI*k/400.0);
      float[] b=a.clone();
      for(int i=24;i<a.length;i++)b[i]=
        (float)(Math.cos(theta)*a[i]+Math.sin(theta)*a[24+(i-24+19)%64]);
      float score=FaceAppearance.score(a,b);
      if(score>.737f && score<.765f)return b;
    }
    throw new AssertionError("cannot synthesize intermediate descriptor");
  }
  static FacePath.Box box(float x,float y,float w,float h,float[] v){return new FacePath.Box(x,y,w,h,12,v);}
  static FacePath seeded(float[] id,boolean withFlow){
    FacePath p=new FacePath();p.anchor(0,box(.2f,.22f,.2f,.2f,id));
    check(p.step(100,List.of(box(.22f,.22f,.2f,.2f,id))).status==FacePath.Status.TRACKED,"start trusted");
    for(int t=200;t<=400;t+=100)p.step(t,Collections.emptyList());
    if(withFlow)check(p.putContinuity(400,box(.25f,.23f,.2f,.2f,id),.95f,"FORWARD_HEAD_MOTION_REVIEW").status==FacePath.Status.FLOW_ESTIMATED,"flow provisional");
    return p;
  }
  static void mediumSupported(){
    float[] id=ref(),turned=mediumMatch(id);FacePath p=seeded(id,true);
    for(int t=500;t<=600;t+=100){
      FacePath.Point n=p.step(t,List.of(box(.27f,.23f,.2f,.2f,turned)));
      check(n.status!=FacePath.Status.TRACKED,"do not trust first two medium matches");
    }
    FacePath.Point approved=p.step(700,List.of(box(.28f,.23f,.2f,.2f,turned)));
    check(approved.status==FacePath.Status.TRACKED,"flow+three detections restore valid changing face");
    check("FLOW_GUIDED_REACQUIRED_REVIEW".equals(approved.reason),"review reason retained");
    check(p.flowGuidedRecoveryCount()==1,"flow guided metric increments");
    check(p.reviewCount()>=5,"flow guided recovery remains review flagged");
  }
  static void withoutFlowCannotApprove(){
    float[] id=ref(),changed=mediumMatch(id);FacePath p=seeded(id,false);
    for(int t=500;t<=700;t+=100){
      FacePath.Point n=p.step(t,List.of(box(.27f,.23f,.2f,.2f,changed)));
      check(n.status!=FacePath.Status.TRACKED,"no motion evidence: keep uncertain");
    }
    check(p.flowGuidedRecoveryCount()==0,"no flow guided recovery count");
  }
  static void wrongAreaRejected(){
    float[] id=ref(),changed=mediumMatch(id);FacePath p=seeded(id,true);
    p.step(500,List.of(box(.27f,.23f,.40f,.36f,changed)));
    p.step(600,List.of(box(.27f,.23f,.40f,.36f,changed)));
    FacePath.Point q=p.step(700,List.of(box(.27f,.23f,.40f,.36f,changed)));
    check(q.status!=FacePath.Status.TRACKED,"bad scale/flow mismatch must never be trusted");
  }
  static void noIdentityDriftFromFlow(){
    float[] id=ref(),changed=mediumMatch(id);FacePath p=seeded(id,true);
    for(int t=500;t<=700;t+=100)p.step(t,List.of(box(.27f,.23f,.2f,.2f,changed)));
    check(p.lastReliableTimestamp()==700,"new trusted sample timestamp");
    check(p.step(800,List.of(box(.29f,.23f,.2f,.2f,id))).status==FacePath.Status.TRACKED,
      "original face descriptor retained as anchor after rescue");
  }
  public static void main(String[] args){
    mediumSupported();withoutFlowCannotApprove();wrongAreaRejected();noIdentityDriftFromFlow();
    System.out.println("PASS: "+checks+" guarded recovery v0.8.6.3 regression assertions");
  }
}
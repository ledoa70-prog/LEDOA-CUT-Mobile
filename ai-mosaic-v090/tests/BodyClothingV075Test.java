package kr.ledoa.cut.aitest;
import java.util.*;
public final class BodyClothingV075Test {
 private static int tests=0;
 private static void yes(boolean cond,String name){tests++;if(!cond)throw new AssertionError(name);}
 private static float[] cloth(int rgb){
  int[] pix=new int[24*24];for(int y=0;y<24;y++)for(int x=0;x<24;x++){
   int v=((x*4+y*7)%37)-18;
   int r=Math.min(255,Math.max(0,((rgb>>16)&255)+v));
   int g=Math.min(255,Math.max(0,((rgb>>8)&255)+v));
   int b=Math.min(255,Math.max(0,(rgb&255)+v));
   pix[y*24+x]=0xff000000|(r<<16)|(g<<8)|b;
  }return FaceAppearance.describe(pix,24,24);
 }
 private static BodyClothing.Observation ob(float headX,float bodyX,float[] color){
  return new BodyClothing.Observation(headX,.22f,bodyX,.47f,.27f,.9f,color);
 }
 private static FacePath.Box box(float x,float[] sig){return new FacePath.Box(x,.14f,.20f,.14f,2,sig,true);}
 public static void main(String[] args){
  float[] jacket=cloth(0x225DA0),stranger=cloth(0xC73D16),face=cloth(0xBC886D);
  BodyClothing b=new BodyClothing();
  FacePath.Box target=box(.35f,face);
  yes(!b.select(0,target,ob(.75f,.75f,jacket)),"wrong body cannot be registered");
  yes(b.select(0,target,ob(.45f,.45f,jacket)),"touch links body near selected face");
  yes(b.isEnabled(),"body status enabled");
  BodyClothing.Assist a=b.observe(100,ob(.46f,.46f,jacket));
  yes(a!=null&&a.confirmed,"clothes motion is held");
  yes(a.near(box(.36f,face)),"head matches selected face");
  yes(!a.near(box(.01f,face)),"distant other face not associated");
  yes(b.observe(200,ob(.47f,.47f,stranger))==null,"different jacket rejected");
  yes(b.rejectedCount()>0,"clothing mismatch tracked for diagnostics");
  yes(b.observe(1000,ob(.63f,.62f,jacket))==null,"first return after long gap not immediate");
  yes(b.observe(1100,ob(.64f,.63f,jacket))!=null,"two stable body samples recover");
  FacePath p=new FacePath();p.anchor(0,target);
  BodyClothing.Assist held=b.observe(1200,ob(.65f,.64f,jacket));
  yes(held!=null,"body tracked after gap");
  yes(p.step(100,Collections.emptyList(),held).status==FacePath.Status.BODY_HELD,
      "torso only must not invent face mosaic");
  FacePath f=new FacePath();f.anchor(0,target);
  FacePath.Box other=box(.09f,face);
  FacePath.Point wrong=f.step(100,Collections.singletonList(other),held);
  yes(wrong.status!=FacePath.Status.TRACKED,"face not over the tracked torso cannot mask");
  FacePath.Point good=f.step(200,Collections.singletonList(box(.55f,face)),held);
  yes(good.status!=FacePath.Status.LOST,"body-assisted face candidate considered");
  yes(f.nearest(100).box==null,"no box for wrong candidate");
  yes(b.heldCount()>=2,"body matches counted");
  b.reset();
  yes(!b.isEnabled(),"reset removes target clothing anchor");
  System.out.println("PASS: "+tests+" v0.7.5 upper-body association/false-positive safety tests");
 }
}

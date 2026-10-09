import kr.ledoa.cut.aitest.FacePath;
import kr.ledoa.cut.aitest.FaceAppearance;
import java.util.*;
public class FacePathV074Test{
 static int count=0;
 static void ok(boolean b,String m){count++;if(!b)throw new AssertionError(m);}
 static float[] signature(int rgb){
  int[] pixels=new int[24*24];
  for(int y=0;y<24;y++)for(int x=0;x<24;x++){
   int v=((x*5+y*9+x*y)%53)-26;
   int r=Math.min(255,Math.max(0,((rgb>>16)&255)+v));
   int g=Math.min(255,Math.max(0,((rgb>>8)&255)+v));
   int b=Math.min(255,Math.max(0,(rgb&255)+v));
   pixels[y*24+x]=0xff000000|(r<<16)|(g<<8)|b;
  }
  return FaceAppearance.describe(pixels,24,24);
 }
 static FacePath.Box b(float x,float y,float w,float h,boolean landmarks,float[] appearance){
  return new FacePath.Box(x,y,w,h,0,appearance,landmarks);
 }
 static List<FacePath.Box> one(FacePath.Box b){return Collections.singletonList(b);}
 public static void main(String[] args){
  float[] face=signature(0xBB8865),jacket=signature(0x2266BB);
  FacePath p=new FacePath();
  FacePath.Box start=b(.40f,.14f,.25f,.14f,true,face);
  p.anchor(0,start);
  ok(p.step(100,one(b(.42f,.14f,.25f,.14f,true,face))).status==FacePath.Status.TRACKED,"face continues tracking");
  ok(p.step(200,one(b(.43f,.14f,.25f,.14f,false,face))).status!=FacePath.Status.TRACKED,"back-of-head with no landmarks not tracked");
  ok(p.step(300,one(b(.44f,.14f,.25f,.14f,true,jacket))).status!=FacePath.Status.TRACKED,"clothing appearance mismatched");
  ok(p.step(400,one(b(.45f,.14f,.035f,.025f,true,face))).status!=FacePath.Status.TRACKED,"tiny facial region rejected");
  p.reset();p.anchor(20700,b(.61f,.11f,.33f,.18f,true,face));
  p.step(20800,Collections.emptyList());p.step(20900,Collections.emptyList());p.step(21000,Collections.emptyList());
  ok(p.step(21200,one(b(.58f,.36f,.18f,.10f,true,jacket))).status!=FacePath.Status.TRACKED,"real-video 21.2s wrong jacket excluded");
  p.step(21300,Collections.emptyList());
  ok(p.step(21900,one(b(.83f,.24f,.09f,.05f,true,face))).status!=FacePath.Status.TRACKED,"real-video 21.9s tiny bystander excluded");
  p.reset();p.anchor(3700,b(.0f,.01f,.42f,.42f,true,face));
  p.step(3800,Collections.emptyList());p.step(3900,Collections.emptyList());
  ok(p.step(4200,one(b(.24f,.21f,.20f,.11f,true,face))).status!=FacePath.Status.TRACKED,"real-video 4.2s partial box not falsely reacquired");
  p.reset();p.anchor(16500,b(.25f,.14f,.39f,.22f,true,face));
  p.step(16600,Collections.emptyList());p.step(16700,Collections.emptyList());
  ok(p.step(17700,one(b(.43f,.14f,.37f,.21f,true,face))).status==FacePath.Status.UNCERTAIN,"first reacquire confirmation");
  ok(p.step(17800,one(b(.43f,.14f,.37f,.21f,true,face))).status==FacePath.Status.UNCERTAIN,"second reacquire confirmation");
  ok(p.step(17900,one(b(.43f,.14f,.37f,.21f,true,face))).status==FacePath.Status.UNCERTAIN && p.isIdentityLocked(),"returning face remains untrusted until user explicitly confirms same person");
  p.reset();p.anchor(0,start);
  ok(p.step(100,one(b(.46f,.13f,.28f,.15f,true,face))).status==FacePath.Status.TRACKED,"normal sideways motion");
  ok(p.step(200,one(b(.54f,.12f,.32f,.18f,true,face))).status==FacePath.Status.TRACKED,"normal approaching movement");
  ok(p.reviewCount()==0,"no spurious review for clear face");
  System.out.println("PASS: "+count+" v0.7.4 wrong-target and recovery cases");
 }
}

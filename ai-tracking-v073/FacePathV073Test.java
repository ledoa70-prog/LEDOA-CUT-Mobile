import kr.ledoa.cut.aitest.*;
import java.util.*;
public final class FacePathV073Test {
  static int count=0;
  static void ok(boolean b,String text){count++;if(!b)throw new AssertionError(text);}
  static FacePath.Box box(float x,float y,float w,int id,float[] a){return new FacePath.Box(x,y,w,w*.58f,id,a);}
  static float[] visual(int val){int[] colors=new int[24*24];for(int y=0;y<24;y++)for(int x=0;x<24;x++){
    int modulation=((x*5+y*9+x*y)%53)-26;
    int r=Math.min(255,Math.max(0,((val>>16)&255)+modulation));
    int g=Math.min(255,Math.max(0,((val>>8)&255)+modulation));
    int b=Math.min(255,Math.max(0,(val&255)+modulation));
    colors[y*24+x]=0xff000000|(r<<16)|(g<<8)|b;
  }return FaceAppearance.describe(colors,24,24); }
  static List<FacePath.Box> list(FacePath.Box... x){return Arrays.asList(x);}
  public static void main(String[] args){
    final float[] ref=visual(0xBB8865),diff=visual(0x2266BB);
    ok(FaceAppearance.score(ref,ref)>.98f,"identical visual signature");
    ok(FaceAppearance.score(ref,diff)<.9f,"different coarse appearance recognizable");
    FacePath f=new FacePath();f.anchor(0,box(.2f,.2f,.20f,1,ref));
    ok(f.step(100,list(box(.23f,.2f,.20f,99,ref))).status==FacePath.Status.TRACKED,"ID changes during continuous movement must not break tracking");
    ok(f.step(200,list(box(.3f,.2f,.23f,13,ref))).status==FacePath.Status.TRACKED,"adaptive motion with changing id");
    ok(f.step(300,list(box(.78f,.2f,.2f,9,diff))).status!=FacePath.Status.TRACKED,"reject distant stranger");
    ok(f.interpolated(250)==null,"do not draw mosaic through LOST");
    ok(f.reviewCount()==1,"review counted");
    f.reset();f.anchor(0,box(.2f,.2f,.2f,1,ref));
    f.step(100,Collections.emptyList());f.step(200,Collections.emptyList());f.step(300,Collections.emptyList());
    FacePath.Point p;
    p=f.step(1000,list(box(.55f,.25f,.24f,50,ref)));
    ok(p.status==FacePath.Status.UNCERTAIN,"far reappearance needs multiple frames");
    p=f.step(1100,list(box(.56f,.25f,.24f,49,ref)));
    ok(p.status==FacePath.Status.UNCERTAIN,"still waiting for confirmation");
    p=f.step(1200,list(box(.57f,.25f,.25f,8,ref)));
    ok(p.status==FacePath.Status.UNCERTAIN && f.isIdentityLocked(),"fail closed after long absence, even when visual color resembles the original face");
    f.reset();f.anchor(0,box(.2f,.2f,.2f,1,ref));
    f.step(100,Collections.emptyList());f.step(200,Collections.emptyList());
    p=f.step(300,list(box(.53f,.25f,.24f,70,diff)));
    ok(p.status!=FacePath.Status.TRACKED,"stranger visual mismatch stays untracked");
    f.reset();f.anchor(0,box(.2f,.2f,.2f,1,ref));
    f.step(100,Collections.emptyList());f.step(200,Collections.emptyList());
    p=f.step(300,list(box(.52f,.2f,.2f,70,ref),box(.80f,.2f,.2f,99,ref)));
    ok(p.status==FacePath.Status.UNCERTAIN,"two similarly appearing candidates must not auto-select");
    f.reset();f.anchor(0,box(.2f,.2f,.2f,1,ref));
    ok(f.step(100,Collections.emptyList()).candidates==0,"save detection-count diagnostic");
    ok(f.nearest(50).status==FacePath.Status.VERIFIED,"nearest reliable sample");
    f.review(100,box(.3f,.25f,.2f,1,ref));
    ok(f.reviewCount()==0,"manual reselect resets subsequent failures");
    System.out.println("PASS: "+count+" v0.7.3 visual association and privacy safety tests");
  }
}

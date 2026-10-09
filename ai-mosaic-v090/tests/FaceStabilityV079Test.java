package kr.ledoa.cut.aitest;
import java.util.*;

/** Regression tests reproducing the 15.3s wrong-person ROI incident. */
public final class FaceStabilityV079Test {
  private static int passed=0;
  private static void require(boolean yes,String text){passed++;if(!yes)throw new AssertionError(text);}
  private static float[] sig(int rgb){
    int[] pixels=new int[576];
    for(int y=0;y<24;y++)for(int x=0;x<24;x++){
      int d=((x*5+y*7+x*y)%37)-18;
      int r=Math.max(0,Math.min(255,((rgb>>16)&255)+d));
      int g=Math.max(0,Math.min(255,((rgb>>8)&255)+d));
      int b=Math.max(0,Math.min(255,(rgb&255)+d));
      pixels[y*24+x]=0xff000000|(r<<16)|(g<<8)|b;
    }return FaceAppearance.describe(pixels,24,24);
  }
  private static FacePath.Box box(float x,float y,float w,float h,float[] sig){
     return new FacePath.Box(x,y,w,h,-1,sig,true,false);
  }
  private static BodyClothing.Assist body(float headX,float headY,float shoulders){
     return new BodyClothing.Assist(new BodyClothing.Observation(headX,headY,
       headX,headY+.25f,shoulders,.94f,null),.95f,true);
  }
  private static List<FacePath.Box> one(FacePath.Box face){
     return Collections.singletonList(face);
  }
  public static void main(String[] a){
     float[] target=sig(0xB88863),wrong=sig(0x2A68BF);
     // 14.9s last target face = 38% frame width, 21% height.
     FacePath.Box last=box(.285f,.149f,.378f,.212f,target);
     // 15.3s erroneous background face = 5.37% width, 3.02% height.
     FacePath.Box stranger=box(.6222f,.3271f,.0537f,.0302f,target);
     BodyClothing.Assist pose=body(.65f,.34f,.27f);
     FacePath p=new FacePath();p.anchor(14900,last);
     FacePath.Point r=p.step(15000,one(stranger),pose);
     require(r.status!=FacePath.Status.TRACKED,"tiny stranger t=15.0 cannot be masked");
     r=p.step(15100,one(stranger),pose);
     require(r.status!=FacePath.Status.TRACKED,"tiny stranger t=15.1 cannot be masked");
     r=p.step(15200,one(stranger),pose);
     require(r.status!=FacePath.Status.TRACKED,"tiny stranger t=15.2 cannot be masked");
     r=p.step(15300,one(stranger),pose);
     require(r.status!=FacePath.Status.TRACKED,"tiny stranger t=15.3 cannot be masked");
     require(p.bodyRecoveredCount()==0,"no false BODY_ASSISTED_REACQUIRED");
     require(p.wrongSizeBodyRejectedCount()>=1,"tiny stranger rejection logged");
     require(!FaceRoi.acceptCrop(stranger,last,pose),"optional ROI cannot bypass face-size check");
     require(!FaceRoi.acceptCrop(stranger,last,null),"optional ROI cannot accept tiny stranger without body");
     require(p.nearest(15300).box==null,"background stranger never gets a facial mosaic box");
     // Valid same-size return near body remains functional for real face reacquisition.
     FacePath q=new FacePath();
     q.anchor(0,box(.15f,.13f,.30f,.17f,target));
     BodyClothing.Assist linked=body(.64f,.26f,.30f);
     FacePath.Box returned=box(.49f,.17f,.31f,.18f,target);
     for(int ms=100;ms<=900;ms+=100)q.step(ms,Collections.emptyList(),linked);
     r=q.step(1000,one(returned),linked);
     require(r.status==FacePath.Status.UNCERTAIN,"real face long gap requires confirmation");
     r=q.step(1100,one(returned),linked);
     require(r.status==FacePath.Status.TRACKED && r.reason.startsWith("BODY_ASSISTED"),
         "real face near same body is still eligible for recovery");
     require(q.bodyRecoveredCount()==1,"true face rescue still counted");
     // Wrong visual appearance fails recovery despite plausible size and shirt.
     FacePath v=new FacePath();v.anchor(0,last);
     FacePath.Box mismatch=box(.40f,.15f,.38f,.21f,wrong);
     for(int ms=100;ms<=900;ms+=100)v.step(ms,Collections.emptyList(),pose);
     r=v.step(1000,one(mismatch),pose);
     require(r.status!=FacePath.Status.TRACKED,"wrong appearance cannot join body");
     r=v.step(1100,one(mismatch),pose);
     require(r.status!=FacePath.Status.TRACKED,"wrong appearance cannot win by repeated position");
     System.out.println("PASS: "+passed+" v0.7.9 wrong-person prevention and legitimate body re-link checks");
  }
}
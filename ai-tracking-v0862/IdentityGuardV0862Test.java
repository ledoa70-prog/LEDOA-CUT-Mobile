package kr.ledoa.cut.aitest;
import java.util.*;
/** v0.8.6.2 safeguards: regression suite must run against real FacePath.java. */
public final class IdentityGuardV0862Test {
  private static int assertions;
  private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
  private static float[] descriptor(){
    int[] pixels=new int[24*24];Arrays.fill(pixels,0xffa0a0a0);
    return FaceAppearance.describe(pixels,24,24);
  }
  private static FacePath.Box box(float x,float y,float w,float h,float[] a){
    return new FacePath.Box(x,y,w,h,7,a);
  }
  private static void normalTracking(){
    FacePath p=new FacePath();float[] id=descriptor();
    p.anchor(0,box(.20f,.25f,.25f,.25f,id));
    FacePath.Point a=p.step(100,Arrays.asList(box(.22f,.25f,.25f,.25f,id)));
    check(a.status==FacePath.Status.TRACKED,"ordinary 100ms motion stays TRACKED");
  }
  private static void provisionalJumpCannotBecomeTrusted(){
    FacePath p=new FacePath();float[] id=descriptor();
    p.anchor(0,box(.20f,.25f,.25f,.25f,id));
    check(p.step(100,Arrays.asList(box(.23f,.25f,.25f,.25f,id))).status==FacePath.Status.TRACKED,"first frame tracked");
    p.step(200,Collections.emptyList());
    FacePath.Point provisional=p.putContinuity(200,box(.25f,.26f,.10f,.10f,id),.91f,"PROVISIONAL_TEST");
    check(provisional.status==FacePath.Status.FLOW_ESTIMATED,"flow remains provisional");
    FacePath.Point jumped=p.step(300,Arrays.asList(box(.23f,.24f,.35f,.35f,id)));
    check(jumped.status!=FacePath.Status.TRACKED,"large jump rejected even with same appearance");
    check(jumped.reason.startsWith("IDENTITY_GUARD_"),"jump classified as identity review");
    check(p.lastReliableTimestamp()==100,"bad detection must not replace trustworthy anchor");
  }
  private static void longGapRequiresReacquisition(){
    FacePath p=new FacePath();float[] id=descriptor();
    p.anchor(0,box(.20f,.25f,.20f,.20f,id));
    check(p.step(100,Arrays.asList(box(.21f,.25f,.20f,.20f,id))).status==FacePath.Status.TRACKED,"initial reliable face");
    for(int t=200;t<=400;t+=100)p.step(t,Collections.emptyList());
    check(p.step(500,Arrays.asList(box(.22f,.25f,.20f,.20f,id))).status!=FacePath.Status.TRACKED,"first after gap pending");
    check(p.step(600,Arrays.asList(box(.23f,.25f,.20f,.20f,id))).status!=FacePath.Status.TRACKED,"second after gap pending");
    check(p.step(700,Arrays.asList(box(.24f,.25f,.20f,.20f,id))).status==FacePath.Status.TRACKED,"third observation reacquired");
  }
  private static void resetDoesNotRetainOldIdentity(){
    FacePath p=new FacePath();float[] id=descriptor();
    p.anchor(0,box(.2f,.2f,.2f,.2f,id));p.reset();
    p.anchor(0,box(.5f,.3f,.2f,.2f,id));
    check(p.step(100,Arrays.asList(box(.51f,.3f,.2f,.2f,id))).status==FacePath.Status.TRACKED,"reselect after reset");
  }
  public static void main(String[] args){
    normalTracking();provisionalJumpCannotBecomeTrusted();
    longGapRequiresReacquisition();resetDoesNotRetainOldIdentity();
    System.out.println("v0.8.6.2 identity guard PASS "+assertions+" assertions");
  }
}

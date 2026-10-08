import kr.ledoa.cut.aitest.FacePath;
import java.util.*;
public class FacePathV072Test {
 static void ok(boolean yes,String message){if(!yes)throw new AssertionError(message);}
 static FacePath.Box b(float x,float y,float w,int id){return new FacePath.Box(x,y,w,w*.58f,id);}
 static List<FacePath.Box> arr(FacePath.Box...bs){return Arrays.asList(bs);}
 public static void main(String[] args){
  FacePath t=new FacePath();t.anchor(0,b(.20f,.15f,.24f,3));
  ok(t.step(100,arr(b(.32f,.16f,.25f,3))).status==FacePath.Status.TRACKED,"fast clear face should track");
  ok(t.step(200,arr(b(.46f,.17f,.28f,3))).status==FacePath.Status.TRACKED,"velocity predicted motion should track");
  ok(t.step(300,arr(b(.53f,.16f,.42f,3))).status==FacePath.Status.TRACKED,"nearer face should grow");
  t.reset();t.anchor(0,b(.2f,.2f,.18f,2));
  ok(t.step(100,arr(b(.85f,.2f,.18f,4))).status==FacePath.Status.LOST,"do not jump to other side");
  t.reset();t.anchor(0,b(.2f,.2f,.18f,2));
  ok(t.step(100,arr(b(.25f,.2f,.18f,2),b(.251f,.201f,.18f,9))).status==FacePath.Status.UNCERTAIN,"crowded faces must ask for review");
  t.reset();t.anchor(0,b(.2f,.2f,.2f,6));
  ok(t.step(100,Collections.emptyList()).status==FacePath.Status.LOST,"missing detection must not mask");
  ok(t.step(200,arr(b(.28f,.21f,.2f,6))).status==FacePath.Status.TRACKED,"short lost should recover when same id and position");
  ok(t.points().get(1).candidates==0,"detector count diagnostics available");
  t.reset();t.anchor(0,b(.2f,.2f,.2f,6));
  t.step(100,Collections.emptyList()); t.step(200,Collections.emptyList());
  ok(t.step(300,arr(b(.45f,.2f,.20f,6))).status==FacePath.Status.UNCERTAIN,"first tentative reentry requires second sample");
  ok(t.step(400,arr(b(.46f,.2f,.20f,6))).status==FacePath.Status.TRACKED,"second stable same-id detection reacquires");
  t.reset();t.anchor(0,b(.2f,.2f,.2f,6));
  t.step(100,Collections.emptyList());
  ok(t.interpolated(50)==null,"never fabricate mosaic before lost sample");
  System.out.println("PASS: 10 tracking regression cases");
 }
}

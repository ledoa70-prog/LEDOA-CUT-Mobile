package kr.ledoa.cut.aitest;
import java.util.*;
/** Regression for 21.2s crowd identity jump; don't count guesses as successful masks. */
public final class FaceCrowdV084Test {
  private static int checks=0;
  private static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
  private static FacePath.Box b(float x,float y,float w,float h,int id){
    return new FacePath.Box(x,y,w,h,id,null,true,false);
  }
  public static void main(String[]args){
    FacePath.Box selected=b(.55f,.09f,.34f,.19f,1);
    List<FacePath.Box> crowd=Arrays.asList(
       b(.59f,.10f,.26f,.14f,201),
       b(.08f,.08f,.13f,.12f,202),
       b(.09f,.40f,.14f,.12f,203),
       b(.54f,.60f,.10f,.10f,204),
       b(.88f,.60f,.09f,.10f,205));
    FacePath p=new FacePath();p.anchor(20600,selected);
    FacePath.Point step=p.step(21200,crowd);
    check(step.status!=FacePath.Status.TRACKED,"a stranger at t=21.2 cannot become trusted after 600ms gap");
    check(step.box==null,"no facial mosaic from one-frame crowded guess");
    check(step.reason.contains("REVIEW")||step.reason.contains("CONFIRMING"),
          "crowded frame requires user or multiple real face observations");
    check(p.crowdedGapBlockedCount()==1,"crowded single-frame block counted");
    check(p.lastReliableTimestamp()==20600,"stranger must not contaminate trusted face history");
    check(p.reviewCount()==1,"ambiguous crowded tracking counts toward review");

    FacePath clean=new FacePath();clean.anchor(0,selected);
    FacePath.Point good=clean.step(100,Collections.singletonList(
        b(.565f,.095f,.34f,.19f,1)));
    check(good.status==FacePath.Status.TRACKED,
          "ordinary consecutive face tracking must remain functional");
    check(clean.crowdedGapBlockedCount()==0,"ordinary face must not trigger crowd guard");
    FacePath empty=new FacePath();empty.anchor(0,selected);
    check(empty.step(100,Collections.emptyList()).status==FacePath.Status.LOST,
          "never pretend missing face was detected");
    check(empty.crowdedGapBlockedCount()==0,"blank scene is not crowd");
    empty.reset();check(empty.crowdedGapBlockedCount()==0,"new video resets crowd counter");
    System.out.println("PASS: "+checks+" v0.8.4 crowd identity and face-privacy regression assertions");
  }
}
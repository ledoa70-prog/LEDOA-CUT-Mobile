package kr.ledoa.cut.aitest;
import java.util.*;
public final class ContinuityV085Test {
 static int n=0;
 static void check(boolean ok,String why){n++;if(!ok)throw new AssertionError(why);}
 static byte[] image(int w,int h,int dx){
  byte[] a=new byte[w*h];
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   double v=120+29*Math.sin((x-dx)*.42)+28*Math.cos(y*.31)+28*Math.sin((x-dx+y)*.28)+12*Math.cos((x-dx-y)*.12);
   a[y*w+x]=(byte)Math.max(0,Math.min(255,Math.round(v)));
  }return a;
 }
 public static void main(String[]args){
  FacePath path=new FacePath();FacePath.Box target=new FacePath.Box(.18f,.22f,.58f,.36f,1);
  path.anchor(0,target);
  FacePath.Box stranger=new FacePath.Box(.83f,.73f,.09f,.08f,9);
  FacePath.Point p=path.step(100,Collections.singletonList(stranger));
  check(p.box==null,"a bystander does not become the selected face");
  p=path.putContinuity(100,new FacePath.Box(.20f,.22f,.58f,.36f,1),.92f,"FORWARD_HEAD_MOTION_REVIEW");
  check(p.status==FacePath.Status.FLOW_ESTIMATED&&p.candidates==1,"background detection no longer suppresses independently measured head motion");
  check(path.lastReliableTimestamp()==0,"provisional coverage never updates identity");
  check(path.reviewCount()==1&&path.uncoveredCount()==0,"review count is distinct from actual mask holes");
  path.coverageEnd(186);
  check(path.interpolated(170)!=null,"final fractional sample stays covered");
  check(path.interpolated(187)==null,"nothing beyond analyzed end is covered");
  check(path.interpolated(-1)==null,"nothing before selection is fabricated");
  check(path.interpolated(50)!=null,"intermediate preview remains covered");
  FacePath reacquired=new FacePath();reacquired.anchor(0,target);
  List<FacePath.Box> crowd=Arrays.asList(target,stranger,new FacePath.Box(.03f,.80f,.08f,.08f,8));
  check(reacquired.step(400,crowd).box==null,"first crowd reacquisition stays unconfirmed");
  check(reacquired.step(500,crowd).box==null,"second crowd reacquisition stays unconfirmed");
  check(reacquired.step(600,crowd).status==FacePath.Status.TRACKED,"third consistent detection confirms recovery");
  check(reacquired.exact(400).status==FacePath.Status.DETECTION_BRIDGED&&reacquired.exact(500).box!=null,"real candidate boxes are restored retroactively");
  check(reacquired.confirmedBackfillCount()==2&&reacquired.reviewCount()==2,"backfilled detections remain explicitly reviewable");

  FacePath stable=new FacePath();stable.anchor(0,target);
  OpticalBridge optical=new OpticalBridge();LazyFlowBridge lazy=new LazyFlowBridge(optical);
  ContinuityBridge bridge=new ContinuityBridge(stable,lazy);
  bridge.process(0,image(160,224,0),160,224,stable.exact(0),null);
  for(int t=50;t<=1000;t+=50){
   byte[] pixels=image(160,224,0);
   if(t%100==0)bridge.process(t,pixels,160,224,stable.step(t,Collections.singletonList(target)),null);
   else bridge.cache(t,pixels,160,224);
  }
  check(lazy.lazySeeds()==0&&lazy.flowAttempts()==0,"no optical pyramids or reverse inference on stable detected frames");
  check(bridge.peakCachedFrames()<=3,"stable path keeps only three tiny frames");
  bridge.cache(1050,image(160,224,1),160,224);
  p=bridge.process(1100,image(160,224,2),160,224,stable.step(1100,Collections.singletonList(stranger)),null);
  check(p.box!=null&&p.status==FacePath.Status.FLOW_ESTIMATED,"50ms forward bridge actually survives unrelated detected faces");
  check(stable.lastReliableTimestamp()==1000,"motion keeps real anchor timestamp immutable");
  for(int t=1150;t<=3400;t+=50){
   byte[] black=new byte[160*224];
   if(t%100==0)bridge.process(t,black,160,224,stable.step(t,Collections.emptyList()),null);
   else bridge.cache(t,black,160,224);
  }
  check(stable.exact(3400).box==null,"scene cut cannot be converted into a face mask");
  check(bridge.peakCachedFrames()<=40,"cache remains bounded during long detector failure");
  check(!ContinuityBridge.agrees(target,stranger),"reverse closure rejects wrong person location");
  OpticalBridge expire=new OpticalBridge();expire.anchor(0,image(160,224,0),160,224,target);
  for(int t=50;t<=OpticalBridge.MAX_FACE_MISSING_MS;t+=50)
   check(expire.advance(t,image(160,224,0),160,224,null)!=null,"valid motion continues within bounded blackout at "+t);
  check(expire.advance(OpticalBridge.MAX_FACE_MISSING_MS+50,image(160,224,0),160,224,null)==null,"long unverified motion expires");
  System.out.println("PASS: "+n+" v0.8.5 continuity, timing, scene-cut, identity, memory and expiry assertions");
 }
}

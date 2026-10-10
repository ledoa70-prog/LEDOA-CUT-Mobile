package kr.ledoa.cut.aitest;
import java.util.*;
public final class MosaicV090Test {
 static int checks=0;static void ok(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static FacePath.Box b(float x,float y){return new FacePath.Box(x,y,.20f,.24f,-1);}
 static float[] sig(double angle){float[] x=new float[88];x[0]=x[12]=1;x[24]=(float)Math.cos(angle);x[25]=(float)Math.sin(angle);return x;}
 static float[] learned(double angle){float[] a=new float[128];a[0]=(float)Math.cos(angle);a[1]=(float)Math.sin(angle);return a;}
 static FacePath.Box lb(float x,float y,double a){return new FacePath.Box(x,y,.20f,.24f,-1,learned(a));}
 static FacePath.Box identity(float x,float y,double a){return new FacePath.Box(x,y,.2f,.24f,-1,sig(a));}
 public static void main(String[] args){
  MosaicTimeline t=new MosaicTimeline();t.allFaces=true;t.startMs=100;t.endMs=1000;t.analysedEndMs=800;t.put(100,List.of(b(.1f,.2f),b(.7f,.2f)));t.put(200,List.of(b(.72f,.2f),b(.12f,.2f)));
  ok(t.boxesAt(99).isEmpty(),"range start");ok(t.boxesAt(800).isEmpty(),"cancelled tail");ok(t.boxesAt(1000).isEmpty(),"exclusive end");
  List<FacePath.Box> mid=t.boxesAt(150);ok(mid.size()==2,"both faces retained");ok(Math.abs(mid.get(0).x-.11)<.001,"one-to-one association despite reordered IDs");
  t.cut(200);ok(Math.abs(t.boxesAt(199).get(0).x-.1)<.001,"no interpolation across cut");ok(Math.abs(t.boxesAt(200).get(0).x-.72)<.001,"new shot applies at boundary");
  t.put(300,Collections.emptyList());ok(t.boxesAt(300).isEmpty(),"no stale face in empty shot");
  t.truncate(200);ok(t.samples().size()==1&&t.cuts().isEmpty(),"manual tail correction preserves earlier samples");
  SceneCuts cuts=new SceneCuts();byte[] dark=new byte[768],light=new byte[768];Arrays.fill(dark,(byte)20);Arrays.fill(light,(byte)220);
  ok(!cuts.observe(0,dark,32,24),"first sample not a cut");ok(!cuts.observe(50,dark,32,24),"static scene");ok(cuts.observe(100,light,32,24),"hard cut");ok(!cuts.observe(150,light,32,24),"no repeated cut");
  FacePath f=new FacePath();f.anchor(0,identity(.1f,.2f,0));f.step(100,List.of(identity(.11f,.2f,0)));f.sceneCut(200);
  ok(f.step(200,List.of(identity(.7f,.1f,Math.PI))).box==null,"unrelated face after cut blocked");
  ok(f.interpolated(150).box.x<.2,"old shot is not dragged into new shot");
  ok(f.step(300,List.of(identity(.7f,.1f,0))).box==null,"first new shot vote");ok(f.step(400,List.of(identity(.71f,.1f,0))).box==null,"second new shot vote");
  ok(f.step(500,List.of(identity(.72f,.1f,0))).reason.equals("SCENE_REACQUIRED_REVIEW"),"strong scene recovery at distant coordinates");
  f.coverageEnd(600);ok(f.interpolated(600)==null,"no unsafe extrapolation beyond last hundred milliseconds");
  FacePath model=new FacePath();model.anchor(0,lb(.1f,.2f,0));
  ok(model.step(100,List.of(lb(.12f,.2f,.3))).status==FacePath.Status.TRACKED,"learned appearance follows movement");
  model.sceneCut(200);
  ok(model.step(200,List.of(lb(.7f,.2f,.7))).box==null,"new shot requires temporal votes");
  ok(model.step(300,List.of(lb(.71f,.2f,.7))).box==null,"new shot second vote");
  ok(model.step(400,List.of(lb(.72f,.2f,.7))).reason.equals("SCENE_EMBEDDING_REACQUIRED_REVIEW"),"learned identity crosses spatial cut");
  ok(model.exact(200).box!=null,"real confirmed detection backfilled to first new-shot frame");
  model.sceneCut(500);ok(model.step(500,List.of(lb(.3f,.3f,2))).box==null,"different learned identity blocked");
  ok(model.step(600,List.of(lb(.2f,.2f,.4),lb(.6f,.2f,.41))).box==null,"similar competing faces remain ambiguous");
  ok(FaceAppearance.score(learned(0),sig(0))<0,"never compare coarse and learned vectors");
  FacePath poses=new FacePath();poses.anchor(0,lb(.1f,.2f,0));
  poses.step(400,List.of(lb(.11f,.2f,.5)));poses.step(500,List.of(lb(.12f,.2f,.5)));poses.step(600,List.of(lb(.13f,.2f,.5)));
  poses.step(700,List.of(lb(.14f,.2f,.5)));poses.step(800,List.of(lb(.15f,.2f,1.0)));
  poses.step(900,List.of(lb(.16f,.2f,1.0)));poses.step(1000,List.of(lb(.17f,.2f,1.0)));poses.step(1100,List.of(lb(.18f,.2f,1.0)));
  ok(poses.step(1200,List.of(lb(.19f,.2f,1.1))).box!=null,"continuous head turn uses verified views");
  poses.sceneCut(2000);
  ok(poses.step(2000,List.of(lb(.7f,.2f,1.2))).box==null,"changed-pose reappearance waits for confirmation");
  poses.step(2100,List.of(lb(.71f,.2f,1.2)));
  ok(poses.step(2200,List.of(lb(.72f,.2f,1.2))).box!=null,"original downward pose does not permanently block verified side view");
  poses.sceneCut(2300);
  ok(poses.step(2300,List.of(lb(.7f,.2f,2.0))).box==null,"gallery alone cannot bypass fixed original identity floor");
  MosaicTimeline review=new MosaicTimeline();review.allFaces=true;
  review.put(0,List.of(b(.1f,.2f)));review.put(100,List.of());review.put(200,List.of());review.put(300,List.of(b(.1f,.2f)));review.put(400,List.of());
  ok(review.nextReview(0)==100,"review jumps to first missing segment");
  ok(review.nextReview(150)==400,"review skips remaining samples in current missing segment");
  System.out.println("PASS: "+checks+" scene/range/multiface checks");
 }
}

package kr.ledoa.cut.aitest;
import java.util.*;

/** Simulation checks using camera-scale edge movements and deliberately wrong targets. */
public final class FaceRecoveryV077Test {
    private static int checks=0;
    private static void ok(boolean b,String what){checks++;if(!b)throw new AssertionError(what);}
    private static float[] sig(int rgb){
        int[] p=new int[576];
        for(int y=0;y<24;y++)for(int x=0;x<24;x++){
            int d=((x*3+y*7+x*y)%27)-13;
            int r=Math.max(0,Math.min(255,((rgb>>16)&255)+d));
            int g=Math.max(0,Math.min(255,((rgb>>8)&255)+d));
            int bb=Math.max(0,Math.min(255,(rgb&255)+d));
            p[y*24+x]=0xff000000|(r<<16)|(g<<8)|bb;
        }
        return FaceAppearance.describe(p,24,24);
    }
    private static FacePath.Box face(float x,float y,float w,float h,int id,float[] a) {
        return new FacePath.Box(x,y,w,h,id,a,true,false);
    }
    private static List<FacePath.Box> list(FacePath.Box...a){return Arrays.asList(a);}
    public static void main(String[] args){
        float[] target=sig(0xB88866),different=sig(0x185BC1);
        FacePath p=new FacePath();
        p.anchor(0,face(0f,.13f,.26f,.165f,1,target));
        FacePath.Point first=p.step(100,list(face(.28f,.1f,.55f,.34f,8,target)));
        ok(first.status==FacePath.Status.UNCERTAIN && first.reason.startsWith("ADAPTIVE_SCALE_CONFIRMING"),
                "large face scale jump must not mask on first frame");
        FacePath.Point second=p.step(200,list(face(.29f,.1f,.55f,.34f,999,target)));
        ok(second.status==FacePath.Status.TRACKED && second.reason.equals("ADAPTIVE_SCALE_REACQUIRED"),
                "two real matching face detections re-link despite ID change");
        ok(p.scaleRecoveredCount()==1,"scale recovery metric is incremented");
        ok(p.reviewCount()==1,"provisional face sample remains review-needed");
        FacePath.Point third=p.step(300,list(face(.30f,.1f,.55f,.34f,4,target)));
        ok(third.status==FacePath.Status.TRACKED,"tracking resumes normally after scale recovery");
        p.reset();p.anchor(0,face(0f,.13f,.26f,.165f,1,target));
        ok(p.scaleRecoveredCount()==0,"all counters reset with video");
        FacePath.Point a=p.step(100,list(face(.28f,.1f,.55f,.34f,8,different)));
        ok(a.status!=FacePath.Status.TRACKED,"appearance mismatch prevents zoom-based wrong person");
        FacePath.Point b=p.step(200,list(face(.28f,.1f,.55f,.34f,8,different)));
        ok(b.status!=FacePath.Status.TRACKED,"repeated wrong appearance never accepted");
        p.reset();p.anchor(0,face(.15f,.13f,.26f,.165f,1,target));
        FacePath.Point multi=p.step(100,list(face(.40f,.1f,.55f,.34f,8,target),face(.72f,.1f,.20f,.14f,9,target)));
        ok(multi.status!=FacePath.Status.TRACKED,"multiple similar faces must not trigger jump recovery");
        p.reset();p.anchor(0,face(.15f,.13f,.26f,.165f,1,target));
        FacePath.Point empty=p.step(100,Collections.emptyList());
        ok(empty.status==FacePath.Status.LOST && empty.box==null,"no model-detected face never draws mosaic");
        BodyClothing c=new BodyClothing();
        FacePath.Box f=face(.38f,.15f,.22f,.14f,1,target);
        float[] top=sig(0x124A8D);
        BodyClothing.Observation pose=new BodyClothing.Observation(.49f,.22f,.49f,.46f,.23f,.94f,top);
        ok(c.select(0,f,pose),"register torso at tapped face");
        ok(c.recent(100)!=null && c.recent(100).confidence>0,"brief pose interruption retains recent body location");
        ok(c.recent(310)==null,"body position cannot be reused after 300ms");
        BodyClothing.Observation notSame=new BodyClothing.Observation(.49f,.22f,.49f,.46f,.23f,.94f,sig(0xD0231E));
        ok(c.observe(100,notSame)==null,"wrong color causes pose rejection");
        ok(c.recent(150)==null,"do not reuse stale torso after explicit mismatched shirt");
        System.out.println("PASS: "+checks+" v0.7.7 adaptive scale and brief-body-hold safety checks");
    }
}
package kr.ledoa.cut.aitest;
import java.util.Collections;

public final class OpticalBridgeV080Test {
    static int pass=0;
    static void check(boolean b,String why){pass++;if(!b)throw new AssertionError(why);}
    static byte[] noisy(int w,int h,int salt){
        byte[] out=new byte[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int v=((x*97+y*113+(x*y)*7+salt*181)^(x*13+y*5+salt*29));
            out[y*w+x]=(byte)(v&255);
        }
        return out;
    }
    static byte[] shift(byte[] prev,int w,int h,int dx,int dy){
        byte[] curr=new byte[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int sx=x-dx,sy=y-dy;
            curr[y*w+x]=(sx>=0&&sx<w&&sy>=0&&sy<h)?prev[sy*w+sx]:0;
        }
        return curr;
    }
    static FacePath.Box box(float x,float y,float w,float h) {
        return new FacePath.Box(x,y,w,h,1,null,true,false);
    }
    public static void main(String[]args){
        int w=160,h=224;
        byte[] old=noisy(w,h,2);
        FacePath.Box initial=box(.20f,.22f,.38f,.28f);
        OpticalBridge tracker=new OpticalBridge();
        tracker.anchor(0,old,w,h,initial);
        byte[] current=shift(old,w,h,4,3);
        OpticalBridge.Estimate estimate=tracker.advance(100,current,w,h,null);
        check(estimate!=null,"high-texture target recovers after 100ms detector blackout");
        check(estimate.confidence>=.72f,"estimate must clear confidence threshold");
        check(estimate.consistentPatches>=7,"optical bridge uses consensus across multiple patches");
        check(Math.abs(estimate.box.x-(initial.x+4f/w))<.022f,"x follows image shift");
        check(Math.abs(estimate.box.y-(initial.y+3f/h))<.022f,"y follows image shift");
        FacePath path=new FacePath();
        path.anchor(0,initial);
        FacePath.Point noFace=path.step(100,Collections.emptyList());
        check(noFace.status==FacePath.Status.LOST,"ML detector miss remains honest in its own results");
        FacePath.Point provisional=path.putMotionEstimate(100,estimate.box,estimate.confidence);
        check(provisional.status==FacePath.Status.FLOW_ESTIMATED,"flow position explicitly labeled provisional");
        check(path.flowEstimatedCount()==1,"flow review counter");
        check(path.reviewCount()==1,"motion guesses still require review");
        check(path.lastReliableTimestamp()==0,"flow must not update true face anchor");
        check(path.interpolated(100)!=null&&path.interpolated(100).box!=null,
              "preview displays provisional mosaic only at validated frame");
        current=shift(current,w,h,3,2);
        OpticalBridge.Estimate continued=tracker.advance(200,current,w,h,null);
        check(continued!=null,"multiple optical frames can bridge a short gap");
        for(int ms=300;ms<=600;ms+=100){
            current=shift(current,w,h,2,1);
            OpticalBridge.Estimate predicted=tracker.advance(ms,current,w,h,null);
            check(predicted!=null,"optical flow remains bounded and coherent "+ms);
        }
        current=shift(current,w,h,2,1);
        check(tracker.advance(700,current,w,h,null)==null,"prediction expires after 600ms");
        check(tracker.expiredCount()>0,"expired bridge flagged");
        OpticalBridge wrong=new OpticalBridge();
        wrong.anchor(0,old,w,h,initial);
        check(wrong.advance(100,noisy(w,h,99),w,h,null)==null,
              "unrelated video texture must not create a mosaic");
        OpticalBridge repeated=new OpticalBridge();
        repeated.anchor(0,new byte[w*h],w,h,initial);
        check(repeated.advance(100,new byte[w*h],w,h,null)==null,
              "blank untextured frames cannot be tracked");
        OpticalBridge guarded=new OpticalBridge();
        guarded.anchor(0,old,w,h,initial);
        BodyClothing.Assist otherBody=new BodyClothing.Assist(
           new BodyClothing.Observation(.94f,.95f,.94f,.94f,.15f,.95f,null),.95f,true);
        check(guarded.advance(100,shift(old,w,h,4,3),w,h,otherBody)==null,
              "confirmed body at different location rejects flow prediction");
        path.reset();
        check(path.flowEstimatedCount()==0,"selection resets provisional state");
        System.out.println("PASS: "+pass+" bounded optical motion, 600ms expiry and wrong-person safety checks");
    }
}
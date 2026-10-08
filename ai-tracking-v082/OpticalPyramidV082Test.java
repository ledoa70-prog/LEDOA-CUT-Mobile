package kr.ledoa.cut.aitest;
import java.util.*;
/** Deterministic tests with synthetic imagery; private customer frames are never committed. */
public final class OpticalPyramidV082Test {
    static int count=0;
    static void check(boolean yes,String what){count++;if(!yes)throw new AssertionError(what);}
    static byte[] textured(int w,int h){
        byte[] a=new byte[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            double v=120+29*Math.sin(x*.42)+28*Math.cos(y*.31)+
                     28*Math.sin((x+y)*.28)+12*Math.cos((x-y)*.12);
            a[y*w+x]=(byte)Math.max(0,Math.min(255,Math.round(v)));
        }
        return a;
    }
    static byte[] transformed(byte[] old,int w,int h,float scale,int dx,int dy){
        byte[] n=new byte[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            float fx=(x-75-dx)/scale+75,fy=(y-110-dy)/scale+110;
            int sx=(int)Math.floor(fx),sy=(int)Math.floor(fy);
            float wx=fx-sx,wy=fy-sy,v=0;
            if(sx>=0&&sy>=0&&sx<w-1&&sy<h-1){
                v=(old[sy*w+sx]&255)*(1-wx)*(1-wy)+
                  (old[sy*w+sx+1]&255)*wx*(1-wy)+
                  (old[(sy+1)*w+sx]&255)*(1-wx)*wy+
                  (old[(sy+1)*w+sx+1]&255)*wx*wy;
            }
            n[y*w+x]=(byte)Math.round(v);
        }
        return n;
    }
    public static void main(String[]args){
        int w=160,h=224;
        FacePath.Box face=new FacePath.Box(.18f,.22f,.58f,.36f,5);
        byte[] first=textured(w,h);
        OpticalBridge bridge=new OpticalBridge();
        bridge.anchor(0,first,w,h,face);
        float previousW=face.w;
        for(int ms=100;ms<=600;ms+=100){
            byte[] next=transformed(first,w,h,1.035f,3,2);
            OpticalBridge.Estimate e=bridge.advance(ms,next,w,h,null);
            check(e!=null,"zoom bridge should estimate close-up at "+ms+"ms");
            check(e.confidence>=.78f,"provisional confidence");
            check(e.consistentPatches>=7,"multi-feature agreement");
            check(e.box.w>previousW,"face-scale change measured from actual image motion");
            check(e.box.w<=.95f,"bounded rectangle");
            previousW=e.box.w;first=next;
        }
        byte[] over=transformed(first,w,h,1.035f,3,2);
        check(bridge.advance(700,over,w,h,null)==null,"never bridge past 600ms without actual face");
        check(bridge.expiredCount()==1,"expired counter");
        check(bridge.rejectedCount()==bridge.rejectedFewPoints()+
             bridge.rejectedGeometry()+bridge.rejectedPhotometric()+
             bridge.rejectedBody()+bridge.rejectedOutside(),
             "all rejection reasons accounted for");
        OpticalBridge flat=new OpticalBridge();
        flat.anchor(0,new byte[w*h],w,h,face);
        check(flat.advance(100,new byte[w*h],w,h,null)==null,
              "no false tracker on featureless frames");
        OpticalBridge displaced=new OpticalBridge();
        displaced.anchor(0,textured(w,h),w,h,face);
        byte[] unrelated=transformed(textured(w,h),w,h,1f,62,-59);
        check(displaced.advance(100,unrelated,w,h,null)==null,
              "reject out-of-search jumps instead of switching identities");
        FacePath path=new FacePath();path.anchor(0,face);
        path.step(100,Collections.emptyList());
        FacePath.Box provisional=new FacePath.Box(.19f,.22f,.58f,.36f,5,null,false,false);
        FacePath.Point p=path.putMotionEstimate(100,provisional,.91f);
        check(p.status==FacePath.Status.FLOW_ESTIMATED,
              "estimated mosaics must be distinguished from detected faces");
        check(path.reviewCount()==1,"unverified gap remains review required");
        check(path.lastReliableTimestamp()==0,
              "motion cannot poison verified face identity");
        System.out.println("PASS: "+count+" v0.8.2 synthetic optical pyramid zoom and privacy guard checks");
    }
}

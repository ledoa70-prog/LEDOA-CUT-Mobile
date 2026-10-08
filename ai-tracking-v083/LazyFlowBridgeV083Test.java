package kr.ledoa.cut.aitest;
import java.util.*;
public final class LazyFlowBridgeV083Test {
    private static int n=0;
    private static void check(boolean ok,String msg){n++;if(!ok)throw new AssertionError(msg);}
    private static byte[] picture(int w,int h,int shift){
        byte[] a=new byte[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int fx=x-shift;
            double value=fx<0?0:128+22*Math.sin(fx*.31)+34*Math.cos(y*.35)+
                          23*Math.sin((fx+y)*.23)+18*Math.cos((fx-y)*.18);
            a[y*w+x]=(byte)Math.max(0,Math.min(255,(int)value));
        }
        return a;
    }
    public static void main(String[]args){
        OpticalBridge optical=new OpticalBridge();
        LazyFlowBridge lazy=new LazyFlowBridge(optical);
        int w=160,h=224;
        FacePath.Box face=new FacePath.Box(.20f,.18f,.53f,.40f,7);
        lazy.reset();
        check(lazy.gap(100,picture(w,h,1),w,h,null,true)==null,
              "No user-selected verified face must never produce flow mask");
        for(int i=0;i<120;i++)
            lazy.trusted(i*100L,picture(w,h,0),w,h,face);
        check(lazy.trustedUpdates()==120,"120 confirmed frames retained cheaply");
        check(lazy.lazySeeds()==0,"ZERO pyramid feature extraction during 120 good frames");
        OpticalBridge.Estimate estimate=lazy.gap(12000,picture(w,h,2),w,h,null,true);
        check(lazy.lazySeeds()==1,"single delayed pyramid extraction at first miss");
        check(estimate!=null,"short detector blackout can still use real pixels");
        check(estimate.confidence>=.78f,"preserve optical confidence checks");
        check(lazy.gap(12000,picture(w,h,2),w,h,null,true)==null,
              "same timestamp cannot be reused to invent extra movement");
        check(lazy.lazySeeds()==1,"do not re-run pyramid on repeated misses");
        lazy.ambiguous();
        check(lazy.gap(12100,picture(w,h,4),w,h,null,true)==null,
              "ambiguous stranger detection invalidates lazy flow state");
        check(lazy.gap(12700,picture(w,h,5),w,h,null,true)==null,
              "must not re-link after 600ms without real face");
        lazy.trusted(20000,picture(w,h,0),w,h,face);
        check(lazy.gap(20100,picture(w,h,2),w,h,null,false)==null,
              "body-held or untrusted detection frames must not get provisional mosaic");
        check(lazy.lazySeeds()==1,"unsafe frames never allocate feature pyramids");
        lazy.trusted(30000,picture(w,h,0),w,h,face);
        check(lazy.gap(30100,picture(w,h,2),w,h,null,true)!=null,
              "new verified face re-enables isolated gap tracking");
        check(lazy.lazySeeds()==2,"one pyramid per trustworthy blackout start");
        lazy.reset();
        check(lazy.trustedUpdates()==0&&lazy.lazySeeds()==0,"new video must reset counters");
        System.out.println("PASS: "+n+" v0.8.3 lazy flow crash guard and privacy tests");
    }
}

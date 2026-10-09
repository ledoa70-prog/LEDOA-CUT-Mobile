package kr.ledoa.cut.aitest;
import java.util.Collections;
import java.util.Arrays;

/** v0.8.1: a wrong full-frame candidate must not destroy the short optical bridge. */
public final class OpticalBridgeV081Test {
    private static int n=0;
    private static void ok(boolean b,String name){n++;if(!b)throw new AssertionError(name);}
    private static byte[] image(int w,int h){
        byte[] a=new byte[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int p=(x*97+y*113+(x*y)*7)^(x*13+y*5);
            a[y*w+x]=(byte)p;
        }
        return a;
    }
    private static byte[] shifted(byte[] old,int w,int h,int dx,int dy){
        byte[] a=new byte[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int px=x-dx,py=y-dy;
            a[y*w+x]=(px>=0&&px<w&&py>=0&&py<h)?old[py*w+px]:0;
        }
        return a;
    }
    public static void main(String[] args){
        int w=160,h=224;
        byte[] g0=image(w,h);
        FacePath.Box target=new FacePath.Box(.20f,.22f,.38f,.28f,7);
        FacePath.Box unrelated=new FacePath.Box(.72f,.08f,.22f,.11f,100);
        OpticalBridge bridge=new OpticalBridge();
        FacePath path=new FacePath();
        path.anchor(5800,target);bridge.anchor(5800,g0,w,h,target);
        byte[] g5900=shifted(g0,w,h,4,3);
        FacePath.Point bad=path.step(5900,Collections.singletonList(unrelated));
        ok(bad.status!=FacePath.Status.TRACKED,"bad face rejected");
        OpticalBridge.Estimate a=bridge.advance(5900,g5900,w,h,null);
        ok(a!=null,"keep motion through first rejected face candidate");
        ok(path.flowEstimatedCount()==0,"never mosaic when other face was detected");
        byte[] g6000=shifted(g5900,w,h,4,3);
        path.step(6000,Collections.singletonList(unrelated));
        OpticalBridge.Estimate b=bridge.advance(6000,g6000,w,h,null);
        ok(b!=null,"second rejected face can continue optical history");
        byte[] g6100=shifted(g6000,w,h,4,3);
        FacePath.Point none=path.step(6100,Collections.emptyList());
        ok(none.status==FacePath.Status.LOST,"real detector still reports missing face");
        OpticalBridge.Estimate c=bridge.advance(6100,g6100,w,h,null);
        ok(c!=null,"bridge still initialized when real detector reaches zero");
        FacePath.Point provisional=path.putMotionEstimate(6100,c.box,c.confidence);
        ok(provisional!=null&&provisional.status==FacePath.Status.FLOW_ESTIMATED,
           "only missing detector frame gains provisional face mask");
        ok(path.reviewCount()==3,"provisional mask still counts as review");
        ok(path.lastReliableTimestamp()==5800,"never let optical estimates change trusted selected face");
        // Reacquired real face is the only way to reset trusted identity anchor.
        FacePath.Box back=new FacePath.Box(.27f,.26f,.38f,.28f,11);
        FacePath.Point detected=path.step(6200,Collections.singletonList(back));
        ok(detected!=null,"face detector output can still be processed");
        bridge.invalidate();
        ok(bridge.advance(6300,g6100,w,h,null)==null,
           "explicit multi-person ambiguity must invalidate motion evidence");
        System.out.println("PASS: "+n+" optical bridge v0.8.1 face-candidate blackout safety checks");
    }
}
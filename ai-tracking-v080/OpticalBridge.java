package kr.ledoa.cut.aitest;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Bounded sparse block matching between successive decoded frames.
 * This is image movement evidence, NOT face detection or proof of identity.
 * It only bridges <=600ms after a real, selected face was last detected,
 * and only when several separate textured patches move consistently.
 */
public final class OpticalBridge {
    public static final long MAX_FACE_MISSING_MS=600L;
    public static final class Estimate {
        public final FacePath.Box box;
        public final float confidence;
        public final int consistentPatches;
        Estimate(FacePath.Box b,float confidence,int n) {
            this.box=b;this.confidence=confidence;this.consistentPatches=n;
        }
    }
    private byte[] previous;
    private int width,height;
    private long lastSeenMs=-1,lastFrameMs=-1;
    private FacePath.Box lastBox;
    private int estimated=0,rejected=0,expired=0;
    private static final int PATCH=2,SEARCH=11;
    private static final int[] SX={18,35,52,69,86};
    private static final int[] SY={23,41,59,77};

    public void reset(){
        previous=null;lastBox=null;width=height=0;
        lastSeenMs=lastFrameMs=-1;estimated=rejected=expired=0;
    }
    public int estimatedCount(){return estimated;}
    public int rejectedCount(){return rejected;}
    public int expiredCount(){return expired;}
    public void anchor(long ms,byte[] gray,int w,int h,FacePath.Box detected){
        if(gray==null||detected==null||w<48||h<48||gray.length!=w*h){
            previous=null;lastBox=null;return;
        }
        previous=gray.clone();width=w;height=h;
        lastBox=detected;lastSeenMs=lastFrameMs=ms;
    }
    private static int pixel(byte[] g,int w,int x,int y){return g[y*w+x]&255;}
    private static float median(ArrayList<Float> vals){
        Collections.sort(vals);int n=vals.size();
        return n%2==1?vals.get(n/2):(vals.get(n/2-1)+vals.get(n/2))*.5f;
    }
    private static boolean in(int x,int y,int w,int h,int margin){
        return x>=margin&&y>=margin&&x<w-margin&&y<h-margin;
    }
    private static final class Shift {
        final int dx,dy;
        final float score;
        Shift(int x,int y,float score){dx=x;dy=y;this.score=score;}
    }
    /**
     * Call only for an empty-face-detector frame; never when competing detected
     * faces may cause identity substitution. Returns null instead of guessing.
     */
    public Estimate advance(long ms,byte[] now,int w,int h,BodyClothing.Assist body){
        if(previous==null||lastBox==null||now==null||now.length!=w*h||
           w!=width||h!=height||ms<=lastFrameMs||ms-lastFrameMs>190)return null;
        if(ms-lastSeenMs>MAX_FACE_MISSING_MS){expired++;previous=null;return null;}
        ArrayList<Shift> shifts=new ArrayList<>();
        for(int sy:SY)for(int sx:SX){
            int cx=Math.round((lastBox.x+lastBox.w*sx/100f)*w);
            int cy=Math.round((lastBox.y+lastBox.h*sy/100f)*h);
            if(!in(cx,cy,w,h,SEARCH+PATCH+1))continue;
            float variance=0;
            int center=pixel(previous,w,cx,cy);
            for(int dy=-PATCH;dy<=PATCH;dy++)for(int dx=-PATCH;dx<=PATCH;dx++)
                variance+=Math.abs(pixel(previous,w,cx+dx,cy+dy)-center);
            if(variance/25f<7f)continue; // exclude featureless skin/background
            float best=Float.MAX_VALUE;
            int bestDx=0,bestDy=0;
            for(int dy=-SEARCH;dy<=SEARCH;dy++)for(int dx=-SEARCH;dx<=SEARCH;dx++){
                int sum=0;
                // Sample 5x5 individual pixels; score is mean photometric difference.
                for(int py=-PATCH;py<=PATCH;py++)for(int px=-PATCH;px<=PATCH;px++)
                    sum+=Math.abs(pixel(previous,w,cx+px,cy+py)-
                                 pixel(now,w,cx+dx+px,cy+dy+py));
                float cost=sum/25f;
                // prefer a stationary match when costs are otherwise equal
                cost+=.03f*(Math.abs(dx)+Math.abs(dy));
                if(cost<best){best=cost;bestDx=dx;bestDy=dy;}
            }
            if(best<23f)shifts.add(new Shift(bestDx,bestDy,best));
        }
        if(shifts.size()<7){rejected++;previous=null;return null;}
        ArrayList<Float> xs=new ArrayList<>(),ys=new ArrayList<>();
        for(Shift m:shifts){xs.add((float)m.dx);ys.add((float)m.dy);}
        float mx=median(xs),my=median(ys);
        ArrayList<Shift> inliers=new ArrayList<>();
        float error=0;
        for(Shift m:shifts){
            if(Math.hypot(m.dx-mx,m.dy-my)<=3.0){
                inliers.add(m);error+=m.score;
            }
        }
        if(inliers.size()<7 || inliers.size()<(int)Math.ceil(shifts.size()*.65f)){
            rejected++;previous=null;return null;
        }
        error/=inliers.size();
        if(error>15f){rejected++;previous=null;return null;}
        ArrayList<Float> ix=new ArrayList<>(),iy=new ArrayList<>();
        for(Shift m:inliers){ix.add((float)m.dx);iy.add((float)m.dy);}
        mx=median(ix);my=median(iy);
        float nx=lastBox.x+mx/w,ny=lastBox.y+my/h;
        float cw=lastBox.w,ch=lastBox.h;
        // Keep box dimensions consistent: flow only estimates translation.
        if(nx+cw<.06f||ny+ch<.06f||nx>.94f||ny>.94f){
            rejected++;previous=null;return null;
        }
        nx=Math.max(0,Math.min(1-cw,nx));
        ny=Math.max(0,Math.min(1-ch,ny));
        FacePath.Box next=new FacePath.Box(nx,ny,cw,ch,lastBox.id,
                                          lastBox.appearance,false,false);
        if(body!=null&&body.confirmed&&body.confidence>=.80f&&!body.near(next)){
            rejected++;previous=null;return null;
        }
        float inlierRatio=inliers.size()/(float)shifts.size();
        float confidence=(float)Math.min(1,.56+.44*inlierRatio)*
                          Math.max(0,(25f-error)/25f);
        if(confidence<.72f){rejected++;previous=null;return null;}
        previous=now.clone();lastBox=next;lastFrameMs=ms;estimated++;
        return new Estimate(next,confidence,inliers.size());
    }
}
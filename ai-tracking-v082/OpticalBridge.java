package kr.ledoa.cut.aitest;
import java.util.*;

/** Pyramidal feature tracking during detector blackouts only.
 * Provisional motion is never face identity evidence, and never updates FacePath.
 */
public final class OpticalBridge {
    public static final long MAX_FACE_MISSING_MS=600L;
    public static final class Estimate {
        public final FacePath.Box box;
        public final float confidence;
        public final int consistentPatches;
        Estimate(FacePath.Box b,float c,int n){box=b;confidence=c;consistentPatches=n;}
    }
    private static final int LEVELS=3,WINDOW=3,MAX_POINTS=90,PATCH=3;
    private static final class Pix {
        final float x,y;
        Pix(float x,float y){this.x=x;this.y=y;}
    }
    private static final class Pyramid {
        final int[] w=new int[LEVELS],h=new int[LEVELS];
        final byte[][] image=new byte[LEVELS][];
        Pyramid(byte[] base,int width,int height){
            image[0]=base;w[0]=width;h[0]=height;
            for(int l=1;l<LEVELS;l++){
                w[l]=(w[l-1]+1)/2;h[l]=(h[l-1]+1)/2;
                image[l]=new byte[w[l]*h[l]];
                for(int y=0;y<h[l];y++)for(int x=0;x<w[l];x++){
                    int sum=0,n=0;
                    for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){
                        int ix=x*2+dx,iy=y*2+dy;
                        if(ix<w[l-1]&&iy<h[l-1]){
                            sum+=image[l-1][iy*w[l-1]+ix]&255;n++;
                        }
                    }
                    image[l][y*w[l]+x]=(byte)(sum/n);
                }
            }
        }
    }
    private Pyramid previous;
    private ArrayList<Pix> points=new ArrayList<>();
    private int width,height;
    private long lastSeenMs=-1,lastFrameMs=-1;
    private FacePath.Box lastBox;
    private int estimated=0,rejected=0,expired=0,reasonFew=0,reasonPhotometric=0,
                reasonGeometry=0,reasonBody=0,reasonOutside=0,reasonElapsed=0,
                reasonNoAnchor=0;
    public void reset(){
        previous=null;points.clear();lastBox=null;width=height=0;
        lastSeenMs=lastFrameMs=-1;estimated=rejected=expired=0;
        reasonFew=reasonPhotometric=reasonGeometry=reasonBody=reasonOutside=reasonElapsed=reasonNoAnchor=0;
    }
    public void invalidate(){
        previous=null;points.clear();lastBox=null;lastSeenMs=lastFrameMs=-1;
    }
    public int estimatedCount(){return estimated;}
    public int rejectedCount(){return rejected;}
    public int expiredCount(){return expired;}
    public int rejectedFewPoints(){return reasonFew;}
    public int rejectedPhotometric(){return reasonPhotometric;}
    public int rejectedGeometry(){return reasonGeometry;}
    public int rejectedBody(){return reasonBody;}
    public int rejectedOutside(){return reasonOutside;}
    public int rejectedElapsed(){return reasonElapsed;}
    public int rejectedNoAnchor(){return reasonNoAnchor;}
    private static float bilinear(byte[] img,int w,int h,float x,float y){
        if(x<0||y<0||x>=w-1||y>=h-1)return -1f;
        int ix=(int)x,iy=(int)y;float fx=x-ix,fy=y-iy;
        int i=iy*w+ix;
        float tl=img[i]&255,tr=img[i+1]&255,bl=img[i+w]&255,br=img[i+w+1]&255;
        return (tl+(tr-tl)*fx)*(1-fy)+(bl+(br-bl)*fx)*fy;
    }
    private static ArrayList<Pix> corners(Pyramid pyr,FacePath.Box face){
        ArrayList<Pix> result=new ArrayList<>();
        int w=pyr.w[0],h=pyr.h[0];byte[] im=pyr.image[0];
        int left=Math.max(5,(int)Math.floor(face.x*w)+2);
        int top=Math.max(5,(int)Math.floor(face.y*h)+2);
        int right=Math.min(w-5,(int)Math.ceil((face.x+face.w)*w)-2);
        int bottom=Math.min(h-5,(int)Math.ceil((face.y+face.h)*h)-2);
        // Image corner strength excludes flat skin patches that match strangers.
        ArrayList<float[]> candidates=new ArrayList<>();
        for(int y=top;y<bottom;y+=2)for(int x=left;x<right;x+=2){
            float a=0,b=0,c=0;
            for(int dy=-2;dy<=2;dy+=2)for(int dx=-2;dx<=2;dx+=2){
                int i=(y+dy)*w+(x+dx);
                float gx=((im[i+1]&255)-(im[i-1]&255))*.5f;
                float gy=((im[i+w]&255)-(im[i-w]&255))*.5f;
                a+=gx*gx;b+=gx*gy;c+=gy*gy;
            }
            float smaller=(a+c-(float)Math.sqrt((a-c)*(a-c)+4*b*b))*.5f;
            if(smaller>360f)candidates.add(new float[]{smaller,x,y});
        }
        candidates.sort((a,b)->Float.compare(b[0],a[0]));
        for(float[] candidate:candidates){
            boolean close=false;
            for(Pix chosen:result){
                float dx=chosen.x-candidate[1],dy=chosen.y-candidate[2];
                if(dx*dx+dy*dy<24f){close=true;break;}
            }
            if(!close)result.add(new Pix(candidate[1],candidate[2]));
            if(result.size()>=MAX_POINTS)break;
        }
        return result;
    }
    public void anchor(long ms,byte[] gray,int w,int h,FacePath.Box detected){
        if(gray==null||detected==null||w<48||h<48||gray.length!=w*h){
            invalidate();return;
        }
        Pyramid p=new Pyramid(gray.clone(),w,h);
        ArrayList<Pix> found=corners(p,detected);
        if(found.size()<7){invalidate();return;}
        previous=p;points=found;width=w;height=h;
        lastBox=detected;lastSeenMs=lastFrameMs=ms;
    }
    private static Pix follow(Pyramid src,Pyramid dst,Pix start){
        float u=0,v=0;
        for(int level=LEVELS-1;level>=0;level--){
            if(level<LEVELS-1){u*=2;v*=2;}
            float scale=1f/(1<<level),x=start.x*scale,y=start.y*scale;
            int w=src.w[level],h=src.h[level];
            byte[] original=src.image[level],current=dst.image[level];
            if(x<WINDOW+2||y<WINDOW+2||x>=w-WINDOW-3||y>=h-WINDOW-3)return null;
            for(int iteration=0;iteration<12;iteration++){
                float aa=0,ab=0,bb=0,ax=0,by=0;
                for(int py=-PATCH;py<=PATCH;py++)for(int px=-PATCH;px<=PATCH;px++){
                    float bx=x+px,cy=y+py,tx=bx+u,ty=cy+v;
                    float a=bilinear(original,w,h,bx,cy);
                    float b=bilinear(current,w,h,tx,ty);
                    float xp=bilinear(current,w,h,tx+1,ty);
                    float xm=bilinear(current,w,h,tx-1,ty);
                    float yp=bilinear(current,w,h,tx,ty+1);
                    float ym=bilinear(current,w,h,tx,ty-1);
                    if(Math.min(Math.min(a,b),Math.min(Math.min(xp,xm),Math.min(yp,ym)))<0)return null;
                    float gx=(xp-xm)*.5f,gy=(yp-ym)*.5f;
                    float residual=a-b;
                    aa+=gx*gx;ab+=gx*gy;bb+=gy*gy;
                    ax+=gx*residual;by+=gy*residual;
                }
                float det=aa*bb-ab*ab;
                if(det<5000f)return null;
                float dx=(bb*ax-ab*by)/det,dy=(aa*by-ab*ax)/det;
                if(!Float.isFinite(dx)||!Float.isFinite(dy))return null;
                if(Math.abs(dx)>5||Math.abs(dy)>5)return null;
                u+=dx;v+=dy;
                if(Math.abs(u)>14||Math.abs(v)>14)return null;
                if(dx*dx+dy*dy<.01f)break;
            }
        }
        float nx=start.x+u,ny=start.y+v;
        if(nx<5||ny<5||nx>dst.w[0]-6||ny>dst.h[0]-6)return null;
        float sad=0;
        for(int dy=-PATCH;dy<=PATCH;dy++)for(int dx=-PATCH;dx<=PATCH;dx++){
            float a=bilinear(src.image[0],src.w[0],src.h[0],start.x+dx,start.y+dy);
            float b=bilinear(dst.image[0],dst.w[0],dst.h[0],nx+dx,ny+dy);
            if(a<0||b<0)return null;
            sad+=Math.abs(a-b);
        }
        if(sad/49f>32f)return null;
        return new Pix(nx,ny);
    }
    private static final class Pair {
        final Pix from,to;
        Pair(Pix a,Pix b){from=a;to=b;}
    }
    private static final class Fit {
        final float scale,tx,ty;
        Fit(float scale,float tx,float ty){this.scale=scale;this.tx=tx;this.ty=ty;}
    }
    private static Fit fit(List<Pair> pairs){
        float mx=0,my=0,nx=0,ny=0;
        for(Pair p:pairs){
            mx+=p.from.x;my+=p.from.y;nx+=p.to.x;ny+=p.to.y;
        }
        int n=pairs.size();if(n<5)return null;
        mx/=n;my/=n;nx/=n;ny/=n;
        float numerator=0,denominator=0;
        for(Pair p:pairs){
            float x=p.from.x-mx,y=p.from.y-my;
            numerator+=x*(p.to.x-nx)+y*(p.to.y-ny);
            denominator+=x*x+y*y;
        }
        if(denominator<25f)return null;
        float scale=numerator/denominator;
        return new Fit(scale,nx-scale*mx,ny-scale*my);
    }
    private static float residual(Pair p,Fit f){
        return (float)Math.hypot(f.scale*p.from.x+f.tx-p.to.x,
                                 f.scale*p.from.y+f.ty-p.to.y);
    }
    private Estimate reject(int category){
        rejected++;
        if(category==0)reasonFew++;
        else if(category==1)reasonPhotometric++;
        else if(category==2)reasonGeometry++;
        else if(category==3)reasonBody++;
        else reasonOutside++;
        // Fail closed: uncertain movement never becomes a legitimate face detection.
        invalidate();return null;
    }
    public Estimate advance(long ms,byte[] now,int w,int h,BodyClothing.Assist body){
        if(previous==null||lastBox==null||now==null||w!=width||h!=height||now.length!=w*h){
            reasonNoAnchor++;return null;
        }
        if(ms<=lastFrameMs||ms-lastFrameMs>190){
            reasonElapsed++;return reject(0);
        }
        if(ms-lastSeenMs>MAX_FACE_MISSING_MS){
            expired++;invalidate();return null;
        }
        Pyramid next=new Pyramid(now.clone(),w,h);
        ArrayList<Pair> pairs=new ArrayList<>();
        for(Pix feature:points){
            Pix after=follow(previous,next,feature);
            if(after==null)continue;
            Pix reverse=follow(next,previous,after);
            if(reverse==null||Math.hypot(reverse.x-feature.x,reverse.y-feature.y)>1.65)continue;
            pairs.add(new Pair(feature,after));
        }
        if(pairs.size()<7)return reject(0);
        Fit geometry=fit(pairs);
        if(geometry==null||geometry.scale<.84f||geometry.scale>1.20f)return reject(2);
        ArrayList<Pair> good=new ArrayList<>();
        for(Pair p:pairs)if(residual(p,geometry)<5f)good.add(p);
        if(good.size()<7||good.size()*1f/pairs.size()<.53f)return reject(2);
        Fit robust=fit(good);
        if(robust==null||robust.scale<.84f||robust.scale>1.20f)return reject(2);
        ArrayList<Pair> finalPairs=new ArrayList<>();
        for(Pair p:good)if(residual(p,robust)<3.8f)finalPairs.add(p);
        if(finalPairs.size()<7||finalPairs.size()*1f/pairs.size()<.34f)return reject(2);
        Fit finalFit=fit(finalPairs);
        if(finalFit==null)return reject(2);
        float x=(lastBox.x*w)*finalFit.scale+finalFit.tx;
        float y=(lastBox.y*h)*finalFit.scale+finalFit.ty;
        float newWidth=lastBox.w*finalFit.scale,newHeight=lastBox.h*finalFit.scale;
        float l=Math.max(0,x/w),t=Math.max(0,y/h);
        float r=Math.min(1f,x/w+newWidth),b=Math.min(1f,y/h+newHeight);
        if(r-l<.07f||b-t<.055f||r-l>.98f||b-t>.93f)return reject(4);
        FacePath.Box prediction=new FacePath.Box(l,t,r-l,b-t,lastBox.id,
                                                 lastBox.appearance,false,false);
        if(body!=null&&body.confirmed&&body.confidence>=.80f&&!body.near(prediction))return reject(3);
        float inlier=finalPairs.size()/(float)pairs.size();
        float confidence=Math.min(.95f,.70f+inlier*.22f+Math.min(finalPairs.size(),24)*.001f);
        if(confidence<.78f)return reject(1);
        points.clear();
        for(Pair p:finalPairs)points.add(p.to);
        if(points.size()<18){
            ArrayList<Pix> fresh=corners(next,prediction);
            if(fresh.size()>=14)points=fresh;
        }
        previous=next;lastFrameMs=ms;lastBox=prediction;estimated++;
        return new Estimate(prediction,confidence,finalPairs.size());
    }
}

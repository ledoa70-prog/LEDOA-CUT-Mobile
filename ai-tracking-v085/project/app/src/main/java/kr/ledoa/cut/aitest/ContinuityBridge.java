package kr.ledoa.cut.aitest;
import java.util.*;

/** Bounded 50ms grayscale cache. No extra face inference, no frame re-seeks.
 * Reverse repairs must close onto a real anchor or two consistent forward
 * projections. All repairs stay FLOW_ESTIMATED and require visual review.
 */
public final class ContinuityBridge {
    private static final class Sample {
        final long ms; final byte[] gray; final int w,h;
        Sample(long ms,byte[] gray,int w,int h){this.ms=ms;this.gray=gray;this.w=w;this.h=h;}
    }
    private final FacePath path;
    private final LazyFlowBridge lazy;
    private final ArrayList<Sample> cache=new ArrayList<>();
    private long advanced=-1;
    private int reversed=0,peakFrames=0;
    private boolean hasGap=false;
    public ContinuityBridge(FacePath path,LazyFlowBridge lazy){this.path=path;this.lazy=lazy;}
    public void reset(){cache.clear();advanced=-1;reversed=peakFrames=0;hasGap=false;lazy.reset();}
    public int reverseCount(){return reversed;}
    public int peakCachedFrames(){return peakFrames;}
    public void cache(long ms,byte[] gray,int w,int h){
        if(gray==null||gray.length!=w*h)return;
        if(!cache.isEmpty()&&ms<=cache.get(cache.size()-1).ms)return;
        cache.add(new Sample(ms,gray,w,h));
        while(!cache.isEmpty()&&(cache.size()>40||ms-cache.get(0).ms>OpticalBridge.MAX_FACE_MISSING_MS))cache.remove(0);
        peakFrames=Math.max(peakFrames,cache.size());
    }
    public FacePath.Point process(long ms,byte[] gray,int w,int h,FacePath.Point point,BodyClothing.Assist body){
        cache(ms,gray,w,h);
        if(point.status==FacePath.Status.TRACKED||point.status==FacePath.Status.VERIFIED){
            if(hasGap&&cache.size()>2)repair(ms,gray,w,h,point.box);
            lazy.trusted(ms,gray,w,h,point.box);
            cache.clear();cache(ms,gray,w,h);advanced=ms;hasGap=false;
            return point;
        }
        hasGap=true;
        OpticalBridge.Estimate estimate=null;
        for(Sample s:cache){
            if(s.ms<=advanced||s.ms>ms)continue;
            estimate=lazy.gap(s.ms,s.gray,s.w,s.h,s.ms==ms?body:null,true);
            advanced=s.ms;
        }
        if(estimate!=null)
            point=path.putContinuity(ms,estimate.box,estimate.confidence,"FORWARD_HEAD_MOTION_REVIEW");
        return point;
    }
    static boolean agrees(FacePath.Box a,FacePath.Box b){
        if(a==null||b==null)return false;
        float ix=Math.max(0,Math.min(a.x+a.w,b.x+b.w)-Math.max(a.x,b.x));
        float iy=Math.max(0,Math.min(a.y+a.h,b.y+b.h)-Math.max(a.y,b.y));
        float inter=ix*iy,iou=inter/Math.max(.0001f,a.area()+b.area()-inter);
        float ratio=b.area()/Math.max(.0001f,a.area());
        return iou>=.40f&&ratio>.45f&&ratio<2.2f&&a.dist(b)<.30f*Math.max(a.w,a.h);
    }
    private void repair(long ms,byte[] gray,int w,int h,FacePath.Box real){
        OpticalBridge reverse=new OpticalBridge();reverse.anchor(0,gray,w,h,real);
        ArrayList<FacePath.Point> proposals=new ArrayList<>();
        int agreeing=0;boolean closed=false;
        for(int i=cache.size()-2;i>=0;i--){
            Sample s=cache.get(i);
            OpticalBridge.Estimate e=reverse.advance(ms-s.ms,s.gray,s.w,s.h,null);
            if(e==null)break;
            FacePath.Point old=path.exact(s.ms);
            if(old==null)continue;
            if(old.box!=null){
                if(!agrees(old.box,e.box))break;
                if(old.status==FacePath.Status.TRACKED||old.status==FacePath.Status.VERIFIED||++agreeing>=2){closed=true;break;}
            }else {
                proposals.add(new FacePath.Point(s.ms,e.box,FacePath.Status.FLOW_ESTIMATED,old.candidates,"BIDIRECTIONAL_HEAD_MOTION_REVIEW"));
            }
        }
        if(closed)for(FacePath.Point p:proposals){
            FacePath.Point before=path.exact(p.ms);
            path.putContinuity(p.ms,p.box,.85f,p.reason);
            if(before.box==null&&path.exact(p.ms).box!=null)reversed++;
        }
    }
}

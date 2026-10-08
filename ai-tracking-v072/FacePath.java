package kr.ledoa.cut.aitest;

import java.util.*;

/** User-selected face trajectory. Coordinates are normalized to the displayed video frame. */
public final class FacePath {
    public enum Status { TRACKED, UNCERTAIN, LOST, VERIFIED }
    public static final class Box {
        public final float x,y,w,h;
        public final int id;
        public Box(float x,float y,float w,float h,int id){this.x=x;this.y=y;this.w=w;this.h=h;this.id=id;}
        public float cx(){return x+w/2f;}
        public float cy(){return y+h/2f;}
        public float area(){return w*h;}
        public float dist(Box b){return (float)Math.hypot(cx()-b.cx(),cy()-b.cy());}
        public boolean contains(float u,float v){return u>=x&&u<=x+w&&v>=y&&v<=y+h;}
        public Box expanded(float margin){
            float xx=Math.max(0,x-w*margin),yy=Math.max(0,y-h*margin);
            float x2=Math.min(1,x+w*(1+margin)),y2=Math.min(1,y+h*(1+margin));
            return new Box(xx,yy,x2-xx,y2-yy,id);
        }
    }
    public static final class Point {
        public final long ms; public final Box box; public final Status status;
        public final int candidates; public final String reason;
        public Point(long ms,Box box,Status status){this(ms,box,status,-1,"");}
        public Point(long ms,Box box,Status status,int candidates,String reason){
            this.ms=ms;this.box=box;this.status=status;this.candidates=candidates;this.reason=reason;
        }
    }
    public static final class Pick {
        public final Box box; public final Status status; public final String reason;
        Pick(Box box,Status status,String reason){this.box=box;this.status=status;this.reason=reason;}
    }
    private final TreeMap<Long,Point> frames=new TreeMap<>();
    private Box last,previous,pending;
    private int lastId=-1;
    private long lastMs=-1,previousMs=-1,pendingMs=-1;
    public synchronized void reset(){
        frames.clear();last=null;previous=null;pending=null;lastId=-1;lastMs=-1;previousMs=-1;pendingMs=-1;
    }
    public synchronized void anchor(long ms,Box box){
        Objects.requireNonNull(box);
        frames.tailMap(ms,true).clear();
        last=box;lastId=box.id;lastMs=ms;previous=null;previousMs=-1;pending=null;pendingMs=-1;
        frames.put(ms,new Point(ms,box,Status.VERIFIED,-1,"USER_SELECTED"));
    }
    public synchronized Point step(long ms,List<Box> found){
        if(last==null)throw new IllegalStateException("Select face before tracking");
        if(ms<=lastMs)throw new IllegalArgumentException("Samples must be forward and strictly increasing");
        List<Box> candidates=found==null?Collections.emptyList():found;
        long gap=ms-lastMs;
        Pick pick=chooseMotion(last,previous,lastId,gap,candidates);
        if(pick.status!=Status.TRACKED){
            Pick recovered=tryShortReacquisition(ms,gap,candidates);
            if(recovered!=null)pick=recovered;
        }else{pending=null;pendingMs=-1;}
        Point out=new Point(ms,pick.box,pick.status,candidates.size(),pick.reason);
        frames.put(ms,out);
        if(pick.status==Status.TRACKED){
            previous=last;previousMs=lastMs;last=pick.box;lastId=pick.box.id;lastMs=ms;
            pending=null;pendingMs=-1;
        }
        return out;
    }
    private Pick tryShortReacquisition(long ms,long gap,List<Box> found){
        // Do not automatically switch identity after extended absence or amongst multiple faces.
        if(found.size()!=1||gap>1600){pending=null;pendingMs=-1;return null;}
        Box candidate=found.get(0);
        float distance=last.dist(candidate);
        boolean sameId=lastId>=0&&candidate.id>=0&&lastId==candidate.id;
        if(distance>.34f || (!sameId&&gap>650)){
            pending=null;pendingMs=-1;return null;
        }
        if(pending!=null && ms-pendingMs<=250 && pending.dist(candidate)<.12f &&
                scaleRatio(pending,candidate)>.4 && scaleRatio(pending,candidate)<2.5 &&
                (sameId||gap<=650)) {
            return new Pick(candidate,Status.TRACKED,"REACQUIRED_TWO_SAMPLES");
        }
        pending=candidate;pendingMs=ms;
        return new Pick(null,Status.UNCERTAIN,"REACQUIRE_CONFIRM_NEXT_SAMPLE");
    }
    private static double scaleRatio(Box a,Box b){return b.area()/Math.max(.0001,a.area());}
    private Pick chooseMotion(Box last,Box before,int id,long gap,List<Box> found){
        if(found.isEmpty())return new Pick(null,Status.LOST,"NO_FACE_DETECTED");
        Box prediction=last;
        if(before!=null&&previousMs>=0&&lastMs>previousMs&&gap<=450){
            float velocityX=last.cx()-before.cx(),velocityY=last.cy()-before.cy();
            float m=Math.min(2.0f,(float)gap/(lastMs-previousMs));
            float dx=Math.max(-.20f,Math.min(.20f,velocityX*m));
            float dy=Math.max(-.20f,Math.min(.20f,velocityY*m));
            prediction=new Box(last.x+dx,last.y+dy,last.w,last.h,last.id);
        }
        double maxStep=Math.min(.32,.064+.38*Math.max(last.w,last.h)+.00016*Math.min(gap,800));
        double best=Double.POSITIVE_INFINITY,second=Double.POSITIVE_INFINITY;
        Box winner=null;
        for(Box b:found){
            double relArea=scaleRatio(last,b);
            if(relArea<.25||relArea>4.5)continue;
            double direct=last.dist(b),predicted=prediction.dist(b);
            if(direct>maxStep && !(gap<=450 && direct<maxStep*1.7 && predicted<maxStep))continue;
            double distance=Math.min(direct,predicted+.012);
            double idPenalty=id>=0&&b.id>=0&&id!=b.id?.035:0;
            double score=distance+Math.abs(Math.log(relArea))*.035+idPenalty;
            if(score<best){second=best;best=score;winner=b;}
            else if(score<second)second=score;
        }
        if(winner==null)return new Pick(null,Status.LOST,"MOTION_OR_SCALE_GATE");
        for(Box b:found){
            if(b!=winner && b.dist(winner)<Math.max(.05f,Math.min(winner.w,winner.h)*.65f))
                return new Pick(null,Status.UNCERTAIN,"FACES_OVERLAP");
        }
        if(second-best<.030)return new Pick(null,Status.UNCERTAIN,"TWO_SIMILAR_FACES");
        if(gap>650 && id>=0 && winner.id>=0 && winner.id!=id)
            return new Pick(null,Status.UNCERTAIN,"FACE_ID_CHANGED_AFTER_GAP");
        return new Pick(winner,Status.TRACKED,"ADAPTIVE_MOTION_MATCH");
    }
    /** API compatibility for the original safety checks. */
    public static Pick choose(Box previous,int previousId,long deltaMs,List<Box> found){
        FacePath temp=new FacePath();temp.last=previous;temp.lastId=previousId;
        return temp.chooseMotion(previous,null,previousId,deltaMs,found==null?Collections.emptyList():found);
    }
    public synchronized void review(long ms,Box box){anchor(ms,box);}
    public synchronized List<Point> points(){return new ArrayList<>(frames.values());}
    public synchronized int reviewCount(){int n=0;for(Point p:frames.values())if(p.status!=Status.TRACKED&&p.status!=Status.VERIFIED)n++;return n;}
    public synchronized Point nearest(long ms){
        Map.Entry<Long,Point> a=frames.floorEntry(ms),b=frames.ceilingEntry(ms);
        if(a==null)return b==null?null:b.getValue();
        if(b==null)return a.getValue();
        return ms-a.getKey()<=b.getKey()-ms?a.getValue():b.getValue();
    }
    public synchronized Point interpolated(long ms){
        Map.Entry<Long,Point> a=frames.floorEntry(ms),b=frames.ceilingEntry(ms);
        if(a==null||b==null)return null;
        Point p=a.getValue(),q=b.getValue();
        if(p.box==null||q.box==null)return null;
        if(p.status==Status.LOST||p.status==Status.UNCERTAIN||q.status==Status.LOST||q.status==Status.UNCERTAIN)return null;
        if(q.ms-p.ms>160)return null;
        if(q.ms==p.ms)return p;
        float t=(float)(ms-p.ms)/(q.ms-p.ms);
        Box x=p.box,y=q.box;
        return new Point(ms,new Box(lerp(x.x,y.x,t),lerp(x.y,y.y,t),lerp(x.w,y.w,t),lerp(x.h,y.h,t),y.id),Status.TRACKED);
    }
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
}

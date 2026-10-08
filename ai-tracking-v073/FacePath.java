package kr.ledoa.cut.aitest;
import java.util.*;

/** A selected face path with deliberately conservative re-identification.
 *  ML Kit tracking IDs are NOT identities. When in doubt, emit UNCERTAIN/LOST.
 */
public final class FacePath {
    public enum Status { TRACKED, UNCERTAIN, LOST, VERIFIED }
    public static final class Box {
        public final float x,y,w,h; public final int id; public final float[] appearance;
        public Box(float x,float y,float w,float h,int id){this(x,y,w,h,id,null);}
        public Box(float x,float y,float w,float h,int id,float[] appearance){
            this.x=x;this.y=y;this.w=w;this.h=h;this.id=id;this.appearance=appearance;
        }
        public float cx(){return x+w/2f;}
        public float cy(){return y+h/2f;}
        public float area(){return w*h;}
        public float dist(Box b){return (float)Math.hypot(cx()-b.cx(),cy()-b.cy());}
        public boolean contains(float u,float v){return u>=x&&u<=x+w&&v>=y&&v<=y+h;}
        public Box expanded(float margin){
            float xx=Math.max(0,x-w*margin),yy=Math.max(0,y-h*margin);
            float x2=Math.min(1,x+w*(1+margin)),y2=Math.min(1,y+h*(1+margin));
            return new Box(xx,yy,x2-xx,y2-yy,id,appearance);
        }
    }
    public static final class Point {
        public final long ms;public final Box box;public final Status status;
        public final int candidates;public final String reason;
        public Point(long ms,Box box,Status status){this(ms,box,status,-1,"");}
        public Point(long ms,Box box,Status status,int candidates,String reason){
            this.ms=ms;this.box=box;this.status=status;this.candidates=candidates;this.reason=reason;
        }
    }
    public static final class Pick {
        public final Box box;public final Status status;public final String reason;
        Pick(Box box,Status status,String reason){this.box=box;this.status=status;this.reason=reason;}
    }
    private final TreeMap<Long,Point> frames=new TreeMap<>();
    private final ArrayList<float[]> appearanceGallery=new ArrayList<>();
    private Box last,previous,pending;private int confirmations=0;
    private long lastMs=-1,previousMs=-1,pendingMs=-1;
    private int lastId=-1;
    public synchronized void reset(){frames.clear();appearanceGallery.clear();last=previous=pending=null;lastMs=previousMs=pendingMs=-1;lastId=-1;confirmations=0;}
    public synchronized void anchor(long ms,Box b){
        Objects.requireNonNull(b);frames.tailMap(ms,true).clear();
        last=b;lastMs=ms;lastId=b.id;previous=null;previousMs=-1;pending=null;pendingMs=-1;confirmations=0;
        appearanceGallery.clear();if(b.appearance!=null)appearanceGallery.add(b.appearance);
        frames.put(ms,new Point(ms,b,Status.VERIFIED,-1,"USER_SELECTED"));
    }
    private static double scale(Box a,Box b){return b.area()/Math.max(.0001f,a.area());}
    private float appearance(Box b){
        if(b.appearance==null||appearanceGallery.isEmpty())return -1;
        float best=-1;
        for(float[] ref:appearanceGallery)best=Math.max(best,FaceAppearance.score(ref,b.appearance));
        return best;
    }
    private static boolean duplicate(Box a,Box b){
        float left=Math.max(a.x,b.x),top=Math.max(a.y,b.y),right=Math.min(a.x+a.w,b.x+b.w),bottom=Math.min(a.y+a.h,b.y+b.h);
        float intersection=Math.max(0,right-left)*Math.max(0,bottom-top);
        return intersection/Math.max(.0001f,a.area()+b.area()-intersection)>.55f;
    }
    private static List<Box> unique(List<Box> found){
        if(found==null||found.isEmpty())return Collections.emptyList();
        ArrayList<Box> out=new ArrayList<>();
        for(Box b:found){
            if(b==null||b.w<.025f||b.h<.025f||b.w*b.h>.82f)continue;
            boolean seen=false;
            for(Box q:out)if(duplicate(b,q)){seen=true;break;}
            if(!seen)out.add(b);
        }
        return out;
    }
    public synchronized Point step(long ms,List<Box> found){
        if(last==null)throw new IllegalStateException("First select a face");
        Map.Entry<Long,Point> tail=frames.lastEntry();
        if(tail!=null&&ms<=tail.getKey())throw new IllegalArgumentException("Samples must be chronological");
        List<Box> cs=unique(found);
        long gap=ms-lastMs;
        Pick chosen=chooseMotion(last,previous,lastId,gap,cs);
        if(chosen.status!=Status.TRACKED)chosen=reacquire(ms,gap,cs,chosen);
        Point point=new Point(ms,chosen.box,chosen.status,cs.size(),chosen.reason);
        frames.put(ms,point);
        if(chosen.status==Status.TRACKED){
            previous=last;previousMs=lastMs;last=chosen.box;lastMs=ms;lastId=chosen.box.id;
            if(chosen.box.appearance!=null && appearanceGallery.size()<7 && appearance(chosen.box)>.78f)
                appearanceGallery.add(chosen.box.appearance);
            pending=null;pendingMs=-1;confirmations=0;
        }
        return point;
    }
    private Pick chooseMotion(Box from,Box before,int id,long gap,List<Box> candidates){
        if(candidates.isEmpty())return new Pick(null,Status.LOST,"NO_FACE_DETECTED");
        if(gap>850)return new Pick(null,Status.LOST,"LONG_GAP_REIDENTIFICATION_REQUIRED");
        float predX=from.cx(),predY=from.cy();
        if(before!=null&&previousMs>=0&&lastMs>previousMs&&gap<500){
            float ratio=Math.min(2f,gap/(float)(lastMs-previousMs));
            predX+=Math.max(-.16f,Math.min(.16f,(from.cx()-before.cx())*ratio));
            predY+=Math.max(-.16f,Math.min(.16f,(from.cy()-before.cy())*ratio));
        }
        float allowed=(float)Math.min(.42,.070+.42*Math.max(from.w,from.h)+.00022*Math.min(gap,850));
        double best=Double.POSITIVE_INFINITY,runnerUp=Double.POSITIVE_INFINITY;Box bestBox=null;
        for(Box b:candidates){
            double size=scale(from,b);
            if(size<.18||size>6.0)continue;
            double dist=Math.hypot(predX-b.cx(),predY-b.cy());
            if(dist>allowed)continue;
            double score=dist+Math.abs(Math.log(size))*.025;
            float appearance=appearance(b);
            if(appearance>=0)score+=Math.max(0,.8-appearance)*.16;
            // Tracking ID deliberately not used for rejection or identity decisions.
            if(score<best){runnerUp=best;best=score;bestBox=b;}
            else if(score<runnerUp)runnerUp=score;
        }
        if(bestBox==null)return new Pick(null,Status.LOST,"MOTION_OR_SCALE_GATE");
        if(runnerUp-best<.025)return new Pick(null,Status.UNCERTAIN,"AMBIGUOUS_MULTIPLE_FACES");
        return new Pick(bestBox,Status.TRACKED,"MOTION_AND_APPEARANCE_MATCH");
    }
    private Pick reacquire(long ms,long gap,List<Box> candidates,Pick previousDecision){
        if(candidates.isEmpty()){pending=null;pendingMs=-1;confirmations=0;return previousDecision;}
        if(gap<250 && previousDecision.status==Status.UNCERTAIN)return previousDecision;
        Box winner=null;float score=-2,runnerUp=-2;
        for(Box b:candidates){
            float value=appearance(b);
            if(value<0){
                // Tests without appearance signals: recovery only very near the lost location.
                if(gap>650||last.dist(b)>.28)continue;
                value=.76f;
            }
            if(value>score){runnerUp=score;score=value;winner=b;}
            else if(value>runnerUp)runnerUp=value;
        }
        if(winner==null){pending=null;confirmations=0;return previousDecision;}
        // Coarse appearance scores are weak evidence; reject mismatches and ambiguity.
        if(score<.73f){pending=null;confirmations=0;return new Pick(null,Status.UNCERTAIN,"APPEARANCE_MISMATCH_REVIEW");}
        if(runnerUp>=0 && score-runnerUp<.065f){pending=null;confirmations=0;return new Pick(null,Status.UNCERTAIN,"MULTIPLE_SIMILAR_FACES_REVIEW");}
        if(pending!=null && ms-pendingMs<=240 && pending.dist(winner)<.20f && scale(pending,winner)>.35 && scale(pending,winner)<3.0){
            confirmations++;pending=winner;pendingMs=ms;
        }else{pending=winner;pendingMs=ms;confirmations=1;}
        int needed=gap>850?3:2;
        if(confirmations>=needed){pending=null;confirmations=0;return new Pick(winner,Status.TRACKED,"REACQUIRED_APPEARANCE_STABLE");}
        return new Pick(null,Status.UNCERTAIN,"REACQUIRE_CONFIRMING_"+confirmations+"_OF_"+needed);
    }
    /** Kept for compatibility with earlier tracker safety tests. */
    public static Pick choose(Box previous,int previousId,long deltaMs,List<Box> found){
        FacePath a=new FacePath();a.last=previous;a.lastMs=0;a.lastId=previousId;
        return a.chooseMotion(previous,null,previousId,deltaMs,unique(found));
    }
    public synchronized void review(long ms,Box b){anchor(ms,b);}
    public synchronized List<Point> points(){return new ArrayList<>(frames.values());}
    public synchronized int reviewCount(){int n=0;for(Point p:frames.values())if(p.status!=Status.TRACKED&&p.status!=Status.VERIFIED)n++;return n;}
    public synchronized Point nearest(long ms){
        Map.Entry<Long,Point> a=frames.floorEntry(ms),b=frames.ceilingEntry(ms);
        if(a==null)return b==null?null:b.getValue();if(b==null)return a.getValue();
        return ms-a.getKey()<=b.getKey()-ms?a.getValue():b.getValue();
    }
    public synchronized Point interpolated(long ms){
        Map.Entry<Long,Point> a=frames.floorEntry(ms),b=frames.ceilingEntry(ms);
        if(a==null||b==null)return null;
        Point p=a.getValue(),q=b.getValue();
        if(p.box==null||q.box==null||p.status==Status.LOST||p.status==Status.UNCERTAIN||q.status==Status.LOST||q.status==Status.UNCERTAIN)return null;
        if(q.ms-p.ms>160)return null;if(q.ms==p.ms)return p;
        float f=(float)(ms-p.ms)/(q.ms-p.ms);Box x=p.box,y=q.box;
        return new Point(ms,new Box(x.x+(y.x-x.x)*f,x.y+(y.y-x.y)*f,x.w+(y.w-x.w)*f,x.h+(y.h-x.h)*f,y.id),Status.TRACKED);
    }
}

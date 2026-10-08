package kr.ledoa.cut.aitest;
import java.util.*;

/** A selected face path with deliberately conservative re-identification.
 *  ML Kit tracking IDs are NOT identities. When in doubt, emit UNCERTAIN/LOST.
 */
public final class FacePath {
    public enum Status { TRACKED, UNCERTAIN, LOST, BODY_HELD, FLOW_ESTIMATED, DETECTION_BRIDGED, VERIFIED }
    public static final class Box {
        public final float x,y,w,h; public final int id; public final float[] appearance; public final boolean faceEvidence,edgePartial;
        public Box(float x,float y,float w,float h,int id){this(x,y,w,h,id,null,true);}
        public Box(float x,float y,float w,float h,int id,float[] appearance){
            this(x,y,w,h,id,appearance,true,false);
        }
        public Box(float x,float y,float w,float h,int id,float[] appearance,boolean faceEvidence){
            this(x,y,w,h,id,appearance,faceEvidence,false);
        }
        public Box(float x,float y,float w,float h,int id,float[] appearance,boolean faceEvidence,boolean edgePartial){
            this.x=x;this.y=y;this.w=w;this.h=h;this.id=id;
            this.appearance=appearance;this.faceEvidence=faceEvidence;this.edgePartial=edgePartial;
        }
        public float cx(){return x+w/2f;}
        public float cy(){return y+h/2f;}
        public float area(){return w*h;}
        public float dist(Box b){return (float)Math.hypot(cx()-b.cx(),cy()-b.cy());}
        public boolean contains(float u,float v){return u>=x&&u<=x+w&&v>=y&&v<=y+h;}
        public Box expanded(float margin){
            float xx=Math.max(0,x-w*margin),yy=Math.max(0,y-h*margin);
            float x2=Math.min(1,x+w*(1+margin)),y2=Math.min(1,y+h*(1+margin));
            return new Box(xx,yy,x2-xx,y2-yy,id,appearance,faceEvidence,edgePartial);
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
    private int bodyValidatedCount=0,bodyRecoveredCount=0,edgeRecoveredCount=0;
    private int scaleRecoveredCount=0;
    private Box scalePending=null;
    private long scalePendingMs=-1;
    private int scalePendingCount=0;
    private int scaleReasonMismatch=0;
    private int wrongSizeBodyRejected=0;
    private int flowEstimatedCount=0;
    private int crowdedGapBlocked=0;
    private final TreeMap<Long,Box> confirmingFrames=new TreeMap<>();
    private int confirmedBackfills=0;
    private int confirmedGapReviews=0;
    private final TreeMap<Long,Box> manualKeyframes=new TreeMap<>();
    private int manualEditsMade=0;
    public synchronized int confirmedBackfillCount(){return confirmedBackfills;}
    public synchronized int confirmedGapReviewCount(){return confirmedGapReviews;}
    public synchronized int manualKeyframeCount(){return manualKeyframes.size();}
    public synchronized int manualEditsMade(){return manualEditsMade;}
    public synchronized Long nextManualAfter(long ms){return manualKeyframes.higherKey(ms);}
    public synchronized long firstTimestamp(){return frames.isEmpty()?-1:frames.firstKey();}
    public synchronized long latestTimestamp(){return frames.isEmpty()?-1:frames.lastKey();}
    public synchronized long coveredThrough(){return coverageEndMs;}
    public synchronized void reset(){
        frames.clear();appearanceGallery.clear();last=previous=pending=null;
        lastMs=previousMs=pendingMs=-1;lastId=-1;confirmations=0;
        bodyValidatedCount=bodyRecoveredCount=edgeRecoveredCount=0;
        scaleRecoveredCount=scaleReasonMismatch=0;
        wrongSizeBodyRejected=0;flowEstimatedCount=0;crowdedGapBlocked=0;
        scalePending=null;scalePendingMs=-1;scalePendingCount=0;
        confirmingFrames.clear();confirmedBackfills=0;confirmedGapReviews=0;manualKeyframes.clear();manualEditsMade=0;coverageEndMs=-1;
    }
    public synchronized int bodyValidatedCount(){return bodyValidatedCount;}
    public synchronized int bodyRecoveredCount(){return bodyRecoveredCount;}
    public synchronized int edgeRecoveredCount(){return edgeRecoveredCount;}
    public synchronized int scaleRecoveredCount(){return scaleRecoveredCount;}
    public synchronized int scaleRejectedCount(){return scaleReasonMismatch;}
    public synchronized int wrongSizeBodyRejectedCount(){return wrongSizeBodyRejected;}
    public synchronized int flowEstimatedCount(){return flowEstimatedCount;}
    public synchronized int crowdedGapBlockedCount(){return crowdedGapBlocked;}
    public synchronized boolean plausible(long ms,List<Box> found){
        return last!=null && chooseMotion(last,previous,lastId,ms-lastMs,unique(found)).status==Status.TRACKED;
    }
    /** Image-motion coverage remains provisional, even when background faces exist.
     * Never feed a projected box into the face identity gallery or lastReliableBox.
     */
    public synchronized Point putContinuity(long ms,Box box,float confidence,String reason){
        Point p=frames.get(ms);
        if(p==null||p.status==Status.TRACKED||p.status==Status.VERIFIED||box==null||confidence<.80f)return p;
        if(p.box!=null)return p;
        Point n=new Point(ms,box,Status.FLOW_ESTIMATED,p.candidates,reason);
        frames.put(ms,n);flowEstimatedCount++;return n;
    }
    public synchronized Point exact(long ms){return frames.get(ms);}
    public synchronized int uncoveredCount(){int n=0;for(Point p:frames.values())if(p.box==null)n++;return n;}
    private long coverageEndMs=-1;
    public synchronized void coverageEnd(long ms){coverageEndMs=ms;}
    /** Motion estimates are provisional annotations, not confirmed face detection.
     * They never update the last verified face or its visual identity gallery.
     */
    public synchronized Point putMotionEstimate(long ms,Box prediction,float confidence){
        Point original=frames.get(ms);
        if(original==null||original.status==Status.TRACKED||original.status==Status.VERIFIED||
           original.box!=null||prediction==null||confidence<.72f) return original;
        if(original.candidates!=0)return original; // Never override an ambiguous detected face.
        Point provisional=new Point(ms,prediction,Status.FLOW_ESTIMATED,0,
            "OPTICAL_FLOW_ESTIMATE_REVIEW");
        frames.put(ms,provisional);flowEstimatedCount++;
        return provisional;
    }
    /** Last face that actually passed the tracking checks; no fabricated frame. */
    public synchronized Box lastReliableBox(){return last;}
    public synchronized long lastReliableTimestamp(){return lastMs;}
    public synchronized void anchor(long ms,Box b){
        Objects.requireNonNull(b);frames.tailMap(ms,true).clear();coverageEndMs=-1;confirmingFrames.clear();
        last=b;lastMs=ms;lastId=b.id;previous=null;previousMs=-1;pending=null;pendingMs=-1;confirmations=0;scalePending=null;scalePendingMs=-1;scalePendingCount=0;
        appearanceGallery.clear();if(b.appearance!=null)appearanceGallery.add(b.appearance);
        frames.put(ms,new Point(ms,b,Status.VERIFIED,-1,"USER_SELECTED"));
    }
    /**
     * User-confirmed face keyframes form hard boundaries between edited segments.
     * Changing an earlier segment MUST NOT erase already corrected later segments.
     * Only replace the samples between the new anchor and the NEXT manual anchor.
     */
    public synchronized void anchorManual(long ms,Box b){
        Objects.requireNonNull(b);
        Long next=manualKeyframes.higherKey(ms);
        if(next==null)frames.tailMap(ms,true).clear();
        else frames.subMap(ms,true,next,false).clear();
        manualKeyframes.put(ms,b);
        manualEditsMade++;
        // Reset temporary recognition state without clearing other segments.
        last=b;lastMs=ms;lastId=b.id;previous=null;previousMs=-1;
        pending=null;pendingMs=-1;confirmations=0;
        scalePending=null;scalePendingMs=-1;scalePendingCount=0;
        confirmingFrames.clear();
        appearanceGallery.clear();
        if(b.appearance!=null)appearanceGallery.add(b.appearance);
        frames.put(ms,new Point(ms,b,Status.VERIFIED,-1,"MANUAL_FACE_BOX_SELECTED"));
    }
    /**
     * Only bridge <=2 consecutive 100ms missing samples. Require a geometric
     * agreement with a REAL detected and confirmed face shortly afterwards.
     * The resulting frame stays provisional and review-required.
     */
    public synchronized int bridgeShortConfirmedGaps(){
        ArrayList<Point> points=new ArrayList<>(frames.values());
        int filled=0;
        for(int right=1;right<points.size();right++){
            Point after=points.get(right);
            if(after.status!=Status.DETECTION_BRIDGED||after.box==null)continue;
            int previous=right-1,missing=0;
            while(previous>=0&&points.get(previous).box==null){
                missing++;previous--;
                if(missing>2)break;
            }
            if(previous<0||missing<1||missing>2)continue;
            Point before=points.get(previous);
            Point realAfter=null;
            for(int i=right+1;i<points.size();i++){
                Point candidate=points.get(i);
                if(candidate.ms-after.ms>320)break;
                if(candidate.status==Status.TRACKED||candidate.status==Status.VERIFIED){
                    realAfter=candidate;break;
                }
            }
            if(!SafeGapV086.supported(before,after,realAfter,missing))continue;
            for(int i=previous+1;i<right;i++){
                Point old=points.get(i);
                if(old.box!=null)break;
                float ratio=(old.ms-before.ms)/(float)(after.ms-before.ms);
                Box predicted=SafeGapV086.interpolate(before.box,after.box,ratio);
                if(predicted==null)continue;
                frames.put(old.ms,new Point(old.ms,predicted,Status.FLOW_ESTIMATED,
                    old.candidates,"CONFIRMED_SHORT_GAP_REVIEW"));
                filled++;confirmedGapReviews++;
            }
        }
        return filled;
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
            // Require visible facial landmarks: prevents selecting a cap, jacket or background.
            if(b==null||(!b.faceEvidence&&!b.edgePartial)||b.w<.04f||b.h<.028f||b.w*b.h>.75f)continue;
            boolean seen=false;
            for(Box q:out)if(duplicate(b,q)){seen=true;break;}
            if(!seen)out.add(b);
        }
        return out;
    }
    public synchronized Point step(long ms,List<Box> found){
        return step(ms,found,null);
    }
    public synchronized Point step(long ms,List<Box> found,BodyClothing.Assist body){
        if(last==null)throw new IllegalStateException("First select a face");
        Map.Entry<Long,Point> tail=frames.lastEntry();
        if(tail!=null&&ms<=tail.getKey())throw new IllegalArgumentException("Samples must be chronological");
        List<Box> cs=unique(found);
        long gap=ms-lastMs;
        Pick chosen=chooseMotion(last,previous,lastId,gap,cs);
        // Multi-person privacy regression: 21.2s produced a one-frame TRACKED
        // box among FIVE detections after the selected person was lost.
        // Motion + coarse color is not sufficient to switch identities after
        // an extended gap in a crowd. Require persistent reacquisition.
        if(gap>=350 && cs.size()>=3 && chosen.status==Status.TRACKED){
            crowdedGapBlocked++;
            chosen=new Pick(null,Status.UNCERTAIN,"CROWDED_GAP_REIDENTIFICATION_REVIEW");
        }
        if(body!=null && body.confirmed && body.confidence>=.35f){
            if(chosen.status==Status.TRACKED){
                if(body.near(chosen.box) &&
                   chosen.box.area()/Math.max(.0001f,last.area())>=.23f){
                    chosen=new Pick(chosen.box,Status.TRACKED,"FACE_BODY_CORROBORATED");
                    bodyValidatedCount++;
                }else if(body.confidence>=.82f){
                    chosen=new Pick(null,Status.UNCERTAIN,"BODY_FACE_DISAGREEMENT_REVIEW");
                }
            }
            if(chosen.status!=Status.TRACKED){
                Pick assisted=chooseWithBody(ms,gap,cs,body);
                if(assisted!=null)chosen=assisted;
                else if(cs.isEmpty())chosen=new Pick(null,Status.BODY_HELD,"BODY_TRACKED_FACE_NOT_VISIBLE");
            }
        }
        if(chosen.status!=Status.TRACKED && chosen.status!=Status.BODY_HELD &&
            (body==null || !body.confirmed)){
            Pick edge=tryEdgeRecovery(ms,gap,cs);
            if(edge!=null)chosen=edge;
            else{
                Pick scale=tryAdaptiveScale(ms,gap,cs);
                chosen=scale==null?reacquire(ms,gap,cs,chosen):scale;
            }
        }
        if(chosen.status==Status.TRACKED&&!confirmingFrames.isEmpty()){
            // Preserve the real candidate boxes that led to a successful two/three
            // frame reacquisition; waiting for confirmation must not erase them.
            Box next=chosen.box;long nextMs=ms;
            for(Map.Entry<Long,Box> e:confirmingFrames.descendingMap().entrySet()){
                Box b=e.getValue();Point old=frames.get(e.getKey());
                if(nextMs-e.getKey()>240||next.dist(b)>.20f||scale(b,next)<.35||scale(b,next)>3)break;
                if(old!=null&&old.box==null){
                    frames.put(e.getKey(),new Point(e.getKey(),b,Status.DETECTION_BRIDGED,old.candidates,"MULTIFRAME_DETECTION_BACKFILL_REVIEW"));
                    confirmedBackfills++;
                }
                next=b;nextMs=e.getKey();
            }
            confirmingFrames.clear();
        }else if(chosen.reason.contains("CONFIRMING")){
            Box candidate=chosen.reason.startsWith("ADAPTIVE_SCALE")?scalePending:pending;
            if(chosen.reason.contains("_1_OF_")||candidate==null)confirmingFrames.clear();
            if(candidate!=null){confirmingFrames.put(ms,candidate);while(confirmingFrames.size()>3)confirmingFrames.pollFirstEntry();}
        }else confirmingFrames.clear();
        Point point=new Point(ms,chosen.box,chosen.status,cs.size(),chosen.reason);
        frames.put(ms,point);
        if(chosen.status==Status.TRACKED){
            if(chosen.reason.startsWith("BODY_ASSISTED_"))bodyRecoveredCount++;
            if(chosen.reason.equals("EDGE_FACE_REACQUIRED"))edgeRecoveredCount++;
            if(chosen.reason.equals("ADAPTIVE_SCALE_REACQUIRED"))scaleRecoveredCount++;
            scalePending=null;scalePendingMs=-1;scalePendingCount=0;
            previous=last;previousMs=lastMs;last=chosen.box;lastMs=ms;lastId=chosen.box.id;
            if(chosen.box.appearance!=null && appearanceGallery.size()<7 && appearance(chosen.box)>.78f)
                appearanceGallery.add(chosen.box.appearance);
            pending=null;pendingMs=-1;confirmations=0;
        }
        return point;
    }
    private Pick chooseWithBody(long ms,long gap,List<Box> cs,BodyClothing.Assist body){
        if(cs.isEmpty())return null;
        Box candidate=null;
        float top=-1,second=-1,topAppearance=-1;
        for(Box face:cs){
            if(!body.near(face))continue;
            // v0.7.8 regression: a background face ~2% the last target's
            // area was spuriously relinked at t=15.3s when clothing matched.
            // Torso alignment alone is NOT proof that a tiny face belongs
            // to the selected person.
            float currentRatio=face.area()/Math.max(.0001f,last.area());
            float relativeShoulders=face.w/Math.max(.03f,body.shoulderWidth);
            if(currentRatio<.20f || currentRatio>5.5f ||
               relativeShoulders<.30f || (face.area()<.003f && last.area()>.018f)){
                wrongSizeBodyRejected++;
                continue;
            }
            float similarity=appearance(face);
            // Partial edge landmarks require stronger body confidence and visual similarity.
            float minAppearance=face.edgePartial?.70f:.63f;
            if(similarity<minAppearance)continue;
            float headDist=(float)Math.hypot(face.cx()-body.headX,face.cy()-body.headY);
            float score=similarity-headDist*.45f;
            if(score>top){second=top;top=score;candidate=face;topAppearance=similarity;}
            else if(score>second)second=score;
        }
        if(candidate==null || (second>=0 && top-second<.09f))
            return null;
        boolean solid=topAppearance>=.80f && body.confidence>=.65f && !candidate.edgePartial;
        // Only accept immediate rescue for short gaps when torso+face agree strongly;
        // otherwise require two independent time samples.
        if(gap<=250 && solid && last.dist(candidate)<=.32f &&
           scale(last,candidate)>.28 && scale(last,candidate)<5f)
            return new Pick(candidate,Status.TRACKED,"BODY_ASSISTED_FACE_MATCH");
        if(pending!=null && ms>pendingMs && ms-pendingMs<=250 &&
           pending.dist(candidate)<.19f && scale(pending,candidate)>.4 &&
           scale(pending,candidate)<2.5f)
            confirmations++;
        else confirmations=1;
        pending=candidate;pendingMs=ms;
        int needed=(candidate.edgePartial||topAppearance<.76f)?3:2;
        if(confirmations>=needed && body.confidence>=(candidate.edgePartial?.66f:.42f)){
            pending=null;pendingMs=-1;confirmations=0;
            return new Pick(candidate,Status.TRACKED,"BODY_ASSISTED_REACQUIRED");
        }
        return new Pick(null,Status.UNCERTAIN,
                        "BODY_FACE_CONFIRMING_"+confirmations+"_OF_"+needed);
    }
    private Pick tryEdgeRecovery(long ms,long gap,List<Box> candidates){
        if(candidates.size()!=1||gap>600)return null;
        // A partially cropped face can violate the ordinary size/motion thresholds.
        // Never infer a face in empty frames; require an actual face detection.
        boolean fromEdge=last.x<.06f||last.x+last.w>.94f||last.y<.04f;
        if(!fromEdge)return null;
        Box c=candidates.get(0);
        boolean atEdge=c.x<.10f||c.x+c.w>.90f||c.y<.07f;
        if(!atEdge||last.dist(c)>.38f)return null;
        float sim=appearance(c);
        if(sim<.69f)return null;
        if(pending!=null&&ms>pendingMs&&ms-pendingMs<=230 &&
            pending.dist(c)<.20f && scale(pending,c)>.35f &&
            scale(pending,c)<3.2f)
            confirmations++;
        else confirmations=1;
        pending=c;pendingMs=ms;
        if(confirmations>=2){
            pending=null;pendingMs=-1;confirmations=0;
            return new Pick(c,Status.TRACKED,"EDGE_FACE_REACQUIRED");
        }
        return new Pick(null,Status.UNCERTAIN,"EDGE_FACE_CONFIRMING_1_OF_2");
    }
    /**
     * Local recovery for zoom/edge movement that violates the v0.7.6 geometry gate.
     * Crucially no face is fabricated: at least two consecutive real detections
     * with corresponding facial landmarks and a strong visual match are required.
     * The mechanism is NOT a biometric identification system.
     */
    private Pick tryAdaptiveScale(long ms,long gap,List<Box> faces){
        if(gap>650 || faces.size()!=1){
            scalePending=null;scalePendingMs=-1;scalePendingCount=0;
            return null;
        }
        Box c=faces.get(0);
        if(!c.faceEvidence || c.edgePartial || c.appearance==null){
            scalePending=null;scalePendingMs=-1;scalePendingCount=0;
            return null;
        }
        final float similarity=appearance(c);
        if(similarity<.80f){scaleReasonMismatch++;scalePending=null;scalePendingCount=0;scalePendingMs=-1;return null;}
        float areaRatio=(float)scale(last,c);
        // A large zoom, not any arbitrary remote face: last face near edge,
        // substantial size shift or partially cropped detected face.
        boolean scaleEvent=areaRatio>2.2f || areaRatio<.48f;
        boolean edgeEvent=last.x<.055f || last.x+last.w>.945f || c.x<.055f || c.x+c.w>.945f;
        if(!scaleEvent && !edgeEvent)return null;
        if(areaRatio<.14f || areaRatio>10f)return null;
        if(last.dist(c)>.52f)return null;
        if(scalePending!=null && ms>scalePendingMs && ms-scalePendingMs<=230 &&
           scalePending.dist(c)<.20f &&
           scale(scalePending,c)>.26 && scale(scalePending,c)<3.5f){
            scalePendingCount++;
        }else scalePendingCount=1;
        scalePending=c;scalePendingMs=ms;
        // Zooms >5x get a third detection to mitigate another person's face.
        int required=(areaRatio>5f || areaRatio<.2f || similarity<.88f)?3:2;
        if(scalePendingCount>=required){
            scalePending=null;scalePendingMs=-1;scalePendingCount=0;
            return new Pick(c,Status.TRACKED,"ADAPTIVE_SCALE_REACQUIRED");
        }
        return new Pick(null,Status.UNCERTAIN,
             "ADAPTIVE_SCALE_CONFIRMING_"+scalePendingCount+"_OF_"+required);
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
            if(b.edgePartial)continue; // Only body or multi-frame edge logic may accept a partial face.
            double size=scale(from,b);
            if(size<.33||size>4.6)continue; // avoid nose-only or tiny mismatched boxes
            double dist=Math.hypot(predX-b.cx(),predY-b.cy());
            if(dist>allowed)continue;
            double score=dist+Math.abs(Math.log(size))*.025;
            float appearance=appearance(b);
            if(appearance>=0 && appearance<.63f)continue;
            if(appearance>=0)score+=Math.max(0,.83-appearance)*.18;
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
            double relativeArea=scale(last,b);
            if(relativeArea<.32||relativeArea>4.6)continue;
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
        int needed=(gap>850 || (gap>=350 && candidates.size()>=3))?3:2;
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
        // Include the final fractional sample (e.g. 22.100-22.186), never
        // extrapolate into an unanalyzed or cancelled part of the video.
        if(a!=null && b==null && ms<=coverageEndMs && ms-a.getKey()<=100)
            return a.getValue().box==null?null:a.getValue();
        if(a==null||b==null)return null;
        Point p=a.getValue(),q=b.getValue();
        if(p.box==null||q.box==null||p.status==Status.LOST||p.status==Status.UNCERTAIN||p.status==Status.BODY_HELD||q.status==Status.LOST||q.status==Status.UNCERTAIN||q.status==Status.BODY_HELD)return null;
        if(q.ms-p.ms>160)return null;if(q.ms==p.ms)return p;
        float f=(float)(ms-p.ms)/(q.ms-p.ms);Box x=p.box,y=q.box;
        return new Point(ms,new Box(x.x+(y.x-x.x)*f,x.y+(y.y-x.y)*f,x.w+(y.w-x.w)*f,x.h+(y.h-x.h)*f,y.id),
            (p.status==Status.FLOW_ESTIMATED||q.status==Status.FLOW_ESTIMATED)?Status.FLOW_ESTIMATED:
            ((p.status==Status.DETECTION_BRIDGED||q.status==Status.DETECTION_BRIDGED)?Status.DETECTION_BRIDGED:Status.TRACKED));
    }
}

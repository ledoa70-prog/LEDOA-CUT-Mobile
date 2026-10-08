package kr.ledoa.cut.aitest;

/** Keeps a selected person's upper-body continuity as auxiliary evidence.
 * It never creates a facial mosaic rectangle: only face detections may do that.
 * A clothing color/texture signature is NOT a reliable personal identity.
 */
public final class BodyClothing {
    public static final class Observation {
        public final float headX,headY,bodyX,bodyY,shoulderWidth,quality;
        public final float[] clothing;
        public Observation(float headX,float headY,float bodyX,float bodyY,
                           float shoulderWidth,float quality,float[] clothing){
            this.headX=headX;this.headY=headY;this.bodyX=bodyX;this.bodyY=bodyY;
            this.shoulderWidth=shoulderWidth;this.quality=quality;this.clothing=clothing;
        }
        public boolean nearFace(FacePath.Box box){
            if(box==null)return false;
            return Math.hypot(headX-box.cx(),headY-box.cy())<
                Math.max(.12,.65*Math.max(box.w,box.h));
        }
    }
    public static final class Assist {
        public final float headX,headY,shoulderWidth,confidence;
        public final boolean confirmed;
        Assist(Observation o,float confidence,boolean confirmed){
            this.headX=o.headX;this.headY=o.headY;this.shoulderWidth=o.shoulderWidth;
            this.confidence=confidence;this.confirmed=confirmed;
        }
        public boolean near(FacePath.Box face){
            if(face==null)return false;
            float d=(float)Math.hypot(face.cx()-headX,face.cy()-headY);
            return d<Math.max(.11f,Math.max(face.w*.63f,shoulderWidth*.75f));
        }
    }
    private float[] reference;
    private Observation lastGood,pending;
    private long lastGoodMs=-1,pendingMs=-1;
    private int candidateCount=0;
    private boolean enabled=false;
    private int bodyHeld=0;
    private int bodyRejected=0;
    public synchronized void reset(){
        reference=null;lastGood=null;pending=null;
        lastGoodMs=pendingMs=-1;candidateCount=0;enabled=false;bodyHeld=bodyRejected=0;
    }
    /** Only associate the body with the user-tapped face when head pose points to that face. */
    public synchronized boolean select(long ms,FacePath.Box face,Observation pose){
        reset();
        if(pose==null||pose.clothing==null||pose.quality<.52f||!pose.nearFace(face))return false;
        reference=pose.clothing.clone();lastGood=pose;lastGoodMs=ms;enabled=true;
        return true;
    }
    public synchronized boolean isEnabled(){return enabled;}
    public synchronized int heldCount(){return bodyHeld;}
    public synchronized int rejectedCount(){return bodyRejected;}
    public synchronized Assist observe(long ms,Observation pose){
        if(!enabled||pose==null||pose.clothing==null||pose.quality<.48f)return null;
        float sim=FaceAppearance.score(reference,pose.clothing);
        if(sim<.72f){pending=null;candidateCount=0;bodyRejected++;return null;}
        long gap=Math.max(0,ms-lastGoodMs);
        float movement=lastGood==null?0:(float)Math.hypot(pose.bodyX-lastGood.bodyX,pose.bodyY-lastGood.bodyY);
        float permitted=Math.min(.59f,.14f+gap*.00040f+Math.max(.01f,pose.shoulderWidth)*.22f);
        if(movement>permitted){pending=null;candidateCount=0;bodyRejected++;return null;}
        float ratio=lastGood==null?1f:pose.shoulderWidth/Math.max(.01f,lastGood.shoulderWidth);
        if(ratio<.38f||ratio>2.7f){pending=null;candidateCount=0;bodyRejected++;return null;}
        boolean uncertain=(gap>550 || movement>.28);
        if(uncertain){
            if(pending!=null&&ms>pendingMs&&ms-pendingMs<=350 &&
                Math.hypot(pose.bodyX-pending.bodyX,pose.bodyY-pending.bodyY)<.22){
                candidateCount++;
            }else candidateCount=1;
            pending=pose;pendingMs=ms;
            if(candidateCount<2)return null;
        }
        pending=null;candidateCount=0;lastGood=pose;lastGoodMs=ms;bodyHeld++;
        return new Assist(pose,Math.min(1,(sim-.65f)/.34f),true);
    }
}

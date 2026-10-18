package kr.ledoa.cut.aitest;

/**
 * Lazy flow-seed controller. Heavy feature extraction is done ONLY on first
 * detector blackout, not on all 10 frames per second of reliable tracking.
 * Face identity and successful detections remain owned solely by FacePath.
 */
public final class LazyFlowBridge {
    private final OpticalBridge optical;
    private byte[] lastGray;
    private FacePath.Box lastTrusted;
    private long lastTrustedMs=-1;
    private int lastW,lastH;
    private boolean seeded;
    private int trustedUpdates,lazySeeds,flowSkips,flowAttempts;
    public LazyFlowBridge(OpticalBridge optical){this.optical=optical;}
    public void reset(){
        optical.reset();
        lastGray=null;lastTrusted=null;lastTrustedMs=-1;lastW=lastH=0;
        seeded=false;trustedUpdates=lazySeeds=flowSkips=flowAttempts=0;
    }
    public void trusted(long ms,byte[] gray,int w,int h,FacePath.Box realFace){
        if(gray==null||realFace==null||gray.length!=w*h)return;
        lastGray=gray;lastW=w;lastH=h;lastTrusted=realFace;lastTrustedMs=ms;
        trustedUpdates++;
        // IMPORTANT: this does not recompute pyramid or image corners.
        seeded=false;
        optical.invalidate();
    }
    /** Never pretend a projected face rectangle was detected. */
    public OpticalBridge.Estimate gap(long ms,byte[] gray,int w,int h,
                                     BodyClothing.Assist body,boolean safeToBridge){
        if(!safeToBridge||gray==null||lastGray==null||lastTrusted==null ||
           w!=lastW||h!=lastH||ms<=lastTrustedMs ||
           ms-lastTrustedMs>OpticalBridge.MAX_FACE_MISSING_MS){
            flowSkips++;return null;
        }
        if(!seeded){
            seeded=true;lazySeeds++;
            optical.anchor(lastTrustedMs,lastGray,lastW,lastH,lastTrusted);
        }
        flowAttempts++;
        return optical.advance(ms,gray,w,h,body);
    }
    public void ambiguous(){
        optical.invalidate();
        seeded=true; // do not reinitialize stale reliable frame
    }
    public int trustedUpdates(){return trustedUpdates;}
    public int lazySeeds(){return lazySeeds;}
    public int flowSkips(){return flowSkips;}
    public int flowAttempts(){return flowAttempts;}
}

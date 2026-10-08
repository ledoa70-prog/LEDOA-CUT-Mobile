package kr.ledoa.cut.aitest;
import java.lang.reflect.Field;
import java.util.TreeMap;
public final class SafeGapV086Test {
    private static int checks=0;
    private static void check(boolean ok,String msg){checks++;if(!ok)throw new AssertionError(msg);}
    private static FacePath.Box b(float x,float y,float w,float h){
        return new FacePath.Box(x,y,w,h,-1,null,false,false);
    }
    private static FacePath.Point p(long ms,FacePath.Status status,FacePath.Box box,int candidates){
        return new FacePath.Point(ms,box,status,candidates,"test");
    }
    @SuppressWarnings("unchecked")
    private static TreeMap<Long,FacePath.Point> frames(FacePath path) throws Exception{
        Field f=FacePath.class.getDeclaredField("frames");f.setAccessible(true);
        return (TreeMap<Long,FacePath.Point>)f.get(path);
    }
    public static void main(String[]args) throws Exception{
        FacePath.Box left=b(.206f,.009f,.772f,.705f);
        FacePath.Box right=b(0,0,1,.703f);
        FacePath.Box confirmed=b(.214f,.025f,.786f,.603f);
        FacePath.Point a=p(6800,FacePath.Status.FLOW_ESTIMATED,left,0);
        FacePath.Point z=p(7100,FacePath.Status.DETECTION_BRIDGED,right,3);
        FacePath.Point trusted=p(7300,FacePath.Status.TRACKED,confirmed,1);
        check(SafeGapV086.supported(a,z,trusted,2),"6.9-7.0s gap safely supported by real face");
        check(!SafeGapV086.supported(a,z,trusted,3),"3 missing frames cannot be guessed");
        check(!SafeGapV086.supported(a,p(7300,FacePath.Status.DETECTION_BRIDGED,right,3),
             trusted,2),"large time gap is not bridged");
        check(!SafeGapV086.supported(a,z,p(7500,FacePath.Status.TRACKED,confirmed,1),2),
              "confirmation must be nearby");
        check(!SafeGapV086.supported(a,z,p(7300,FacePath.Status.LOST,confirmed,1),2),
              "a guessed or lost future face is not real confirmation");
        check(!SafeGapV086.supported(a,z,p(7300,FacePath.Status.TRACKED,b(.01f,.73f,.19f,.10f),1),2),
              "a stranger at a different position cannot authorize interpolation");
        FacePath.Box middle=SafeGapV086.interpolate(left,right,1f/3);
        check(middle!=null&&middle.w>.77f&&middle.w<1f,"intermediate geometry bounded");

        FacePath path=new FacePath();
        TreeMap<Long,FacePath.Point> map=frames(path);
        map.put(6800L,a);
        map.put(6900L,p(6900,FacePath.Status.UNCERTAIN,null,4));
        map.put(7000L,p(7000,FacePath.Status.LOST,null,0));
        map.put(7100L,z);
        map.put(7200L,p(7200,FacePath.Status.DETECTION_BRIDGED,
                      b(.083f,0,.917f,.644f),3));
        map.put(7300L,trusted);
        check(path.bridgeShortConfirmedGaps()==2,"fill ONLY 6.9 and 7.0 second samples");
        check(path.exact(6900).status==FacePath.Status.FLOW_ESTIMATED &&
              path.exact(7000).status==FacePath.Status.FLOW_ESTIMATED,
              "both new masks are provisional, not fabricated face detections");
        check(path.confirmedGapReviewCount()==2,"gap repair statistics recorded");
        check(path.bridgeShortConfirmedGaps()==0,"repair is idempotent");
        check(path.reviewCount()==5,"all provisional backfills still need review");
        path.anchor(0,b(.4f,.2f,.2f,.1f));
        map=frames(path);
        map.put(1000L,p(1000,FacePath.Status.TRACKED,b(.5f,.2f,.2f,.1f),1));
        path.anchorManual(1100,b(.6f,.2f,.15f,.1f));
        check(path.exact(1000)!=null,"manual repair preserves prior complete video");
        check(path.exact(1100).reason.equals("MANUAL_FACE_BOX_SELECTED"),
              "a manual box is explicitly labeled, not an AI recognition");
        check(path.manualKeyframeCount()==1,"manual rerun has an audit count");
        map.put(1200L,p(1200,FacePath.Status.LOST,null,0));
        path.anchorManual(1150,b(.6f,.2f,.15f,.1f));
        check(path.exact(1200)==null&&path.exact(1000)!=null,
              "new manual correction removes only the corrected tail");
        System.out.println("PASS: "+checks+" safe short-gap and manual partial-rerun checks");
    }
}
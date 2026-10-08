package kr.ledoa.cut.aitest;
import java.util.*;
/** Multiple manual face edits survive edits made in non-chronological order. */
public final class ManualRetentionV087Test {
    static int n=0;
    static void ok(boolean ok,String why){n++;if(!ok)throw new AssertionError(why);}
    static FacePath.Box b(float x,float y,float w,float h){
        return new FacePath.Box(x,y,w,h,-1,null,true,false);
    }
    public static void main(String[] args){
        FacePath p=new FacePath();p.anchor(0,b(.35f,.1f,.2f,.2f));
        p.coverageEnd(22186);
        p.anchorManual(21500,b(.5f,.1f,.15f,.2f));
        p.anchorManual(21900,b(.55f,.12f,.15f,.18f));
        p.anchorManual(15400,b(.4f,.17f,.2f,.2f));
        p.anchorManual(6750,b(.15f,.03f,.7f,.75f));
        p.anchorManual(5746,b(.08f,.08f,.65f,.6f));
        p.anchorManual(20800,b(.64f,.13f,.25f,.17f));
        p.anchorManual(21200,b(.68f,.13f,.25f,.17f));
        p.anchorManual(22100,b(.72f,.12f,.22f,.2f));
        ok(p.manualKeyframeCount()==8,"8 different manual face positions retained");
        ok(p.manualEditsMade()==8,"cumulative edits tracked");
        ok(p.exact(22100)!=null&&p.exact(20800)!=null&&p.exact(15400)!=null,
           "correcting earlier frames cannot erase the ending");
        ok(p.nextManualAfter(5746)==6750,"next anchor is an exclusive processing boundary");
        ok(p.nextManualAfter(21500)==21900,"ending segment bounded by future anchor");
        ok(p.nextManualAfter(22100)==null,"final segment extends to video end");
        ok(p.coveredThrough()==22186,"manual edit must not discard the prior whole-video coverage");

        // Most important regression: later manual selections survive an
        // additional correction at the beginning of the movie.
        p.anchorManual(6000,b(.12f,.07f,.7f,.7f));
        ok(p.manualKeyframeCount()==9,"new earlier edit retains all 8 previous locations");
        ok(p.manualEditsMade()==9,"total manual corrections counted separately");
        ok(p.exact(22100).reason.equals("MANUAL_FACE_BOX_SELECTED"),
           "22.1s user-selected face persists after 6s edit");
        ok(p.exact(21900).reason.equals("MANUAL_FACE_BOX_SELECTED"),
           "21.9s corrected frame persists after 6s edit");
        ok(p.exact(6750).reason.equals("MANUAL_FACE_BOX_SELECTED"),
           "6.75s user correction also preserved");
        ok(p.nextManualAfter(6000)==6750,"rerun 6.0-6.75s only, without damaging future edits");
        FacePath.Box replacement=b(.58f,.1f,.18f,.16f);
        p.anchorManual(21900,replacement);
        ok(p.manualKeyframeCount()==9,"editing same timestamp replaces rather than duplicates a keyframe");
        ok(p.manualEditsMade()==10,"repeated corrections remain auditable");
        ok(Math.abs(p.exact(21900).box.x-.58f)<.00001f,"replacement coordinates preserved");
        ok(p.exact(22100)!=null,"next manual anchor survives same-time replacement");
        ok(p.firstTimestamp()==0,"whole video first timestamp remains zero");
        ok(p.latestTimestamp()==22100,"ending frame remains in export");
        // Two nearby user-marked faces authorize ONLY provisional boxes
        // between them, never a verified identity or an unbounded tail.
        FacePath bounded=new FacePath();
        bounded.anchor(0,b(.3f,.1f,.2f,.15f));
        bounded.anchorManual(21000,b(.45f,.12f,.25f,.20f));
        bounded.anchorManual(21600,b(.58f,.14f,.24f,.18f));
        for(long ms=21100;ms<21600;ms+=100)
            bounded.step(ms,Collections.emptyList());
        int nBefore=bounded.uncoveredCount();
        int filled=bounded.fillBetweenManualKeyframes();
        ok(filled==5,"5 previously empty samples bridged between two user keys");
        ok(bounded.uncoveredCount()==nBefore-5,"manually bracketed coverage improves");
        ok(bounded.exact(21100).status==FacePath.Status.FLOW_ESTIMATED,
           "manual gap interpolation is not face recognition");
        ok("TWO_MANUAL_BOXES_GAP_REVIEW".equals(bounded.exact(21300).reason),
           "manual interpolation requires human review");
        ok(bounded.fillBetweenManualKeyframes()==0,"no duplicate interpolation");
        FacePath far=new FacePath();
        far.anchor(0,b(.3f,.1f,.2f,.15f));
        far.anchorManual(21000,b(.45f,.12f,.25f,.20f));
        far.anchorManual(22000,b(.58f,.14f,.24f,.18f));
        far.step(21500,Collections.emptyList());
        ok(far.fillBetweenManualKeyframes()==0,"never infer a face across a one second gap");
        ok(far.exact(21500).box==null,"unsupported long gap remains unmasked");
        p.reset();
        ok(p.manualKeyframeCount()==0 && p.manualEditsMade()==0 &&
           p.firstTimestamp()==-1,"new video clears old personal manual positions");
        System.out.println("PASS: "+n+" multi-keyframe retention, bounded re-analysis, export and identity checks");
    }
}
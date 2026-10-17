package kr.ledoa.cut.aitest;
import java.util.*;
public final class AutomaticRangeV094Test {
    static int checks;
    static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static FacePath.Box box(float x){return new FacePath.Box(x,.2f,.15f,.2f,-1);}
    public static void main(String[] args){
        MosaicTimeline t=new MosaicTimeline();t.allFaces=true;t.rangeAuto(400,1600,2000);
        for(long ms=400;ms<1600;ms+=100)t.put(ms,List.of(box(.1f+ms/10000f)));t.analysedEndMs=1600;
        ManualTrack plate=new ManualTrack(1,false,0,2000);plate.put(0,box(.6f));t.addManual(plate);
        ok(t.canExport(),"analysed interval exports");int samples=t.samples().size();
        t.rangeAuto(600,1400,2000);ok(t.canExport(),"trim needs no reanalysis");ok(t.samples().size()==samples,"trim preserves computed detections");
        ok(t.autoBoxesAt(599).isEmpty()&&!t.autoBoxesAt(600).isEmpty(),"inclusive automatic start");
        ok(t.autoBoxesAt(1400).isEmpty()&&!t.autoBoxesAt(1399).isEmpty(),"exclusive automatic end");
        ok(t.boxesAt(200).size()==1,"automatic trim does not trim manual plate");
        t.rangeAuto(400,1600,2000);ok(t.canExport()&&!t.autoBoxesAt(500).isEmpty(),"extending back within covered data restores masks");
        t.rangeAuto(200,1600,2000);ok(!t.canExport(),"unanalysed prefix cannot be exported");ok(t.autoBoxesAt(300).isEmpty(),"no invented prefix mask");
        t.rangeAuto(400,1800,2000);ok(!t.canExport(),"unanalysed suffix cannot be exported");ok(t.autoBoxesAt(1700).isEmpty(),"no stale suffix mask");
        t.rangeAuto(400,1600,2000);ok(t.canExport(),"undoing range restores export eligibility");
        t.put(500,List.of());t.put(1200,List.of());t.rangeAuto(800,1500,2000);ok(t.nextReview(-1)==1200,"review navigation ignores trimmed-out missing frames");
        boolean invalid=false;try{t.rangeAuto(-1,100,2000);}catch(IllegalArgumentException expected){invalid=true;}ok(invalid,"negative range rejected");
        invalid=false;try{t.rangeAuto(0,2100,2000);}catch(IllegalArgumentException expected){invalid=true;}ok(invalid,"range past source end rejected");
        t.resetAuto();t.allFaces=false;t.rangeAuto(500,1000,2000);t.selectedPath.anchor(500,box(.2f));t.selectedPath.step(600,List.of(box(.21f)));t.selectedPath.coverageEnd(1000);t.analysedEndMs=1000;
        ok(t.canExport(),"selected face coverage starts at its actual anchor");t.rangeAuto(100,1000,2000);ok(!t.canExport(),"moving selected range before its anchor needs tracking");
        ok(t.manualTracks().size()==1,"range and reanalysis preserve manual tracks");
        System.out.println("PASS: "+checks+" automatic range, coverage, restore and manual independence checks");
    }
}

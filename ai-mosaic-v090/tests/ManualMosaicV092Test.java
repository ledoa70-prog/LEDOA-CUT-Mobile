package kr.ledoa.cut.aitest;
import java.util.*;

public final class ManualMosaicV092Test {
    static int checks;
    static void ok(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    static void close(float a,float b,String message){ok(Math.abs(a-b)<.0001,message+" "+a+" != "+b);}
    static FacePath.Box box(float x,float y,float w,float h){return new FacePath.Box(x,y,w,h,-1);}
    public static void main(String[] args){
        ManualTrack t=new ManualTrack(1,false,100,2000);
        t.put(100,box(.1f,.2f,.15f,.2f));t.put(1100,box(.5f,.4f,.3f,.4f));
        FacePath.Box middle=t.at(600,List.of());
        close(middle.x,.3f,"position interpolation");close(middle.y,.3f,"vertical interpolation");
        close(middle.w,.225f,"width grows with next corner");close(middle.h,.3f,"height grows independently");
        ok(t.at(99,List.of())==null&&t.at(2000,List.of())==null,"exclusive manual range");
        close(t.at(1900,List.of()).w,.3f,"last point held within chosen interval");
        t.put(1100,box(.6f,.4f,.35f,.1f));ok(t.size()==2,"editing same time replaces point");
        ok(t.at(1200,List.of(1150L))==null,"no stale box after scene cut");
        close(t.at(1149,List.of(1150L)).x,.6f,"previous shot retained up to cut");
        t.put(1500,box(.3f,.6f,.4f,.04f));close(t.at(1300,List.of(1150L)).h,.04f,"thin number plate uses manual geometry without recognition");
        t.range(500,1400,2000);ok(t.size()==3,"trimming preserves hidden keyframes");
        t.range(0,2000,2000);ok(t.at(1500,List.of())!=null,"re-extending restores trimmed path");
        ManualTrack copy=t.copy();copy.remove(1100);ok(t.size()==3&&copy.size()==2,"undo snapshot is independent");
        ok(t.previous(1100)==100&&t.next(1100)==1500,"previous next point navigation");
        ok(t.near(1110,16)==1100&&t.near(1200,16)==null,"near-frame replacement tolerance");
        ManualTrack right=t.split(1000,2,List.of());ok(t.end()==1000&&right.start()==1000,"split intervals meet without overlap");
        ok(t.at(1000,List.of())==null&&right.at(1000,List.of())!=null,"split applies exactly once at boundary");
        ok(Math.abs(t.at(999,List.of()).x-right.at(1000,List.of()).x)<.001f,"split preserves original motion until boundary");
        right.put(1000,box(.8f,.2f,.1f,.1f));ok(t.at(999,List.of()).x<.6f,"editing new shot cannot move previous segment");
        MosaicTimeline timeline=new MosaicTimeline();timeline.startMs=0;timeline.endMs=2000;timeline.analysedEndMs=2000;
        timeline.selectedPath.anchor(0,box(.1f,.2f,.2f,.2f));timeline.selectedPath.coverageEnd(2000);
        ManualTrack correction=new ManualTrack(3,true,0,2000);correction.put(0,box(.5f,.4f,.3f,.1f));timeline.addManual(correction);
        ok(timeline.boxesAt(0).size()==1,"manual correction replaces selected automatic box");close(timeline.boxesAt(0).get(0).x,.5f,"manual correction takes precedence");
        ManualTrack plate=new ManualTrack(4,false,0,2000);plate.put(0,box(.1f,.7f,.4f,.04f));timeline.addManual(plate);
        ok(timeline.boxesAt(0).size()==2,"independent face correction and plate coexist");
        List<ManualTrack> saved=timeline.manualSnapshot();timeline.resetAuto();
        ok(timeline.manualTracks().size()==2,"automatic reanalysis preserves user points");
        ok(!timeline.canExport(),"incomplete automatic path still blocks export");timeline.manualOnly=true;
        ok(timeline.canExport()&&timeline.boxesAt(500).size()==2,"manual-only mode exports without any AI analysis");
        timeline.manual(4).range(1000,2000,2000);ok(timeline.boxesAt(500).size()==1,"per-box range independent");
        timeline.restoreManual(saved);ok(timeline.boxesAt(500).size()==2,"undo restores range and all tracks");
        timeline.cut(1000);ok(timeline.boxesAt(1000).isEmpty(),"scene boundary never extrapolates previous boxes");
        timeline.reset();ok(timeline.manualTracks().isEmpty()&&!timeline.canExport(),"opening new video clears old geometry");
        TimelineViewport v=new TimelineViewport();v.reset(10000);v.zoom(4,.5f);ok(v.span()==2500&&v.time(.5f)==5000,"zoom keeps focus time stable");
        v.pan(10);ok(v.boundary(1)==10000,"pan clamps at video end");v.pan(-20);ok(v.boundary(0)==0,"pan clamps at start");
        v.reveal(9000);ok(v.fraction(9000)>=0&&v.fraction(9000)<=1,"playhead remains visible");
        v.zoom(1000,.5f);ok(v.span()==250,"fine timeline zoom reaches quarter-second window");
        v.reset(100);v.zoom(100,.5f);ok(v.span()==100&&v.time(1)==99&&v.boundary(1)==100,"short clip and exclusive end math");
        boolean invalid=false;try{plate.put(0,box(Float.NaN,0,.1f,.1f));}catch(IllegalArgumentException expected){invalid=true;}ok(invalid,"invalid geometry rejected");
        System.out.println("PASS: "+checks+" manual keyframe, resize, range, cut, undo, zoom checks");
    }
}

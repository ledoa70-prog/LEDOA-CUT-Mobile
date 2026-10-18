package kr.ledoa.cut.aitest;

import java.util.*;

/** Shared time/geometry source for preview and exported pixels. All-face mode
 * does not attempt identity recognition. Face matching only interpolates nearby
 * samples within one shot. Selected-face mode delegates to the guarded tracker. */
public final class MosaicTimeline {
    private final TreeMap<Long,List<FacePath.Box>> frames=new TreeMap<>();
    private final TreeSet<Long> cuts=new TreeSet<>();
    private final LinkedHashMap<Integer,ManualTrack> manualTracks=new LinkedHashMap<>();
    public final FacePath selectedPath=new FacePath();
    public volatile boolean allFaces=false;
    public volatile boolean manualOnly=false;
    public volatile long startMs=0,endMs=0,analysedEndMs=0;
    public volatile int strength=85,margin=18;
    public synchronized void reset(){resetAuto();manualTracks.clear();}
    public synchronized void resetAuto(){frames.clear();cuts.clear();selectedPath.reset();analysedEndMs=0;}
    public synchronized void addManual(ManualTrack track){manualTracks.put(track.id,track);}
    public synchronized ManualTrack manual(int id){return manualTracks.get(id);}
    public synchronized void removeManual(int id){manualTracks.remove(id);}
    public synchronized List<ManualTrack> manualTracks(){return new ArrayList<>(manualTracks.values());}
    public synchronized List<ManualTrack> manualSnapshot(){List<ManualTrack> out=new ArrayList<>();for(ManualTrack t:manualTracks.values())out.add(t.copy());return out;}
    public synchronized void restoreManual(List<ManualTrack> tracks){manualTracks.clear();for(ManualTrack t:tracks)manualTracks.put(t.id,t.copy());}
    /** Crop the output range without deleting already computed tracking data. */
    public synchronized void rangeAuto(long start,long end,long duration){
        if(start<0||end<=start||end>duration)throw new IllegalArgumentException("Invalid automatic range");
        startMs=start;endMs=end;
    }
    public synchronized long analysisStartMs(){
        if(!frames.isEmpty())return frames.firstKey();
        if(!allFaces){List<FacePath.Point> points=selectedPath.points();if(!points.isEmpty())return points.get(0).ms;}
        return Long.MAX_VALUE;
    }
    public synchronized boolean canExport(){
        if(!manualOnly)return endMs>startMs&&analysisStartMs()<=startMs&&analysedEndMs>=endMs;
        for(ManualTrack t:manualTracks.values())if(t.size()>0)return true;
        return false;
    }
    public synchronized void truncate(long ms){frames.tailMap(ms,true).clear();cuts.tailSet(ms,true).clear();analysedEndMs=ms;}
    public synchronized void cut(long ms){cuts.add(ms);selectedPath.sceneCut(ms);}
    public synchronized List<Long> cuts(){return new ArrayList<>(cuts);}
    public synchronized boolean crosses(long a,long b){Long cut=cuts.higher(a);return cut!=null&&cut<=b;}
    public synchronized void put(long ms,List<FacePath.Box> boxes){frames.put(ms,new ArrayList<>(boxes));}
    public synchronized NavigableMap<Long,List<FacePath.Box>> samples(){return Collections.unmodifiableNavigableMap(new TreeMap<>(frames));}
    public synchronized List<FacePath.Box> boxesAt(long ms){
        return boxesAt(ms,-1);
    }
    public synchronized List<FacePath.Box> boxesAt(long ms,int omitManualId){
        List<FacePath.Box> out=new ArrayList<>();boolean replace=false;
        for(ManualTrack t:manualTracks.values()){
            FacePath.Box box=t.at(ms,cuts);
            if(box!=null){if(t.replacesSelected&&!allFaces)replace=true;if(t.id!=omitManualId)out.add(box);}
        }
        if(!manualOnly&&!replace)out.addAll(autoBoxesAt(ms));
        return out;
    }
    public synchronized List<FacePath.Box> autoBoxesAt(long ms){
        if(ms<startMs||ms>=endMs||ms>=analysedEndMs)return Collections.emptyList();
        if(!allFaces){FacePath.Point point=selectedPath.interpolated(ms);return point==null||point.box==null?Collections.emptyList():Collections.singletonList(point.box);}
        Map.Entry<Long,List<FacePath.Box>> a=frames.floorEntry(ms),b=frames.ceilingEntry(ms);
        if(a==null)return Collections.emptyList();
        if(a.getKey()==ms)return a.getValue();
        if(ms-a.getKey()>120)return Collections.emptyList();
        if(b==null||crosses(a.getKey(),b.getKey()))return a.getValue();
        if(b.getKey()-a.getKey()>220)return a.getValue();
        boolean[] used=new boolean[b.getValue().size()];ArrayList<FacePath.Box> out=new ArrayList<>();
        float f=(ms-a.getKey())/(float)(b.getKey()-a.getKey());
        for(FacePath.Box left:a.getValue()){
            int best=-1;float distance=Float.MAX_VALUE;
            for(int i=0;i<used.length;i++)if(!used[i]){
                FacePath.Box right=b.getValue().get(i);float d=left.dist(right);
                float ratio=right.area()/Math.max(.0001f,left.area());
                if(d<distance&&d<Math.max(.08f,Math.max(left.w,left.h)*.85f)&&ratio>.45f&&ratio<2.2f){distance=d;best=i;}
            }
            if(best<0){out.add(left);continue;}
            used[best]=true;FacePath.Box right=b.getValue().get(best);
            out.add(new FacePath.Box(left.x+(right.x-left.x)*f,left.y+(right.y-left.y)*f,
                left.w+(right.w-left.w)*f,left.h+(right.h-left.h)*f,-1));
        }
        return out;
    }
    public synchronized long nextReview(long after){
        boolean previousReview=false;long previousMs=-1;
        if(!allFaces){for(FacePath.Point p:selectedPath.points()){
            if(endMs>startMs&&(p.ms<startMs||p.ms>=endMs))continue;
            boolean review=p.box==null||p.reason.contains("REVIEW");
            if(review&&(!previousReview||p.ms-previousMs>220||cuts.contains(p.ms))&&p.ms>after)return p.ms;
            previousReview=review;previousMs=p.ms;
        }}else for(Map.Entry<Long,List<FacePath.Box>> e:frames.entrySet()){
            long ms=e.getKey();if(endMs>startMs&&(ms<startMs||ms>=endMs))continue;boolean review=e.getValue().isEmpty()||cuts.contains(ms);
            if(review&&(!previousReview||ms-previousMs>220||cuts.contains(ms))&&ms>after)return ms;
            previousReview=review;previousMs=ms;
        }
        return -1;
    }
    public synchronized int missing(){if(!allFaces)return selectedPath.uncoveredCount();int n=0;for(List<?> f:frames.values())if(f.isEmpty())n++;return n;}
    public int columns(){return Math.max(3,30-strength*27/100);}
}

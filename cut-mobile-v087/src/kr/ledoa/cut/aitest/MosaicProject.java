package kr.ledoa.cut.aitest;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Geometry-only project data. Face embeddings and identity galleries are never persisted. */
public final class MosaicProject {
    private static JSONObject box(FacePath.Box box)throws JSONException{
        JSONObject value=new JSONObject();value.put("x",box.x);value.put("y",box.y);value.put("w",box.w);value.put("h",box.h);return value;
    }
    private static FacePath.Box box(JSONObject value)throws JSONException{
        float x=(float)value.getDouble("x"),y=(float)value.getDouble("y"),w=(float)value.getDouble("w"),h=(float)value.getDouble("h");
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(w)||!Float.isFinite(h)||x<0||y<0||w<=0||h<=0||x+w>1.001f||y+h>1.001f)throw new JSONException("Invalid box");return new FacePath.Box(x,y,w,h,-1);
    }
    public static void save(File file,MosaicTimeline timeline,long duration,int active)throws Exception{
        JSONObject root=new JSONObject();root.put("version",1);root.put("duration",duration);root.put("active",active);root.put("start",timeline.startMs);root.put("end",timeline.endMs);root.put("analysedEnd",timeline.analysedEndMs);root.put("all",timeline.allFaces);root.put("manual",timeline.manualOnly);root.put("strength",timeline.strength);root.put("margin",timeline.margin);root.put("cuts",new JSONArray(timeline.cuts()));
        JSONArray samples=new JSONArray();for(Map.Entry<Long,List<FacePath.Box>> entry:timeline.samples().entrySet()){
            JSONObject frame=new JSONObject();frame.put("ms",entry.getKey());JSONArray boxes=new JSONArray();for(FacePath.Box item:entry.getValue())boxes.put(box(item));frame.put("boxes",boxes);samples.put(frame);
        }root.put("samples",samples);
        JSONArray path=new JSONArray();for(FacePath.Point point:timeline.selectedPath.points()){
            JSONObject item=new JSONObject();item.put("ms",point.ms);item.put("status",point.status.name());item.put("reason",point.reason);if(point.box!=null)item.put("box",box(point.box));path.put(item);
        }root.put("path",path);
        JSONArray tracks=new JSONArray();for(ManualTrack track:timeline.manualTracks()){
            JSONObject item=new JSONObject();item.put("id",track.id);item.put("replace",track.replacesSelected);item.put("start",track.start());item.put("end",track.end());JSONArray points=new JSONArray();
            for(Map.Entry<Long,FacePath.Box> entry:track.points().entrySet()){JSONObject point=box(entry.getValue());point.put("ms",entry.getKey());points.put(point);}item.put("points",points);tracks.put(item);
        }root.put("tracks",tracks);
        File part=new File(file.getParentFile(),file.getName()+".part");try(FileOutputStream output=new FileOutputStream(part)){output.write(root.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
        if(!part.renameTo(file)){part.delete();throw new IOException("모자이크 지점을 저장하지 못했습니다.");}
    }
    public static int restore(File file,MosaicTimeline target,long duration)throws Exception{
        if(file.length()>24*1024*1024)throw new IOException("모자이크 지점 파일이 너무 큽니다.");byte[] bytes;
        try(InputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[32768];int count;while((count=input.read(buffer))>0)output.write(buffer,0,count);bytes=output.toByteArray();}
        JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));if(root.getInt("version")!=1||Math.abs(root.getLong("duration")-duration)>100)throw new IOException("원본 영상과 모자이크 지점이 다릅니다.");
        MosaicTimeline next=new MosaicTimeline();next.allFaces=root.getBoolean("all");next.manualOnly=root.getBoolean("manual");next.rangeAuto(root.getLong("start"),root.getLong("end"),duration);next.analysedEndMs=root.getLong("analysedEnd");next.strength=Math.max(1,Math.min(100,root.getInt("strength")));next.margin=Math.max(0,Math.min(60,root.getInt("margin")));
        if(next.analysedEndMs<0||next.analysedEndMs>duration)throw new IOException("Invalid coverage");
        JSONArray cuts=root.getJSONArray("cuts");for(int i=0;i<cuts.length();i++){long at=cuts.getLong(i);if(at<0||at>=duration)throw new IOException("Invalid cut");next.cut(at);}
        JSONArray samples=root.getJSONArray("samples");if(samples.length()>200000)throw new IOException("Too many frames");for(int i=0;i<samples.length();i++){
            JSONObject item=samples.getJSONObject(i);long at=item.getLong("ms");if(at<0||at>=duration)throw new IOException("Invalid sample time");JSONArray boxes=item.getJSONArray("boxes");if(boxes.length()>MosaicGl.MAX_FACES)throw new IOException("Too many faces");List<FacePath.Box> frame=new ArrayList<>();for(int j=0;j<boxes.length();j++)frame.add(box(boxes.getJSONObject(j)));next.put(at,frame);
        }
        List<FacePath.Point> path=new ArrayList<>();JSONArray points=root.getJSONArray("path");if(points.length()>200000)throw new IOException("Too many points");for(int i=0;i<points.length();i++){
            JSONObject item=points.getJSONObject(i);long at=item.getLong("ms");if(at<0||at>=duration)throw new IOException("Invalid point time");path.add(new FacePath.Point(at,item.has("box")?box(item.getJSONObject("box")):null,FacePath.Status.valueOf(item.getString("status")),-1,item.optString("reason")));
        }
        JSONArray tracks=root.getJSONArray("tracks");if(tracks.length()>16)throw new IOException("Too many tracks");for(int i=0;i<tracks.length();i++){
            JSONObject item=tracks.getJSONObject(i);ManualTrack track=new ManualTrack(item.getInt("id"),item.getBoolean("replace"),item.getLong("start"),item.getLong("end"));if(track.end()>duration||next.manual(track.id)!=null)throw new IOException("Invalid manual range");
            JSONArray keys=item.getJSONArray("points");if(keys.length()>100000)throw new IOException("Too many keys");for(int j=0;j<keys.length();j++){JSONObject key=keys.getJSONObject(j);long at=key.getLong("ms");if(at<0||at>duration)throw new IOException("Invalid key time");track.put(at,box(key));}next.addManual(track);
        }
        target.reset();target.allFaces=next.allFaces;target.manualOnly=next.manualOnly;target.rangeAuto(next.startMs,next.endMs,duration);target.analysedEndMs=next.analysedEndMs;target.strength=next.strength;target.margin=next.margin;
        for(long cut:next.cuts())target.cut(cut);for(Map.Entry<Long,List<FacePath.Box>> entry:next.samples().entrySet())target.put(entry.getKey(),entry.getValue());target.restoreManual(next.manualTracks());target.selectedPath.restoreGeometry(path,target.cuts(),target.analysedEndMs);
        int active=root.optInt("active",-1);return target.manual(active)==null?-1:active;
    }
}

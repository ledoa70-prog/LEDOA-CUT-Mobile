#!/usr/bin/env python3
"""v0.7.9 harden v0.7.8 ROI face detection in separate preview APK only."""
from pathlib import Path
import os
project=Path(os.environ["PROJECT"])
activity=project/"app/src/main/java/kr/ledoa/cut/aitest/MainActivity.java"
gradle=project/"app/build.gradle"
manifest=project/"app/src/main/AndroidManifest.xml"
s=activity.read_text(encoding="utf-8")
def change(a,b):
    global s
    n=s.count(a)
    if n!=1:raise ValueError("v079 expected one matching anchor (%d): %s"%(n,a[:100]))
    s=s.replace(a,b)
change("AI 얼굴 ROI 복합 추적 TEST 0.7.8","AI 얼굴 안전추적 TEST 0.7.9")
change("LEDOA_FACE_ROI_TRACK_v0.7.8.json","LEDOA_FACE_STABLE_TRACK_v0.7.9.json")
change("""private int roiAttempts=0,roiAccepted=0,roiDiscarded=0,roiAmbiguous=0;""",
"""private int roiAttempts=0,roiAccepted=0,roiDiscarded=0,roiAmbiguous=0;
    private int roiThrottled=0,roiMemoryFailures=0;
    private boolean roiDisabled=false;
    private long roiLastScanMs=-10000;""")
change("""roiAttempts=roiAccepted=roiDiscarded=roiAmbiguous=0;""",
"""roiAttempts=roiAccepted=roiDiscarded=roiAmbiguous=0;
                roiThrottled=roiMemoryFailures=0;roiDisabled=false;roiLastScanMs=-10000;""")
change("""if(frame==null || roiDetector==null || cancel.get())return full;""",
"""if(frame==null || roiDetector==null || cancel.get() || roiDisabled)return full;""")
change("""        roiAttempts++;
        List<FacePath.Box> faces=new ArrayList<>();""",
"""        // 300ms cooldown stops 100ms video samples from repeatedly allocating
        // and invoking a SECOND detector while ML Kit/GC is still catching up.
        if(ms-roiLastScanMs<300){roiThrottled++;return full;}
        roiLastScanMs=ms;
        roiAttempts++;
        List<FacePath.Box> faces=new ArrayList<>();""")
change("""            Bitmap crop=null;
            try{
                crop=Bitmap.createBitmap(frame,l,t,w,h);
                List<Face> detected=Tasks.await(roiDetector.process(InputImage.fromBitmap(crop,0)));
                for(Face f:detected){
                    Rect local=f.getBoundingBox();
                    Rect global=new Rect(l+local.left,t+local.top,l+local.right,t+local.bottom);""",
"""            Bitmap crop=null,downscaled=null;
            try{
                crop=Bitmap.createBitmap(frame,l,t,w,h);
                Bitmap input=crop;
                int longest=Math.max(w,h);
                if(longest>480){
                    float factor=480f/longest;
                    int iw=Math.max(1,Math.round(w*factor));
                    int ih=Math.max(1,Math.round(h*factor));
                    downscaled=Bitmap.createScaledBitmap(crop,iw,ih,true);
                    input=downscaled;
                }
                float sx=w/(float)input.getWidth(),sy=h/(float)input.getHeight();
                List<Face> detected=Tasks.await(roiDetector.process(InputImage.fromBitmap(input,0)));
                for(Face f:detected){
                    Rect local=f.getBoundingBox();
                    Rect global=new Rect(l+Math.round(local.left*sx),t+Math.round(local.top*sy),
                                         l+Math.round(local.right*sx),t+Math.round(local.bottom*sy));""")
change("""            }catch(Exception e){
                android.util.Log.w("LEDOA-AI","Crop face search unavailable",e);
            }finally{if(crop!=null&&!crop.isRecycled())crop.recycle();}""",
"""            }catch(OutOfMemoryError oom){
                // Optional ROI must NEVER take down the working face tracker.
                roiMemoryFailures++;roiDisabled=true;
                android.util.Log.e("LEDOA-AI","ROI disabled for this video: memory exhausted",oom);
                faces.clear();break;
            }catch(Exception e){
                android.util.Log.w("LEDOA-AI","Crop face search unavailable",e);
            }finally{
                if(downscaled!=null&&downscaled!=crop&&!downscaled.isRecycled())downscaled.recycle();
                if(crop!=null&&!crop.isRecycled())crop.recycle();
            }""")
change("""        if(winner!=null && best>=.70f && (runner<0 || best-runner>=.065f)){
            roiAccepted++;
            return java.util.Collections.singletonList(winner);
        }
        if(unique.size()>1){roiAmbiguous++;roiDiscarded++;return new ArrayList<>();}
        if(full.size()>5){roiDiscarded++;return new ArrayList<>();}
        return full;""",
"""        // ROI may rescue only when the primary detector produced NO candidates.
        // Never replace a normal full-frame detection with a small nearby stranger.
        // Also demand high visual similarity rather than clothes alone.
        if(full.isEmpty() && winner!=null && best>=.85f &&
           (runner<0 || best-runner>=.10f)){
            roiAccepted++;
            return java.util.Collections.singletonList(winner);
        }
        if(unique.size()>1){roiAmbiguous++;roiDiscarded++;}
        if(full.size()>5){roiDiscarded++;return new ArrayList<>();}
        return full;""")
change("""            obj.put("roiNoiseFramesDiscarded",roiDiscarded);""",
"""            obj.put("roiNoiseFramesDiscarded",roiDiscarded);
            obj.put("roiSearchThrottled",roiThrottled);
            obj.put("roiMemoryFailures",roiMemoryFailures);
            obj.put("roiDisabledForMemory",roiDisabled);
            obj.put("tinyBodyFaceRejected",path.wrongSizeBodyRejectedCount());""")
change("""        return retriever.getScaledFrameAtTime(Math.max(0,ms)*1000,MediaMetadataRetriever.OPTION_CLOSEST,960,960);""",
"""        // Bounded-size frame decode lowers bitmap allocation pressure on Fold phones.
        return retriever.getScaledFrameAtTime(Math.max(0,ms)*1000,MediaMetadataRetriever.OPTION_CLOSEST,720,720);""")
change("""            }catch(Exception e){runOnUiThread(()->{analysing=false;controls();status.setText("분석 중 오류: "+e.getMessage());});}""",
"""            }catch(OutOfMemoryError oom){
                // Retain partial track in memory so user can export diagnostic JSON.
                cancel.set(true);roiDisabled=true;roiMemoryFailures++;
                android.util.Log.e("LEDOA-AI","Analysis stopped safely due to low memory",oom);
                runOnUiThread(()->{
                    analysing=false;controls();
                    status.setText("메모리 부족으로 분석이 중단됐습니다. 현재까지의 JSON은 저장할 수 있습니다.");
                });
            }catch(Exception e){
                android.util.Log.e("LEDOA-AI","Analysis error",e);
                runOnUiThread(()->{analysing=false;controls();status.setText("분석 중 오류: "+e.getMessage());});
            }""")
change("""                    " · 보조 얼굴검출 "+roiAccepted+"/"+roiAttempts+
                    " · 반드시 영상 확인 / MP4 저장 미지원.");""",
"""                    " · 보조 얼굴검출 "+roiAccepted+"/"+roiAttempts+
                    (roiDisabled?" · ROI 메모리 보호": "")+
                    " · 반드시 영상 확인 / MP4 저장 미지원.");""")
activity.write_text(s,encoding="utf-8")

g=gradle.read_text(encoding="utf-8")
assert "versionCode 708" in g and "applicationId 'kr.ledoa.cut.aiface078'" in g
g=g.replace("versionCode 708","versionCode 709")
g=g.replace("versionName '0.7.8-roi-face-rescue'","versionName '0.7.9-stable-roi'")
g=g.replace("applicationId 'kr.ledoa.cut.aiface078'","applicationId 'kr.ledoa.cut.aiface079'")
gradle.write_text(g,encoding="utf-8")
m=manifest.read_text(encoding="utf-8")
assert "LEDOA AI ROI 얼굴 추적 v0.7.8" in m
m.write_text(m.replace("LEDOA AI ROI 얼굴 추적 v0.7.8",
                       "LEDOA AI 안전추적 v0.7.9"),encoding="utf-8")
assert 'roiMemoryFailures' in s and 'tinyBodyFaceRejected' in s
print("PASS: v0.7.9 memory limits, ROI throttling, wrong-target safe fallback integrated")

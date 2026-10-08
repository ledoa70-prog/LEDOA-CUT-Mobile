#!/usr/bin/env python3
"""Install provisional motion bridging into the isolated v0.7.9 preview Android app."""
from pathlib import Path
import os
project=Path(os.environ["PROJECT"])
activity=project/"app/src/main/java/kr/ledoa/cut/aitest/MainActivity.java"
gradle=project/"app/build.gradle"
manifest=project/"app/src/main/AndroidManifest.xml"
s=activity.read_text(encoding="utf-8")
def change(a,b):
    global s
    assert s.count(a)==1, "Unexpected previous source: %d matches of %s"%(s.count(a),a[:110])
    s=s.replace(a,b)
change("AI 얼굴 안전추적 TEST 0.7.9","AI 얼굴+움직임 추적 TEST 0.8.0")
change("LEDOA_FACE_STABLE_TRACK_v0.7.9.json","LEDOA_FACE_FLOW_TRACK_v0.8.0.json")
change("private long roiLastScanMs=-10000;",
"""private long roiLastScanMs=-10000;
    private final OpticalBridge opticalBridge=new OpticalBridge();""")
change("""                roiThrottled=roiMemoryFailures=0;roiDisabled=false;roiLastScanMs=-10000;""",
"""                roiThrottled=roiMemoryFailures=0;roiDisabled=false;roiLastScanMs=-10000;
                opticalBridge.reset();""")
change("""                path.anchor(start,anchor);
                int failed=0,total=0,bodyOnly=0;""",
"""                path.anchor(start,anchor);
                opticalBridge.reset();
                // Seed optical flow with the exact user-selected face and source frame.
                Bitmap initialFrame=bitmapAt(start);
                try{
                    MotionFrames.Gray seed=MotionFrames.grayscale(initialFrame);
                    if(seed!=null) opticalBridge.anchor(start,seed.pixels,seed.w,seed.h,anchor);
                }finally{if(initialFrame!=null&&!initialFrame.isRecycled())initialFrame.recycle();}
                int failed=0,total=0,bodyOnly=0;""")
change("""                    List<FacePath.Box> found;
                    BodyClothing.Assist assist=null;""",
"""                    List<FacePath.Box> found;
                    MotionFrames.Gray gray=null;
                    BodyClothing.Assist assist=null;""")
change("""                        found=detectWithRoi(frame,ms,found,assist);
                    }finally{if(frame!=null)frame.recycle();}
                    FacePath.Point p=path.step(ms,found,assist);total++;""",
"""                        found=detectWithRoi(frame,ms,found,assist);
                        // Recycle the full bitmap after extracting one tiny grayscale copy.
                        // This works off the same timestamp as face detection.
                        if(frame!=null)gray=MotionFrames.grayscale(frame);
                    }finally{if(frame!=null)frame.recycle();}
                    FacePath.Point p=path.step(ms,found,assist);total++;
                    if(gray!=null){
                        if(p.status==FacePath.Status.TRACKED || p.status==FacePath.Status.VERIFIED){
                            opticalBridge.anchor(ms,gray.pixels,gray.w,gray.h,p.box);
                        }else if(found.isEmpty()){
                            OpticalBridge.Estimate motion=opticalBridge.advance(
                                   ms,gray.pixels,gray.w,gray.h,assist);
                            if(motion!=null)
                                p=path.putMotionEstimate(ms,motion.box,motion.confidence);
                        }else{
                            // Competing face detections: never force an optical jump.
                            opticalBridge.invalidate();
                        }
                    }""")
change("""            obj.put("tinyBodyFaceRejected",path.wrongSizeBodyRejectedCount());""",
"""            obj.put("tinyBodyFaceRejected",path.wrongSizeBodyRejectedCount());
            obj.put("flowEstimatedFrames",path.flowEstimatedCount());
            obj.put("flowMatchingRejects",opticalBridge.rejectedCount());
            obj.put("flowExpiredGaps",opticalBridge.expiredCount());
            obj.put("flowMaximumUnverifiedMs",OpticalBridge.MAX_FACE_MISSING_MS);
            obj.put("flowRequiresReview",true);""")
change("""                    (roiDisabled?" · ROI 메모리 보호": "")+
                    " · 반드시 영상 확인 / MP4 저장 미지원.");""",
"""                    (roiDisabled?" · ROI 메모리 보호": "")+
                    " · 움직임 임시추적 "+opticalBridge.estimatedCount()+
                    " · 영상 검토 필수 / MP4 저장 미지원.");""")
change("""            for(FacePath.Box b:faces){
                RectF rect=rectFor(b);""",
"""            if(nearest!=null && nearest.status==FacePath.Status.FLOW_ESTIMATED){
                line.setColor(Color.YELLOW);line.setTextSize(dp(14));
                c.drawText("움직임 기반 임시 가림 · 직접 확인 필요",
                    left+dp(6),top+dp(45),line);
            }
            for(FacePath.Box b:faces){
                RectF rect=rectFor(b);""")
activity.write_text(s,encoding="utf-8")

g=gradle.read_text(encoding="utf-8")
assert "versionCode 709" in g and "applicationId 'kr.ledoa.cut.aiface079'" in g
g=g.replace("versionCode 709","versionCode 800")
g=g.replace("versionName '0.7.9-stable-roi'","versionName '0.8.0-optical-bridge'")
g=g.replace("applicationId 'kr.ledoa.cut.aiface079'","applicationId 'kr.ledoa.cut.aiface080'")
gradle.write_text(g,encoding="utf-8")

m=manifest.read_text(encoding="utf-8")
assert "LEDOA AI 안전추적 v0.7.9" in m
manifest.write_text(m.replace("LEDOA AI 안전추적 v0.7.9",
                               "LEDOA AI 움직임 추적 v0.8.0"),encoding="utf-8")
assert "opticalBridge.advance" in s and "flowRequiresReview" in s
print("PASS: 0.8.0 bounded optical-flow mask previews wired into independent APK")

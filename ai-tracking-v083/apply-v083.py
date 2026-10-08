#!/usr/bin/env python3
"""v0.8.3 analysis crash fix: lazy pyramid on first detector miss; no extra ROI model."""
import os
from pathlib import Path
project=Path(os.environ["PROJECT"])
activity=project/"app/src/main/java/kr/ledoa/cut/aitest/MainActivity.java"
gradle=project/"app/build.gradle"
manifest=project/"app/src/main/AndroidManifest.xml"
s=activity.read_text(encoding="utf-8")
def change(a,b):
    global s
    n=s.count(a)
    assert n==1,f"v0.8.3 source mismatch, expected one occurrence, found {n}: {a[:120]}"
    s=s.replace(a,b)
change("AI 얼굴 확대 보조 추적 TEST 0.8.2","AI 안정화 지연추적 TEST 0.8.3")
change("LEDOA_FACE_FLOW_TRACK_v0.8.2.json","LEDOA_FACE_FLOW_TRACK_v0.8.3.json")
change("LEDOA_FACE_CRASH_v0.8.1.txt","LEDOA_FACE_CRASH_v0.8.3.txt")
change('obj.put("analysisStabilityVersion","0.8.1")','obj.put("analysisStabilityVersion","0.8.3")')
change("""    private final OpticalBridge opticalBridge=new OpticalBridge();""",
"""    private final OpticalBridge opticalBridge=new OpticalBridge();
    private final LazyFlowBridge lazyFlow=new LazyFlowBridge(opticalBridge);
    private static final boolean ROI_DISABLED_FOR_STABILITY=true;""")
change("roiDetector=FaceDetection.getClient(rescueOptions);",
"""// No separate ROI ML detector in stability mode.
        // It rescued zero frames in v0.8.2 but consumed native ML memory.
        roiDetector=null;""")
change("""                    " · 확대추적 보완 "+opticalBridge.estimatedCount()+""",
"""                    " · 확대추적 보완 "+path.flowEstimatedCount()+""")
change("""                opticalBridge.reset();
                // Seed optical flow with the exact user-selected face and source frame.""",
"""                lazyFlow.reset();
                // Cache selected frame, but defer expensive pyramid/corner extraction
                // until a REAL detector blackout is encountered.""")
change("""                    if(seed!=null) opticalBridge.anchor(start,seed.pixels,seed.w,seed.h,anchor);""",
"""                    if(seed!=null)lazyFlow.trusted(start,seed.pixels,seed.w,seed.h,anchor);""")
change("""                            if(((ms-start)/STEP_MS)%3==0){""",
"""                            if(((ms-start)/STEP_MS)%5==0){""")
change("""                        diagnostics.progress(ms,"OPTIONAL_ROI_FACE_MODEL");
                        found=detectWithRoi(frame,ms,found,assist);""",
"""                        // A second ML Kit face model produced zero successful
                        // rescues; avoid extra native inference during stability
                        // tests. Keep the accurate primary detector untouched.
                        diagnostics.progress(ms,"ROI_SKIPPED_STABILITY");
                        found=detectWithRoi(frame,ms,found,assist);""")
change("""                        if(p.status==FacePath.Status.TRACKED || p.status==FacePath.Status.VERIFIED){
                            opticalBridge.anchor(ms,gray.pixels,gray.w,gray.h,p.box);
                        }else if(found.isEmpty() ||
                                 (found.size()<=2 && "MOTION_OR_SCALE_GATE".equals(p.reason))){
                            // A rejected nonempty detection must not wipe the
                            // last reliable motion state before the blackout.
                            // Only blank frames may receive a provisional mask.
                            diagnostics.progress(ms,"OPTICAL_PYRAMID_ZOOM");
                            OpticalBridge.Estimate motion=opticalBridge.advance(
                                   ms,gray.pixels,gray.w,gray.h,assist);
                            if(motion!=null && found.isEmpty())
                                p=path.putMotionEstimate(ms,motion.box,motion.confidence);
                        }else{
                            // Multiple/ambiguous faces: never jump to a bystander.
                            opticalBridge.invalidate();
                        }""",
"""                        if(p.status==FacePath.Status.TRACKED || p.status==FacePath.Status.VERIFIED){
                            // Cheap save of the last real face frame only.
                            // Before v0.8.3 expensive pyramid+corner construction
                            // was wrongly done on EVERY trusted 100ms frame.
                            lazyFlow.trusted(ms,gray.pixels,gray.w,gray.h,p.box);
                        }else if(p.status==FacePath.Status.LOST &&
                                 (found.isEmpty() ||
                                  (found.size()<=2 && "MOTION_OR_SCALE_GATE".equals(p.reason)))){
                            diagnostics.progress(ms,"OPTICAL_LAZY_FIRST_GAP");
                            OpticalBridge.Estimate motion=lazyFlow.gap(
                                   ms,gray.pixels,gray.w,gray.h,assist,true);
                            // Never mask a detected bystander or a back-of-head.
                            if(motion!=null && found.isEmpty())
                                p=path.putMotionEstimate(ms,motion.box,motion.confidence);
                        }else{
                            lazyFlow.ambiguous();
                        }""")
change("""            obj.put("analysisStabilityVersion","0.8.3");""",
"""            obj.put("analysisStabilityVersion","0.8.3");
            obj.put("roiDisabledForStability",ROI_DISABLED_FOR_STABILITY);
            obj.put("flowLazyTrustedFrames",lazyFlow.trustedUpdates());
            obj.put("flowLazyPyramidBuilds",lazyFlow.lazySeeds());
            obj.put("flowLazyAttempts",lazyFlow.flowAttempts());
            obj.put("flowLazySkipped",lazyFlow.flowSkips());""")
change("""return retriever.getScaledFrameAtTime(Math.max(0,ms)*1000,MediaMetadataRetriever.OPTION_CLOSEST,720,720);""",
"""return retriever.getScaledFrameAtTime(Math.max(0,ms)*1000,MediaMetadataRetriever.OPTION_CLOSEST,640,640);""")
# Update previous diagnostic label when relaunching after a prior crash.
change("""        if(interrupted)status.setText("이전 얼굴 추적이 비정상 종료됐을 수 있습니다. '진단 기록 저장'을 눌러 보내주세요.");""",
"""        if(interrupted)status.setText("이전 얼굴 추적이 중단됐습니다. '진단 기록 저장'으로 v0.8.3 오류 정보를 보내주세요.");""")
activity.write_text(s,encoding="utf-8")

g=gradle.read_text(encoding="utf-8")
assert "versionCode 802" in g and "applicationId 'kr.ledoa.cut.aiface082'" in g
g=g.replace("versionCode 802","versionCode 803")
g=g.replace("versionName '0.8.2-zoom-optical-pyramid'","versionName '0.8.3-stable-lazy-flow'")
g=g.replace("applicationId 'kr.ledoa.cut.aiface082'","applicationId 'kr.ledoa.cut.aiface083'")
gradle.write_text(g,encoding="utf-8")
m=manifest.read_text(encoding="utf-8")
assert "LEDOA AI 얼굴 확대 보완 v0.8.2" in m
manifest.write_text(m.replace("LEDOA AI 얼굴 확대 보완 v0.8.2",
    "LEDOA AI 안정화 지연추적 v0.8.3"),encoding="utf-8")
assert "lazyFlow.trusted(" in s and "roiDetector=null" in s
print("PASS: v0.8.3 on-demand optical features; ROI off; 640px decode; versioned diagnostics")

#!/usr/bin/env python3
"""Apply v0.8.2 actual-frame-tuned pyramid optical bridge to isolated app."""
import os
from pathlib import Path
project=Path(os.environ["PROJECT"])
main=project/"app/src/main/java/kr/ledoa/cut/aitest/MainActivity.java"
gradle=project/"app/build.gradle"
manifest=project/"app/src/main/AndroidManifest.xml"
s=main.read_text(encoding="utf8")
def change(a,b):
    global s
    if s.count(a)!=1:raise RuntimeError("v082 source layout changed: "+a[:100])
    s=s.replace(a,b)
change("AI 안정화+진단 TEST 0.8.1","AI 얼굴 확대 보조 추적 TEST 0.8.2")
change("LEDOA_FACE_FLOW_TRACK_v0.8.1.json","LEDOA_FACE_FLOW_TRACK_v0.8.2.json")
change("""            obj.put("flowRequiresReview",true);""",
"""            obj.put("flowRequiresReview",true);
            obj.put("flowEngine","PYRAMID_LK_ZOOM_GUARDED");
            obj.put("flowRejectionsFewPoints",opticalBridge.rejectedFewPoints());
            obj.put("flowRejectionsPhotometric",opticalBridge.rejectedPhotometric());
            obj.put("flowRejectionsGeometry",opticalBridge.rejectedGeometry());
            obj.put("flowRejectionsBody",opticalBridge.rejectedBody());
            obj.put("flowRejectionsOutside",opticalBridge.rejectedOutside());
            obj.put("flowRejectionsElapsed",opticalBridge.rejectedElapsed());
            obj.put("flowRejectionsNoAnchor",opticalBridge.rejectedNoAnchor());""")
change("diagnostics.progress(ms,\"OPTICAL_MOTION\");",
       "diagnostics.progress(ms,\"OPTICAL_PYRAMID_ZOOM\");")
change("""                    " · 움직임 임시추적 "+opticalBridge.estimatedCount()+""",
       """                    " · 확대추적 보완 "+opticalBridge.estimatedCount()+""")
main.write_text(s,encoding="utf8")
g=gradle.read_text(encoding="utf8")
assert "versionCode 801" in g and "applicationId 'kr.ledoa.cut.aiface081'" in g
g=g.replace("versionCode 801","versionCode 802")
g=g.replace("versionName '0.8.1-crash-diagnostics'","versionName '0.8.2-zoom-optical-pyramid'")
g=g.replace("applicationId 'kr.ledoa.cut.aiface081'","applicationId 'kr.ledoa.cut.aiface082'")
gradle.write_text(g,encoding="utf8")
m=manifest.read_text(encoding="utf8")
assert "LEDOA AI 안정화 진단 v0.8.1" in m
manifest.write_text(m.replace("LEDOA AI 안정화 진단 v0.8.1",
                              "LEDOA AI 얼굴 확대 보완 v0.8.2"),encoding="utf8")
print("PASS: v0.8.2 optical pyramid zoom-tracking and rejection diagnostics integrated")

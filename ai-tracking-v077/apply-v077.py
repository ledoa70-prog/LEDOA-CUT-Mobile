#!/usr/bin/env python3
"""v0.7.7 independent Android preview patch, based on verified v0.7.6 source."""
from pathlib import Path
import os
project=Path(os.environ["PROJECT"])
p=project/"app/src/main/java/kr/ledoa/cut/aitest/MainActivity.java"
s=p.read_text(encoding="utf-8")
def change(a,b):
    global s
    assert s.count(a)==1, "Unexpected v076 source: "+str(s.count(a))+" x "+a[:90]
    s=s.replace(a,b)

change("AI 얼굴+옷 재추적 TEST 0.7.6","AI 얼굴+옷 복합 추적 TEST 0.7.7")
change("LEDOA_FACE_BODY_TRACK_v0.7.6.json","LEDOA_FACE_BODY_TRACK_v0.7.7.json")
change("""                                latestAssist=bodyClothing.observe(ms,ob);
                                latestAssistMs=ms;""",
"""                                latestAssist=bodyClothing.observe(ms,ob);
                                // If pose inference briefly fails completely, retain a
                                // decaying location for <=300ms. Never reuse after
                                // an explicit clothing mismatch.
                                if(latestAssist==null && ob==null)
                                    latestAssist=bodyClothing.recent(ms);
                                latestAssistMs=ms;""")
change("""            obj.put("screenEdgeReacquired",path.edgeRecoveredCount());""",
"""            obj.put("screenEdgeReacquired",path.edgeRecoveredCount());
            obj.put("scaleChangeReacquired",path.scaleRecoveredCount());
            obj.put("scaleCandidateRejected",path.scaleRejectedCount());""")
change("""                    " · 옷으로 얼굴 재연결 "+path.bodyRecoveredCount()+
                    " · 미리보기 검토 필수 / MP4 저장 미지원.");""",
"""                    " · 옷으로 얼굴 재연결 "+path.bodyRecoveredCount()+
                    " · 얼굴 크기변화 재연결 "+path.scaleRecoveredCount()+
                    " · 미리보기 검토 필수 / MP4 저장 미지원.");""")
p.write_text(s,encoding="utf-8")
g=project/"app/build.gradle"
gradle=g.read_text(encoding="utf-8")
assert gradle.count("versionCode 706")==1
assert gradle.count("applicationId 'kr.ledoa.cut.aiface076'")==1
gradle=gradle.replace("versionCode 706","versionCode 707")
gradle=gradle.replace("versionName '0.7.6-body-relink'","versionName '0.7.7-body-scale-recovery'")
gradle=gradle.replace("applicationId 'kr.ledoa.cut.aiface076'","applicationId 'kr.ledoa.cut.aiface077'")
g.write_text(gradle,encoding="utf-8")
m=project/"app/src/main/AndroidManifest.xml"
manifest=m.read_text(encoding="utf-8")
assert "LEDOA AI 얼굴 옷 재연결 v0.7.6" in manifest
m.write_text(manifest.replace("LEDOA AI 얼굴 옷 재연결 v0.7.6",
"LEDOA AI 복합 추적 v0.7.7"),encoding="utf-8")
print("PASS: v0.7.7 dynamic scale and short torso continuity integrated; distinct package ID")

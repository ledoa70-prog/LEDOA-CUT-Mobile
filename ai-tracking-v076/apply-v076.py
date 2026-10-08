#!/usr/bin/env python3
"""Apply body-assisted face association v0.7.6 only to isolated v0.7.5 build tree."""
from pathlib import Path
import os
project=Path(os.environ["PROJECT"])
src=project/"app/src/main/java/kr/ledoa/cut/aitest/MainActivity.java"
gradle=project/"app/build.gradle"
manifest=project/"app/src/main/AndroidManifest.xml"
s=src.read_text(encoding="utf-8")
def replace(old,new):
    global s
    assert s.count(old)==1, f"expected 1 occurrence, got {s.count(old)}: {old[:80]}"
    s=s.replace(old,new)

replace("AI 얼굴+상반신 추적 TEST 0.7.5","AI 얼굴+옷 재추적 TEST 0.7.6")
replace("LEDOA_FACE_BODY_TRACK_v0.7.5.json","LEDOA_FACE_BODY_TRACK_v0.7.6.json")
replace("""                boolean faceEvidence=nose && (eye || mouth);
                if(!faceEvidence)continue;
                boxes.add(new FacePath.Box(x,y,right-x,bottom-y,
                        face.getTrackingId()==null?-1:face.getTrackingId(),visual,faceEvidence));""",
"""                boolean faceEvidence=nose && (eye || mouth);
                // Screen-edge face can lose one or two landmarks. Only accept an
                // actual ML Kit detection with at least one real facial landmark,
                // and require two more independent frames in FacePath before masking.
                boolean onEdge=(x<.035f||right>.965f||y<.035f);
                boolean partialEdge=!faceEvidence && onEdge && (nose || eye);
                if(!faceEvidence && !partialEdge)continue;
                boxes.add(new FacePath.Box(x,y,right-x,bottom-y,
                        face.getTrackingId()==null?-1:face.getTrackingId(),
                        visual,faceEvidence,partialEdge));""")
replace("""                            if(((ms-start)/STEP_MS)%2==0){""",
"""                            // Re-check pose more often when the face detector becomes
                            // ambiguous; the regular path remains at 200ms.
                            if(((ms-start)/STEP_MS)%2==0 || found.size()!=1){""")
replace("""            obj.put("bodyRejectedObservations",bodyClothing.rejectedCount());""",
"""            obj.put("bodyRejectedObservations",bodyClothing.rejectedCount());
            obj.put("faceBodyCorroborated",path.bodyValidatedCount());
            obj.put("faceBodyReacquired",path.bodyRecoveredCount());
            obj.put("screenEdgeReacquired",path.edgeRecoveredCount());""")
replace("""status.setText((stopped?"중단됨":"분석 완료")+" · 확인 필요 "+problems+
                    " · 상반신만 유지 "+bodyOnlyFrames+
                    " · 실제 얼굴 모자이크 확인 후 사용하세요. MP4 저장 미지원.");""",
"""status.setText((stopped?"중단됨":"분석 완료")+" · 확인 필요 "+problems+
                    " · 상반신만 유지 "+bodyOnlyFrames+
                    " · 옷으로 얼굴 재연결 "+path.bodyRecoveredCount()+
                    " · 미리보기 검토 필수 / MP4 저장 미지원.");""")
src.write_text(s,encoding="utf-8")

g=gradle.read_text(encoding="utf-8")
assert "versionCode 705" in g and "versionName '0.7.5-face-body'" in g
assert "applicationId 'kr.ledoa.cut.aiface075'" in g
g=g.replace("versionCode 705","versionCode 706")
g=g.replace("versionName '0.7.5-face-body'","versionName '0.7.6-body-relink'")
g=g.replace("applicationId 'kr.ledoa.cut.aiface075'","applicationId 'kr.ledoa.cut.aiface076'")
gradle.write_text(g,encoding="utf-8")
m=manifest.read_text(encoding="utf-8")
assert 'LEDOA AI 상반신 추적 0.7.5' in m
m=m.replace('LEDOA AI 상반신 추적 0.7.5','LEDOA AI 얼굴 옷 재연결 v0.7.6')
manifest.write_text(m,encoding="utf-8")
print("PASS: integrated v0.7.6 true body-relayed face recovery + edge detection")

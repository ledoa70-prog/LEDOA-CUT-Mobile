#!/usr/bin/env python3
"""v0.8.4 preserve lazy flow stability, restore pose continuity, guard crowded identity jumps."""
from pathlib import Path
import os
project=Path(os.environ["PROJECT"])
main=project/"app/src/main/java/kr/ledoa/cut/aitest/MainActivity.java"
gradle=project/"app/build.gradle"
manifest=project/"app/src/main/AndroidManifest.xml"
s=main.read_text(encoding="utf-8")
def change(a,b):
    global s
    n=s.count(a)
    assert n==1, f"v084 expected 1 match, got {n}: {a[:120]}"
    s=s.replace(a,b)
change("AI 안정화 지연추적 TEST 0.8.3","AI 안정화+인물보호 TEST 0.8.4")
change("LEDOA_FACE_FLOW_TRACK_v0.8.3.json","LEDOA_FACE_FLOW_TRACK_v0.8.4.json")
change("LEDOA_FACE_CRASH_v0.8.3.txt","LEDOA_FACE_CRASH_v0.8.4.txt")
change('obj.put("analysisStabilityVersion","0.8.3")',
       'obj.put("analysisStabilityVersion","0.8.4")')
change("""    private final LazyFlowBridge lazyFlow=new LazyFlowBridge(opticalBridge);""",
"""    private final LazyFlowBridge lazyFlow=new LazyFlowBridge(opticalBridge);
    private int poseInferenceAttempts=0,poseAdaptiveExtra=0;""")
change("""                lazyFlow.reset();
                // Cache selected frame""",
"""                lazyFlow.reset();
                poseInferenceAttempts=poseAdaptiveExtra=0;
                // Cache selected frame""")
change("""                int failed=0,total=0,bodyOnly=0;""",
"""                int failed=0,total=0,bodyOnly=0;
                // Protect native memory: at most 16 extra pose calls per video.
                int poseBoostRemaining=16;""")
change("""                            if(((ms-start)/STEP_MS)%5==0){
                                BodyClothing.Observation ob=null;""",
"""                            boolean regularPose=((ms-start)/STEP_MS)%3==0;
                            long sinceLastReal=ms-path.lastReliableTimestamp();
                            boolean needsPoseBoost=(found.size()!=1 ||
                                (sinceLastReal>150 && sinceLastReal<900));
                            boolean boostedPose=!regularPose && poseBoostRemaining>0 &&
                                ((ms-start)/STEP_MS)%2==0 && needsPoseBoost;
                            if(regularPose||boostedPose){
                                poseInferenceAttempts++;
                                if(boostedPose){poseBoostRemaining--;poseAdaptiveExtra++;}
                                BodyClothing.Observation ob=null;""")
change("""            obj.put("flowLazySkipped",lazyFlow.flowSkips());""",
"""            obj.put("flowLazySkipped",lazyFlow.flowSkips());
            obj.put("poseInferenceAttempts",poseInferenceAttempts);
            obj.put("poseAdaptiveExtra",poseAdaptiveExtra);
            obj.put("crowdedGapBlocked",path.crowdedGapBlockedCount());
            obj.put("privacyReviewRequiredForCrowds",true);""")
main.write_text(s,encoding="utf-8")
g=gradle.read_text(encoding="utf-8")
assert "versionCode 803" in g and "applicationId 'kr.ledoa.cut.aiface083'" in g
g=g.replace("versionCode 803","versionCode 804")
g=g.replace("versionName '0.8.3-stable-lazy-flow'","versionName '0.8.4-privacy-pose-balance'")
g=g.replace("applicationId 'kr.ledoa.cut.aiface083'","applicationId 'kr.ledoa.cut.aiface084'")
gradle.write_text(g,encoding="utf-8")
m=manifest.read_text(encoding="utf-8")
assert "LEDOA AI 안정화 지연추적 v0.8.3" in m
manifest.write_text(m.replace("LEDOA AI 안정화 지연추적 v0.8.3",
                              "LEDOA AI 안정화 인물보호 v0.8.4"),encoding="utf-8")
assert "poseBoostRemaining=16" in s and "crowdedGapBlocked" in s
print("PASS: v0.8.4 adaptive body cadence with bounded boosts, crowded re-ID guard and diagnostic counters")

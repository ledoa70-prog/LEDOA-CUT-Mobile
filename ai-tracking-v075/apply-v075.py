#!/usr/bin/env python3
"""Apply v0.7.5 body-assisted tracking to the built, independent v0.7.4 test project.
Abort on any unexpected source layout; NEVER patch main/stable LEDOA CUT.
"""
from pathlib import Path
import os

project=Path(os.environ["PROJECT"])
activity=project/"app/src/main/java/kr/ledoa/cut/aitest/MainActivity.java"
gradle=project/"app/build.gradle"
manifest=project/"app/src/main/AndroidManifest.xml"

s=activity.read_text(encoding="utf-8")
def rep(old,new):
    global s
    matches=s.count(old)
    if matches != 1:
        raise SystemExit("v075 ABORT: expected one source anchor, found "+str(matches)+": "+old[:120])
    s=s.replace(old,new)

rep("import com.google.mlkit.vision.face.FaceLandmark;",
    """import com.google.mlkit.vision.face.FaceLandmark;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseDetectorOptions;""")
rep("private FaceDetector detector;", """private FaceDetector detector;
    private PoseDetector poseDetector;
    private final BodyClothing bodyClothing=new BodyClothing();
    private BodyClothing.Observation visibleBody;""")
rep("detector=FaceDetection.getClient(opts);",
    """detector=FaceDetection.getClient(opts);
        PoseDetectorOptions poseOptions=new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build();
        poseDetector=PoseDetection.getClient(poseOptions);""")
rep("AI 얼굴 모자이크 TEST 0.7.4","AI 얼굴+상반신 추적 TEST 0.7.5")
rep("정식 앱과 분리 · 선택한 얼굴 1명 · 픽셀화 강도 85%",
    "정식 앱과 분리 · 얼굴+상반신 옷 보조 추적 · 모자이크 85%")
rep("검토용 무음 프레임 재생(기기 속도에 따라 끊길 수 있음). 빨간 확인 문구가 나오면 얼굴 가림을 검토하세요. MP4 저장 기능 없음.",
    "옷·어깨는 같은 사람 재추적에만 사용합니다. 얼굴이 안 보이면 모자이크 추정 금지. 무음 미리보기, MP4 저장 없음.")
rep("durationMs=ms;currentMs=0;anchorMs=-1;selected=null;path.reset();",
    "durationMs=ms;currentMs=0;anchorMs=-1;selected=null;path.reset();bodyClothing.reset();visibleBody=null;")
rep("""                List<FacePath.Box> boxes=detect(frame);
                if(token!=generation.get())""",
    """                List<FacePath.Box> boxes=detect(frame);
                BodyClothing.Observation pose=null;
                try{pose=PoseBodyAdapter.detect(poseDetector,frame);}
                catch(Exception e){android.util.Log.w("LEDOA-AI","Pose unavailable for selection",e);}
                final BodyClothing.Observation poseForUi=pose;
                if(token!=generation.get())""")
rep("""                    visibleFaces=boxes;frameView.setFrame(result,boxes,path.interpolated(shown),path.nearest(shown));""",
    """                    visibleFaces=boxes;visibleBody=poseForUi;
                    frameView.setFrame(result,boxes,path.interpolated(shown),path.nearest(shown));""")
rep("""        selected=picked;anchorMs=currentMs;
        path.anchor(anchorMs,picked);""",
    """        selected=picked;anchorMs=currentMs;
        path.anchor(anchorMs,picked);
        final boolean upperBodyLinked=bodyClothing.select(anchorMs,picked,visibleBody);""")
rep("""        status.setText("선택 완료 · 추적 버튼을 눌러주세요. 다른 시점에서 선택하면 그 이후 경로를 다시 계산합니다.");""",
    """        status.setText(upperBodyLinked
            ?"얼굴+상의 등록 완료 · 얼굴 추적 버튼을 눌러주세요."
            :"얼굴 선택 완료 · 상의가 명확히 보이지 않아 얼굴 단독 추적으로 진행합니다.");""")
rep("""        status.setText("얼굴 이동 분석 중… (최대 30초)");
        worker.execute(()->{""",
    """        status.setText(bodyClothing.isEnabled()
            ?"얼굴+옷+어깨를 함께 분석 중… (최대 30초)"
            :"얼굴 단독 분석 중… (상의가 보이는 장면에서 얼굴을 다시 선택하면 등록할 수 있습니다)");
        worker.execute(()->{""")
rep("""                int failed=0,total=0;
                for(long ms=start+STEP_MS;ms<=end;ms+=STEP_MS){""",
    """                int failed=0,total=0,bodyOnly=0;
                BodyClothing.Assist latestAssist=null;
                long latestAssistMs=-1;
                for(long ms=start+STEP_MS;ms<=end;ms+=STEP_MS){""")
rep("""                    List<FacePath.Box> found;
                    try{found=detect(frame);}finally{if(frame!=null)frame.recycle();}
                    FacePath.Point p=path.step(ms,found);total++;""",
    """                    List<FacePath.Box> found;
                    BodyClothing.Assist assist=null;
                    try{
                        found=detect(frame);
                        // Pose inference is relatively expensive. Sample every 200ms.
                        // Retain at most one intermediate 100ms step of fresh evidence.
                        if(bodyClothing.isEnabled()){
                            if(((ms-start)/STEP_MS)%2==0){
                                BodyClothing.Observation ob=null;
                                try{ob=PoseBodyAdapter.detect(poseDetector,frame);}
                                catch(Exception e){android.util.Log.w("LEDOA-AI","Pose frame failed",e);}
                                latestAssist=bodyClothing.observe(ms,ob);
                                latestAssistMs=ms;
                            }
                            if(latestAssist!=null && ms-latestAssistMs<=200)
                                assist=latestAssist;
                        }
                    }finally{if(frame!=null)frame.recycle();}
                    FacePath.Point p=path.step(ms,found,assist);total++;
                    if(p.status==FacePath.Status.BODY_HELD)bodyOnly++;""")
rep("""                final int problems=failed;
                final boolean stopped=cancel.get();""",
    """                final int problems=failed,bodyOnlyFrames=bodyOnly;
                final boolean stopped=cancel.get();""")
rep("""status.setText((stopped?"중단됨":"분석 완료")+" · 확인 필요 샘플 "+problems+"개. 빨간 확인 구간은 수동 확인하세요. MP4 저장은 비활성화 상태입니다.");""",
    """status.setText((stopped?"중단됨":"분석 완료")+" · 확인 필요 "+problems+
                    " · 상반신만 유지 "+bodyOnlyFrames+
                    " · 실제 얼굴 모자이크 확인 후 사용하세요. MP4 저장 미지원.");""")
rep("LEDOA_FACE_TRACK_v0.7.4.json","LEDOA_FACE_BODY_TRACK_v0.7.5.json")
rep("""obj.put("reviewCount",path.reviewCount()); // Do not disclose device-local document URIs in exported metadata.""",
    """obj.put("reviewCount",path.reviewCount());
            obj.put("bodyLinked",bodyClothing.isEnabled());
            obj.put("bodyConfirmedObservations",bodyClothing.heldCount());
            obj.put("bodyRejectedObservations",bodyClothing.rejectedCount());
            // Do not disclose device-local document URIs or raw clothing features.""")
rep("""            if(nearest!=null&&(nearest.status==FacePath.Status.LOST||nearest.status==FacePath.Status.UNCERTAIN)){""",
    """            if(nearest!=null&&(nearest.status==FacePath.Status.LOST ||
                    nearest.status==FacePath.Status.UNCERTAIN ||
                    nearest.status==FacePath.Status.BODY_HELD)){""")
rep("""                line.setColor(Color.RED);line.setTextSize(dp(17));c.drawText("추적 확인 필요 (이 구간은 가림이 없습니다)",left+dp(8),top+dp(26),line);""",
    """                line.setColor(Color.RED);line.setTextSize(dp(15));
                c.drawText(nearest.status==FacePath.Status.BODY_HELD
                    ?"옷/상반신은 추적 중 · 얼굴 미확인 (가림 없음)"
                    :"추적 확인 필요 (이 구간은 가림이 없습니다)",
                    left+dp(8),top+dp(26),line);""")
rep("""        if(detector!=null)detector.close();""",
    """        if(detector!=null)detector.close();
        if(poseDetector!=null)poseDetector.close();""")
activity.write_text(s,encoding="utf-8")

g=gradle.read_text(encoding="utf-8")
assert g.count("versionCode 704")==1
assert g.count("applicationId 'kr.ledoa.cut.aiface074'")==1
assert g.count("implementation 'com.google.mlkit:face-detection:16.1.7'")==1
g=g.replace("versionCode 704","versionCode 705").replace("versionName '0.7.4-quality-guard'","versionName '0.7.5-face-body'")
g=g.replace("applicationId 'kr.ledoa.cut.aiface074'","applicationId 'kr.ledoa.cut.aiface075'")
g=g.replace("implementation 'com.google.mlkit:face-detection:16.1.7'",
    """implementation 'com.google.mlkit:face-detection:16.1.7'
    implementation 'com.google.mlkit:pose-detection:18.0.0-beta5'""")
gradle.write_text(g,encoding="utf-8")
m=manifest.read_text(encoding="utf-8")
assert m.count("LEDOA AI 얼굴 추적 0.7.4")==1
m=m.replace("LEDOA AI 얼굴 추적 0.7.4","LEDOA AI 상반신 추적 0.7.5")
manifest.write_text(m,encoding="utf-8")

assert "FacePath.Point p=path.step(ms,found,assist)" in s
assert "bodyClothing.select(anchorMs,picked,visibleBody)" in s
assert "BODY_HELD" in s
print("PASS: v0.7.5 Android preview + face/pose adapter integrated; independent package")

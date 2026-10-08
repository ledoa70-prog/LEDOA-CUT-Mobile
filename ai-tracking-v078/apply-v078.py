#!/usr/bin/env python3
"""v0.7.8: add independent, crop-based second face detector for hard 4-7s frames."""
from pathlib import Path
import os
p=Path(os.environ["PROJECT"])
java=p/"app/src/main/java/kr/ledoa/cut/aitest/MainActivity.java"
gradle=p/"app/build.gradle"
manifest=p/"app/src/main/AndroidManifest.xml"
s=java.read_text(encoding="utf-8")
def change(old,new):
    global s
    matches=s.count(old)
    if matches!=1:
        raise RuntimeError("v078 source layout changed: %s occurrences of %s"%(matches,old[:110]))
    s=s.replace(old,new)

change("AI 얼굴+옷 복합 추적 TEST 0.7.7","AI 얼굴 ROI 복합 추적 TEST 0.7.8")
change("LEDOA_FACE_BODY_TRACK_v0.7.7.json","LEDOA_FACE_ROI_TRACK_v0.7.8.json")
change("private FaceDetector detector;","""private FaceDetector detector;
    private FaceDetector roiDetector;
    private int roiAttempts=0,roiAccepted=0,roiDiscarded=0,roiAmbiguous=0;""")
change("detector=FaceDetection.getClient(opts);",
"""detector=FaceDetection.getClient(opts);
        // Separate instance: crop-scale detection must not pollute the full-frame
        // ML Kit tracker's state or confuse its transient tracking IDs.
        FaceDetectorOptions rescueOptions=new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(.08f).build();
        roiDetector=FaceDetection.getClient(rescueOptions);""")
change("durationMs=ms;currentMs=0;anchorMs=-1;selected=null;path.reset();bodyClothing.reset();visibleBody=null;",
"""durationMs=ms;currentMs=0;anchorMs=-1;selected=null;path.reset();
                bodyClothing.reset();visibleBody=null;
                roiAttempts=roiAccepted=roiDiscarded=roiAmbiguous=0;""")
change("    private float clamp(float n){return Math.max(0,Math.min(1,n));}",
"""    /**
     * Second detection pass for zero faces, implausible jumps or noisy detections.
     * The independent detector sees a cropped view of the selected head, or the
     * body model's head position. Only real detector faces with visual evidence
     * enter the tracker, never a predicted fake rectangle.
     */
    private List<FacePath.Box> detectWithRoi(Bitmap frame,long ms,
                  List<FacePath.Box> full,BodyClothing.Assist assist) {
        if(frame==null || roiDetector==null || cancel.get())return full;
        FacePath.Box last=path.lastReliableBox();
        if(!FaceRoi.needRescue(full,last,assist))return full;
        if(last==null)return full;
        if(ms-path.lastReliableTimestamp()>1100 &&
           (assist==null||assist.confidence<.6f))
            return full.size()>5?new ArrayList<>():full;
        roiAttempts++;
        List<FacePath.Box> faces=new ArrayList<>();
        for(FaceRoi.Region region:FaceRoi.regions(last,assist)){
            int[] pixels=region.pixels(frame.getWidth(),frame.getHeight());
            if(pixels==null)continue;
            int l=pixels[0],t=pixels[1],w=pixels[2]-l,h=pixels[3]-t;
            if(w*h>frame.getWidth()*frame.getHeight()*.83f)continue;
            Bitmap crop=null;
            try{
                crop=Bitmap.createBitmap(frame,l,t,w,h);
                List<Face> detected=Tasks.await(roiDetector.process(InputImage.fromBitmap(crop,0)));
                for(Face f:detected){
                    Rect local=f.getBoundingBox();
                    Rect global=new Rect(l+local.left,t+local.top,l+local.right,t+local.bottom);
                    int L=Math.max(0,global.left),T=Math.max(0,global.top);
                    int R=Math.min(frame.getWidth(),global.right);
                    int B=Math.min(frame.getHeight(),global.bottom);
                    if(R-L<20 || B-T<20)continue;
                    boolean nose=f.getLandmark(FaceLandmark.NOSE_BASE)!=null;
                    boolean eye=f.getLandmark(FaceLandmark.LEFT_EYE)!=null ||
                                f.getLandmark(FaceLandmark.RIGHT_EYE)!=null;
                    boolean mouth=f.getLandmark(FaceLandmark.MOUTH_BOTTOM)!=null;
                    boolean evidence=nose&&(eye||mouth);
                    boolean edge=L<4||T<4||R>=frame.getWidth()-4||B>=frame.getHeight()-4;
                    boolean partial=!evidence&&edge&&(eye||nose);
                    if(!evidence&&!partial)continue;
                    float[] visual=appearanceIn(frame,new Rect(L,T,R,B));
                    FacePath.Box box=new FacePath.Box(
                          L/(float)frame.getWidth(),T/(float)frame.getHeight(),
                          (R-L)/(float)frame.getWidth(),(B-T)/(float)frame.getHeight(),
                          -1,visual,evidence,partial);
                    if(FaceRoi.acceptCrop(box,last,assist))faces.add(box);
                }
            }catch(Exception e){
                android.util.Log.w("LEDOA-AI","Crop face search unavailable",e);
            }finally{if(crop!=null&&!crop.isRecycled())crop.recycle();}
        }
        // Multiple conflicting ROI matches must be reviewed, not guessed.
        FacePath.Box winner=null;
        float best=-10f,runner=-10f;
        for(FacePath.Box b:faces){
            float faceSimilarity=(last.appearance==null||b.appearance==null)?
                    .55f:FaceAppearance.score(last.appearance,b.appearance);
            float bodyBonus=assist!=null&&assist.near(b)?.07f:0f;
            float distance=last.dist(b);
            float score=faceSimilarity + bodyBonus - .13f*distance;
            if(score>best){runner=best;best=score;winner=b;}
            else if(score>runner)runner=score;
        }
        if(winner!=null && best>=.70f && (runner<0 || best-runner>=.065f)){
            roiAccepted++;
            return java.util.Collections.singletonList(winner);
        }
        if(faces.size()>1){roiAmbiguous++;roiDiscarded++;return new ArrayList<>();}
        if(full.size()>5){roiDiscarded++;return new ArrayList<>();}
        return full;
    }
    private float clamp(float n){return Math.max(0,Math.min(1,n));}""")
change("""                    }finally{if(frame!=null)frame.recycle();}
                    FacePath.Point p=path.step(ms,found,assist);total++;""",
"""                        found=detectWithRoi(frame,ms,found,assist);
                    }finally{if(frame!=null)frame.recycle();}
                    FacePath.Point p=path.step(ms,found,assist);total++;""")
change("""            obj.put("scaleCandidateRejected",path.scaleRejectedCount());""",
"""            obj.put("scaleCandidateRejected",path.scaleRejectedCount());
            obj.put("roiSearchAttempts",roiAttempts);
            obj.put("roiFaceCandidatesAccepted",roiAccepted);
            obj.put("roiAmbiguousFrames",roiAmbiguous);
            obj.put("roiNoiseFramesDiscarded",roiDiscarded);""")
change("""                    " · 얼굴 크기변화 재연결 "+path.scaleRecoveredCount()+
                    " · 미리보기 검토 필수 / MP4 저장 미지원.");""",
"""                    " · 얼굴 크기변화 재연결 "+path.scaleRecoveredCount()+
                    " · 보조 얼굴검출 "+roiAccepted+"/"+roiAttempts+
                    " · 반드시 영상 확인 / MP4 저장 미지원.");""")
change("        if(detector!=null)detector.close();",
"""        if(detector!=null)detector.close();
        if(roiDetector!=null)roiDetector.close();""")
java.write_text(s,encoding="utf-8")
g=gradle.read_text(encoding="utf-8")
assert "versionCode 707" in g and "applicationId 'kr.ledoa.cut.aiface077'" in g
g=g.replace("versionCode 707","versionCode 708")
g=g.replace("versionName '0.7.7-body-scale-recovery'","versionName '0.7.8-roi-face-rescue'")
g=g.replace("applicationId 'kr.ledoa.cut.aiface077'","applicationId 'kr.ledoa.cut.aiface078'")
gradle.write_text(g,encoding="utf-8")
m=manifest.read_text(encoding="utf-8")
assert "LEDOA AI 복합 추적 v0.7.7" in m
m.write_text(m.replace("LEDOA AI 복합 추적 v0.7.7",
"LEDOA AI ROI 얼굴 추적 v0.7.8"),encoding="utf-8")
assert "roiDetector" in s and "roiFaceCandidatesAccepted" in s
print("PASS: v0.7.8 isolated face-ROI second detector and JSON diagnostics applied")

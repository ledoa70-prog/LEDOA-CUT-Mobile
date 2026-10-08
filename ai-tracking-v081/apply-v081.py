#!/usr/bin/env python3
"""Stabilize v0.8.0 without touching the stable LEDOA editor."""
from pathlib import Path
import os
project=Path(os.environ["PROJECT"])
act=project/"app/src/main/java/kr/ledoa/cut/aitest/MainActivity.java"
gradle=project/"app/build.gradle"
manifest=project/"app/src/main/AndroidManifest.xml"
s=act.read_text(encoding="utf-8")
def change(old,new):
    global s
    n=s.count(old)
    assert n==1, f"v081 expected exactly one source match (got {n}): {old[:130]}"
    s=s.replace(old,new)

change("AI 얼굴+움직임 추적 TEST 0.8.0","AI 안정화+진단 TEST 0.8.1")
change("LEDOA_FACE_FLOW_TRACK_v0.8.0.json","LEDOA_FACE_FLOW_TRACK_v0.8.1.json")
change("private final OpticalBridge opticalBridge=new OpticalBridge();",
"""private final OpticalBridge opticalBridge=new OpticalBridge();
    private CrashDiagnostics diagnostics;
    private Button diagnosticBtn;
    private static final int SAVE_DIAGNOSTIC=1003;""")
change("""        super.onCreate(b);
        getWindow().setStatusBarColor""",
"""        super.onCreate(b);
        diagnostics=new CrashDiagnostics(getApplicationContext());
        boolean interrupted=diagnostics.previousWasInterrupted();
        diagnostics.install();
        getWindow().setStatusBarColor""")
change("""        renderUi();
    }
    private TextView text(""",
"""        renderUi();
        if(interrupted)status.setText("이전 얼굴 추적이 비정상 종료됐을 수 있습니다. '진단 기록 저장'을 눌러 보내주세요.");
    }
    private TextView text(""")
change("""        root.addView(saveBtn,new LinearLayout.LayoutParams(-1,dp(45)));
        status=text(""",
"""        root.addView(saveBtn,new LinearLayout.LayoutParams(-1,dp(45)));
        diagnosticBtn=btn("진단 기록 저장 (앱 종료 후에도 가능)");
        root.addView(diagnosticBtn,new LinearLayout.LayoutParams(-1,dp(45)));
        status=text(""")
change("""        saveBtn.setOnClickListener(v->saveTrack());
        playBtn.setOnClickListener(v->togglePlayback());""",
"""        saveBtn.setOnClickListener(v->saveTrack());
        diagnosticBtn.setOnClickListener(v->saveDiagnostic());
        playBtn.setOnClickListener(v->togglePlayback());""")
change("""        saveBtn.setEnabled(!analysing && !playing && !path.points().isEmpty());""",
"""        saveBtn.setEnabled(!analysing && !playing && !path.points().isEmpty());
        if(diagnosticBtn!=null)diagnosticBtn.setEnabled(!analysing && !playing);""")
change("""        if(request==SAVE_TRACK){writeTrack(data.getData());return;}""",
"""        if(request==SAVE_TRACK){writeTrack(data.getData());return;}
        if(request==SAVE_DIAGNOSTIC){writeDiagnostic(data.getData());return;}""")
change("""    private void saveTrack(){""",
"""    private void saveDiagnostic(){
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/plain");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE,"LEDOA_FACE_CRASH_v0.8.1.txt");
        startActivityForResult(intent,SAVE_DIAGNOSTIC);
    }
    private void writeDiagnostic(Uri uri){
        if(uri==null)return;
        try(OutputStream output=getContentResolver().openOutputStream(uri)){
            if(output==null)throw new java.io.IOException("파일을 열 수 없습니다.");
            output.write(diagnostics.export().getBytes(StandardCharsets.UTF_8));
            status.setText("진단 기록 저장 완료 · 파일을 채팅에 첨부해 주세요.");
        }catch(Exception err){
            status.setText("진단 기록 저장 실패: "+err.getMessage());
        }
    }
    private void saveTrack(){""")
change("""        worker.execute(()->{
            try{
                path.anchor(start,anchor);
                opticalBridge.reset();""",
"""        worker.execute(()->{
            diagnostics.begin(start);
            try{
                path.anchor(start,anchor);
                opticalBridge.reset();""")
change("""                for(long ms=start+STEP_MS;ms<=end;ms+=STEP_MS){
                    if(cancel.get())break;
                    Bitmap frame=bitmapAt(ms);""",
"""                for(long ms=start+STEP_MS;ms<=end;ms+=STEP_MS){
                    if(cancel.get())break;
                    diagnostics.progress(ms,"VIDEO_FRAME_DECODE");
                    Bitmap frame=bitmapAt(ms);""")
change("""                        found=detect(frame);
                        // Pose inference is relatively expensive.""",
"""                        diagnostics.progress(ms,"FACE_MODEL");
                        found=detect(frame);
                        // Pose inference is relatively expensive.""")
change("""                            if(((ms-start)/STEP_MS)%2==0 || found.size()!=1){""",
"""                            // Fixed 300ms cadence even on face misses: previous
                            // behavior ran the heavier pose model every 100ms.
                            if(((ms-start)/STEP_MS)%3==0){""")
change("""                                BodyClothing.Observation ob=null;
                                try{ob=PoseBodyAdapter.detect(poseDetector,frame);}""",
"""                                BodyClothing.Observation ob=null;
                                diagnostics.progress(ms,"BODY_POSE_MODEL");
                                try{ob=PoseBodyAdapter.detect(poseDetector,frame);}""")
change("""                        found=detectWithRoi(frame,ms,found,assist);""",
"""                        diagnostics.progress(ms,"OPTIONAL_ROI_FACE_MODEL");
                        found=detectWithRoi(frame,ms,found,assist);""")
change("""                        if(frame!=null)gray=MotionFrames.grayscale(frame);""",
"""                        if(frame!=null){
                            diagnostics.progress(ms,"GRAYSCALE_FOR_FLOW");
                            gray=MotionFrames.grayscale(frame);
                        }""")
change("""                        }else if(found.isEmpty()){
                            OpticalBridge.Estimate motion=opticalBridge.advance(
                                   ms,gray.pixels,gray.w,gray.h,assist);
                            if(motion!=null)
                                p=path.putMotionEstimate(ms,motion.box,motion.confidence);
                        }else{
                            // Competing face detections: never force an optical jump.
                            opticalBridge.invalidate();
                        }""",
"""                        }else if(found.isEmpty() ||
                                 (found.size()<=2 && "MOTION_OR_SCALE_GATE".equals(p.reason))){
                            // A rejected nonempty detection must not wipe the
                            // last reliable motion state before the blackout.
                            // Only blank frames may receive a provisional mask.
                            diagnostics.progress(ms,"OPTICAL_MOTION");
                            OpticalBridge.Estimate motion=opticalBridge.advance(
                                   ms,gray.pixels,gray.w,gray.h,assist);
                            if(motion!=null && found.isEmpty())
                                p=path.putMotionEstimate(ms,motion.box,motion.confidence);
                        }else{
                            // Multiple/ambiguous faces: never jump to a bystander.
                            opticalBridge.invalidate();
                        }""")
change("""                final int problems=failed,bodyOnlyFrames=bodyOnly;
                final boolean stopped=cancel.get();""",
"""                final int problems=failed,bodyOnlyFrames=bodyOnly;
                final boolean stopped=cancel.get();
                diagnostics.finished(Math.min(end,start+total*STEP_MS),!stopped);""")
change("""            }catch(OutOfMemoryError oom){
                // Retain partial track in memory so user can export diagnostic JSON.""",
"""            }catch(OutOfMemoryError oom){
                diagnostics.recordRecoverable(oom,"ANALYSIS_MEMORY");
                // Retain partial track in memory so user can export diagnostic JSON.""")
change("""            }catch(Exception e){
                android.util.Log.e("LEDOA-AI","Analysis error",e);""",
"""            }catch(Exception e){
                diagnostics.recordRecoverable(e,"ANALYSIS_EXCEPTION");
                android.util.Log.e("LEDOA-AI","Analysis error",e);""")
change("""            obj.put("flowRequiresReview",true);""",
"""            obj.put("flowRequiresReview",true);
            obj.put("crashDiagnosticAvailable",true);
            obj.put("analysisStabilityVersion","0.8.1");""")
change("""if(ms-roiLastScanMs<300){roiThrottled++;return full;}""",
"""if(ms-roiLastScanMs<600){roiThrottled++;return full;}""")
act.write_text(s,encoding="utf-8")
g=gradle.read_text(encoding="utf-8")
assert "versionCode 800" in g and "applicationId 'kr.ledoa.cut.aiface080'" in g
g=g.replace("versionCode 800","versionCode 801")
g=g.replace("versionName '0.8.0-optical-bridge'","versionName '0.8.1-crash-diagnostics'")
g=g.replace("applicationId 'kr.ledoa.cut.aiface080'","applicationId 'kr.ledoa.cut.aiface081'")
gradle.write_text(g,encoding="utf-8")
m=manifest.read_text(encoding="utf-8")
assert "LEDOA AI 움직임 추적 v0.8.0" in m
manifest.write_text(m.replace("LEDOA AI 움직임 추적 v0.8.0",
                              "LEDOA AI 안정화 진단 v0.8.1"),encoding="utf-8")
assert "diagnostics.recordRecoverable" in s and "diagnostics.progress" in s
print("PASS: v0.8.1 crash diagnostics, AI throttling and optical continuity integrated")

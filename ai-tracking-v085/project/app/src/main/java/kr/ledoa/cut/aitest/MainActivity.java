package kr.ledoa.cut.aitest;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Independent feasibility test only. DOES NOT change LEDOA CUT, DOES NOT export MP4.
 * It intentionally refuses to silently mask lost detections or claim an unverified video is safe.
 */
public final class MainActivity extends Activity {
    private static final int PICK_VIDEO=1001, SAVE_TRACK=1002;
    private static final long STEP_MS=100; // 10 fps feasibility analysis, not release-grade 30 fps.
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel=new AtomicBoolean(false);
    private final AtomicInteger generation=new AtomicInteger(0);
    private final FacePath path=new FacePath(true);
    private FaceDetector detector;
    private FaceDetector roiDetector;
    private int roiAttempts=0,roiAccepted=0,roiDiscarded=0,roiAmbiguous=0;
    private int roiThrottled=0,roiMemoryFailures=0;
    private boolean roiDisabled=false;
    private long roiLastScanMs=-10000;
    private final OpticalBridge opticalBridge=new OpticalBridge();
    private final LazyFlowBridge lazyFlow=new LazyFlowBridge(opticalBridge);
    private int poseInferenceAttempts=0,poseAdaptiveExtra=0;
    private final ContinuityBridge continuity=new ContinuityBridge(path,lazyFlow);
    private long analysisElapsedMs=0,decodeMs=0,faceMs=0,poseMs=0,motionMs=0,analysedEndMs=0;
    private int faceInferenceAttempts=0,decodedFrames=0,sampledFrames=0;
    private String decoderMode="NOT_STARTED";
    private BodyClothing.Observation selectedBody;
    private static final boolean ROI_DISABLED_FOR_STABILITY=true;
    private CrashDiagnostics diagnostics;
    private Button diagnosticBtn;
    private static final int SAVE_DIAGNOSTIC=1003;
    private PoseDetector poseDetector;
    private final BodyClothing bodyClothing=new BodyClothing();
    private BodyClothing.Observation visibleBody;
    private MediaMetadataRetriever retriever;
    private Uri videoUri;
    private long durationMs=0,currentMs=0,anchorMs=-1;
    private long lastRunStartMs=-1,lastRunEndMs=-1;
    private volatile boolean analysing=false;
    private FacePath.Box selected;
    private List<FacePath.Box> visibleFaces=new ArrayList<>();
    private FrameView frameView;
    private TextView status,clock,disclaimer;
    private SeekBar seek;
    private Button openBtn,trackBtn,cancelBtn,saveBtn,playBtn,manualBoxBtn;
    private boolean manualBoxMode=false,manualCorrectionPending=false;
    private boolean updatingSeek=false;
    // Standalone preview: sampled video frames with the already-calculated mosaic path.
    // This is intentionally silent and can skip frames on slow devices; not a 30fps editor playback engine.
    private final Handler playbackHandler=new Handler(Looper.getMainLooper());
    private volatile boolean playing=false;
    private long playBaseMs=0, playBaseUptime=0;
    private static final long PLAYBACK_FRAME_GAP_MS=100;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        diagnostics=new CrashDiagnostics(getApplicationContext());
        boolean interrupted=diagnostics.previousWasInterrupted();
        diagnostics.install();
        getWindow().setStatusBarColor(Color.rgb(13,19,27));
        getWindow().setNavigationBarColor(Color.rgb(13,19,27));
        FaceDetectorOptions opts=new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .enableTracking().setMinFaceSize(.045f).build();
        detector=FaceDetection.getClient(opts);
        // Separate instance: crop-scale detection must not pollute the full-frame
        // ML Kit tracker's state or confuse its transient tracking IDs.
        FaceDetectorOptions rescueOptions=new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(.08f).build();
        // No separate ROI ML detector in stability mode.
        // It rescued zero frames in v0.8.2 but consumed native ML memory.
        roiDetector=null;
        PoseDetectorOptions poseOptions=new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build();
        poseDetector=PoseDetection.getClient(poseOptions);
        renderUi();
        if(interrupted)status.setText("이전 얼굴 추적이 중단됐습니다. '진단 기록 저장'으로 v0.8.7 오류 정보를 보내주세요.");
    }
    private TextView text(String message,int size,int color){
        TextView t=new TextView(this);t.setText(message);t.setTextSize(size);t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(8),dp(5),dp(8),dp(5));return t;
    }
    private int dp(float v){return (int)(getResources().getDisplayMetrics().density*v+.5f);}
    private Button btn(String label){Button x=new Button(this);x.setText(label);x.setAllCaps(false);x.setTextSize(13);return x;}
    private void renderUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13,19,27));root.setPadding(dp(10),dp(6),dp(10),dp(6));
        root.addView(text("LEDOA CUT  |  AI 인물 연속추적 복구 TEST 0.8.9",18,Color.WHITE));
        root.addView(text("정식 앱과 분리 · 얼굴+상반신 옷 보조 추적 · 모자이크 85%",12,Color.rgb(188,199,215)));
        frameView=new FrameView();root.addView(frameView,new LinearLayout.LayoutParams(-1,0,1));frameView.setMinimumHeight(dp(150));
        clock=text("00:00.0 / 00:00.0",12,Color.WHITE);root.addView(clock);
        seek=new SeekBar(this);seek.setMax(1000);root.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int value,boolean fromUser){if(fromUser && durationMs>0) clock.setText(fmt(durationMs*value/1000)+" / "+fmt(durationMs));}
            public void onStartTrackingTouch(SeekBar s){ stopPlayback(false); }
            public void onStopTrackingTouch(SeekBar s){if(!analysing && durationMs>0){long target=durationMs*s.getProgress()/1000; if(target!=anchorMs){selected=null;anchorMs=-1;} preview(target);}}
        });
        LinearLayout buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);
        openBtn=btn("영상 불러오기");trackBtn=btn("선택 얼굴 추적");cancelBtn=btn("중단");saveBtn=btn("추적 데이터 저장");
        for(Button button:new Button[]{openBtn,trackBtn,cancelBtn})buttons.addView(button,new LinearLayout.LayoutParams(0,dp(52),1));
        root.addView(buttons);
        LinearLayout playbackRow=new LinearLayout(this);playbackRow.setOrientation(LinearLayout.HORIZONTAL);
        playBtn=btn("▶ 모자이크 재생");
        playbackRow.addView(playBtn,new LinearLayout.LayoutParams(0,dp(47),1));
        manualBoxBtn=btn("수동 얼굴박스 지정");
        playbackRow.addView(manualBoxBtn,new LinearLayout.LayoutParams(0,dp(47),1));
        root.addView(playbackRow);
        root.addView(saveBtn,new LinearLayout.LayoutParams(-1,dp(45)));
        diagnosticBtn=btn("진단 기록 저장 (앱 종료 후에도 가능)");
        root.addView(diagnosticBtn,new LinearLayout.LayoutParams(-1,dp(45)));
        status=text("영상 불러오기 → 얼굴이 보이는 곳에서 일시정지 → 얼굴 터치 → 추적",13,Color.rgb(244,204,112));
        root.addView(status);
        disclaimer=text("옷·어깨는 같은 사람 재추적에만 사용합니다. 잠깐 놓친 구간은 움직임으로 보완합니다. 노란 표시는 검토 필요. 무음 미리보기·MP4 저장 없음.",11,Color.rgb(181,189,200));root.addView(disclaimer);
        setContentView(root);
        openBtn.setOnClickListener(v->pickVideo());
        trackBtn.setOnClickListener(v->startAnalysis());
        cancelBtn.setOnClickListener(v->{cancel.set(true);status.setText("중단 요청 중…");});
        saveBtn.setOnClickListener(v->saveTrack());
        diagnosticBtn.setOnClickListener(v->saveDiagnostic());
        playBtn.setOnClickListener(v->togglePlayback());
        manualBoxBtn.setOnClickListener(v->{
            if(analysing||playing||frameView.frame==null)return;
            manualBoxMode=!manualBoxMode;
            manualBoxBtn.setText(manualBoxMode?"수동 지정 취소":"수동 얼굴박스 지정");
            status.setText(manualBoxMode
                ?"영상에서 가릴 얼굴을 손가락으로 대각선 드래그해 박스를 만드세요."
                :"수동 얼굴 지정 취소");
            frameView.invalidate();
        });
        controls();
    }
    private void controls(){
        openBtn.setEnabled(!analysing && !playing);
        trackBtn.setEnabled(!analysing && !playing && selected!=null);
        cancelBtn.setEnabled(analysing);
        saveBtn.setEnabled(!analysing && !playing && !path.points().isEmpty());
        if(diagnosticBtn!=null)diagnosticBtn.setEnabled(!analysing && !playing);
        seek.setEnabled(!analysing && durationMs>0);
        playBtn.setEnabled(!analysing && retriever!=null && durationMs>0);
        if(manualBoxBtn!=null)manualBoxBtn.setEnabled(!analysing&&!playing&&frameView.frame!=null);
        playBtn.setText(playing?"Ⅱ 일시정지":"▶ 모자이크 재생");
    }
    private String fmt(long m){return String.format(Locale.KOREA,"%02d:%02d.%01d",m/60000,(m/1000)%60,(m/100)%10);}
    private void pickVideo(){
        stopPlayback(false);
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("video/*");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,PICK_VIDEO);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(request==SAVE_TRACK){writeTrack(data.getData());return;}
        if(request==SAVE_DIAGNOSTIC){writeDiagnostic(data.getData());return;}
        if(request!=PICK_VIDEO)return;
        Uri uri=data.getData();if(uri==null)return;
        stopPlayback(false);
        generation.incrementAndGet();cancel.set(true);
        status.setText("영상 정보를 읽는 중…");
        worker.execute(()->{
            try{
                MediaMetadataRetriever r=new MediaMetadataRetriever();r.setDataSource(this,uri);
                String duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long ms=Long.parseLong(duration==null?"0":duration);
                if(ms<=0)throw new IllegalArgumentException("영상 길이를 읽지 못했습니다.");
                MediaMetadataRetriever old=retriever;retriever=r;
                if(old!=null){try{old.release();}catch(java.io.IOException e){android.util.Log.w("LEDOA-AI","Previous video release failed",e);}}videoUri=uri;
                durationMs=ms;currentMs=0;anchorMs=-1;selected=null;path.reset();lastRunStartMs=lastRunEndMs=-1;manualBoxMode=false;manualCorrectionPending=false;
                bodyClothing.reset();visibleBody=null;
                roiAttempts=roiAccepted=roiDiscarded=roiAmbiguous=0;
                roiThrottled=roiMemoryFailures=0;roiDisabled=false;roiLastScanMs=-10000;
                opticalBridge.reset();
                runOnUiThread(()->{status.setText("원하는 얼굴을 터치해 주세요.");controls();});
                preview(0);
            }catch(Exception e){runOnUiThread(()->status.setText("영상 열기 실패: "+e.getMessage()));}
        });
    }
    /** Preview animation without repeated ML Kit detection: decoded snapshots + saved face path.
     * Frames are scheduled sequentially, never queued faster than decoding. Playback clock is
     * elapsed realtime; heavy decoding simply skips image frames instead of accumulating lag.
     */
    private void togglePlayback(){
        if(playing){stopPlayback(true);return;}
        if(analysing||retriever==null||durationMs<=0)return;
        if(currentMs>=durationMs-100)currentMs=0;
        playing=true;
        playBaseMs=currentMs;
        playBaseUptime=SystemClock.uptimeMillis();
        int token=generation.incrementAndGet();
        controls();
        status.setText("모자이크 검토 재생 중 · 무음 프레임 재생 · Ⅱ 버튼으로 일시정지");
        decodePlaybackFrame(token);
    }
    private void stopPlayback(boolean reloadPausedFrame){
        if(!playing)return;
        playing=false;
        generation.incrementAndGet();
        playbackHandler.removeCallbacksAndMessages(null);
        controls();
        if(reloadPausedFrame){
            status.setText("일시정지 · 얼굴을 터치해 보정하거나 재생을 계속하세요.");
            preview(currentMs);
        }
    }
    private void decodePlaybackFrame(final int token){
        if(!playing||token!=generation.get())return;
        final long ms=Math.min(durationMs,playBaseMs+Math.max(0,SystemClock.uptimeMillis()-playBaseUptime));
        worker.execute(()->{
            if(!playing||token!=generation.get())return;
            Bitmap bitmap=null;
            try{
                bitmap=bitmapAt(ms);
                if(bitmap==null)throw new IllegalStateException("해당 프레임을 읽을 수 없습니다.");
                final Bitmap ready=bitmap;
                runOnUiThread(()->{
                    if(!playing||token!=generation.get()){
                        if(!ready.isRecycled())ready.recycle();return;
                    }
                    currentMs=ms;
                    visibleFaces=Collections.emptyList();
                    frameView.setFrame(ready,visibleFaces,path.interpolated(ms),path.nearest(ms));
                    updatingSeek=true;
                    seek.setProgress((int)(1000.0*ms/durationMs));
                    updatingSeek=false;
                    clock.setText(fmt(ms)+" / "+fmt(durationMs));
                    if(ms>=durationMs){
                        stopPlayback(false);
                        status.setText("끝까지 검토했습니다. 모자이크 누락 구간은 일시정지·재선택하여 확인하세요.");
                    }else{
                        playbackHandler.postDelayed(()->decodePlaybackFrame(token),PLAYBACK_FRAME_GAP_MS);
                    }
                });
            }catch(Exception e){
                if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();
                final String detail=e.getMessage();
                runOnUiThread(()->{
                    if(token!=generation.get())return;
                    stopPlayback(false);
                    status.setText("프레임 재생 오류: "+detail);
                });
            }
        });
    }
    private Bitmap bitmapAt(long ms){
        if(retriever==null)return null;
        // This returns a displayed-rotation bitmap on supported Android implementations.
        // All face coordinates are normalized against the ACTUAL bitmap dimensions.
        // Bounded-size frame decode lowers bitmap allocation pressure on Fold phones.
        return retriever.getScaledFrameAtTime(Math.max(0,ms)*1000,MediaMetadataRetriever.OPTION_CLOSEST,640,640);
    }
    private List<FacePath.Box> detect(Bitmap frame)throws Exception {
        List<FacePath.Box> boxes=new ArrayList<>();if(frame==null)return boxes;
        List<Face> faces=Tasks.await(detector.process(InputImage.fromBitmap(frame,0)));
        for(Face face:faces){Rect r=face.getBoundingBox();
            float x=clamp(r.left/(float)frame.getWidth()),y=clamp(r.top/(float)frame.getHeight());
            float right=clamp(r.right/(float)frame.getWidth()),bottom=clamp(r.bottom/(float)frame.getHeight());
            // A face-detection ID may change between decoded video frames. Compute visual
            // signature locally; it is supporting evidence, never a biometric identity.
            if(right>x && bottom>y && (right-x)*(bottom-y)<.82f){
                float[] visual=appearanceIn(frame,r);
      boolean nose=face.getLandmark(FaceLandmark.NOSE_BASE)!=null;
      boolean eye=face.getLandmark(FaceLandmark.LEFT_EYE)!=null
              ||face.getLandmark(FaceLandmark.RIGHT_EYE)!=null;
      boolean mouth=face.getLandmark(FaceLandmark.MOUTH_BOTTOM)!=null;
      boolean faceEvidence=nose && (eye || mouth);
                // A partial detected face at a screen edge might have fewer landmarks.
                // This signal is never sufficient alone: the tracker requires repeated evidence.
                boolean onEdge=(x<.035f||right>.965f||y<.035f);
                boolean partialEdge=!faceEvidence && onEdge && (nose || eye);
      if(!faceEvidence && !partialEdge)continue;
                boxes.add(new FacePath.Box(x,y,right-x,bottom-y,
                        face.getTrackingId()==null?-1:face.getTrackingId(),visual,faceEvidence,partialEdge));
            }
        }return boxes;
    }
    private float[] appearanceIn(Bitmap image,Rect face){
        if(image==null)return null;
        int l=Math.max(0,Math.min(image.getWidth()-1,face.left));
        int t=Math.max(0,Math.min(image.getHeight()-1,face.top));
        int r=Math.max(l+1,Math.min(image.getWidth(),face.right));
        int b=Math.max(t+1,Math.min(image.getHeight(),face.bottom));
        if(r-l<12 || b-t<12)return null;
        // Exclude bounding-box edges to reduce background and hat-brim contamination.
        int marginX=Math.max(1,(r-l)/8),marginY=Math.max(1,(b-t)/8);
        l+=marginX;t+=marginY;r-=marginX;b-=marginY;
        if(r-l<8||b-t<8)return null;
        Bitmap crop=null,tiny=null;
        try{
            crop=Bitmap.createBitmap(image,l,t,r-l,b-t);
            tiny=Bitmap.createScaledBitmap(crop,FaceAppearance.SIZE,FaceAppearance.SIZE,true);
            int[] colors=new int[FaceAppearance.SIZE*FaceAppearance.SIZE];
            tiny.getPixels(colors,0,FaceAppearance.SIZE,0,0,FaceAppearance.SIZE,FaceAppearance.SIZE);
            return FaceAppearance.describe(colors,FaceAppearance.SIZE,FaceAppearance.SIZE);
        }catch(RuntimeException e){return null;}
        finally{
            if(tiny!=null&&tiny!=crop&&!tiny.isRecycled())tiny.recycle();
            if(crop!=null&&!crop.isRecycled())crop.recycle();
        }
    }
    /**
     * Second detection pass for zero faces, implausible jumps or noisy detections.
     * The independent detector sees a cropped view of the selected head, or the
     * body model's head position. Only real detector faces with visual evidence
     * enter the tracker, never a predicted fake rectangle.
     */
    private List<FacePath.Box> detectWithRoi(Bitmap frame,long ms,
                  List<FacePath.Box> full,BodyClothing.Assist assist) {
        if(frame==null || roiDetector==null || cancel.get() || roiDisabled)return full;
        FacePath.Box last=path.lastReliableBox();
        if(!FaceRoi.needRescue(full,last,assist))return full;
        if(last==null)return full;
        if(ms-path.lastReliableTimestamp()>1100 &&
           (assist==null||assist.confidence<.6f))
            return full.size()>5?new ArrayList<>():full;
        // 300ms cooldown stops 100ms video samples from repeatedly allocating
        // and invoking a SECOND detector while ML Kit/GC is still catching up.
        if(ms-roiLastScanMs<600){roiThrottled++;return full;}
        roiLastScanMs=ms;
        roiAttempts++;
        List<FacePath.Box> faces=new ArrayList<>();
        for(FaceRoi.Region region:FaceRoi.regions(last,assist)){
            int[] pixels=region.pixels(frame.getWidth(),frame.getHeight());
            if(pixels==null)continue;
            int l=pixels[0],t=pixels[1],w=pixels[2]-l,h=pixels[3]-t;
            if(w*h>frame.getWidth()*frame.getHeight()*.83f)continue;
            Bitmap crop=null,downscaled=null;
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
                                         l+Math.round(local.right*sx),t+Math.round(local.bottom*sy));
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
            }catch(OutOfMemoryError oom){
                // Optional ROI must NEVER take down the working face tracker.
                roiMemoryFailures++;roiDisabled=true;
                android.util.Log.e("LEDOA-AI","ROI disabled for this video: memory exhausted",oom);
                faces.clear();break;
            }catch(Exception e){
                android.util.Log.w("LEDOA-AI","Crop face search unavailable",e);
            }finally{
                if(downscaled!=null&&downscaled!=crop&&!downscaled.isRecycled())downscaled.recycle();
                if(crop!=null&&!crop.isRecycled())crop.recycle();
            }
        }
        // De-duplicate the SAME face seen by overlapping head and torso crops.
        List<FacePath.Box> unique=new ArrayList<>();
        for(FacePath.Box b:faces){
            boolean seen=false;
            for(FacePath.Box other:unique){
                float dx=Math.abs(b.cx()-other.cx()),dy=Math.abs(b.cy()-other.cy());
                if(dx<Math.min(b.w,other.w)*.16f &&
                   dy<Math.min(b.h,other.h)*.16f){seen=true;break;}
            }
            if(!seen)unique.add(b);
        }
        // Multiple conflicting ROI matches must be reviewed, not guessed.
        FacePath.Box winner=null;
        float best=-10f,runner=-10f;
        for(FacePath.Box b:unique){
            float faceSimilarity=(last.appearance==null||b.appearance==null)?
                    .55f:FaceAppearance.score(last.appearance,b.appearance);
            float bodyBonus=assist!=null&&assist.near(b)?.07f:0f;
            float distance=last.dist(b);
            float score=faceSimilarity + bodyBonus - .13f*distance;
            if(score>best){runner=best;best=score;winner=b;}
            else if(score>runner)runner=score;
        }
        // ROI may rescue only when the primary detector produced NO candidates.
        // Never replace a normal full-frame detection with a small nearby stranger.
        // Also demand high visual similarity rather than clothes alone.
        if(full.isEmpty() && winner!=null && best>=.85f &&
           (runner<0 || best-runner>=.10f)){
            roiAccepted++;
            return java.util.Collections.singletonList(winner);
        }
        if(unique.size()>1){roiAmbiguous++;roiDiscarded++;}
        if(full.size()>5){roiDiscarded++;return new ArrayList<>();}
        return full;
    }
    private float clamp(float n){return Math.max(0,Math.min(1,n));}
    private void preview(long ms){preview(ms,null);}
    private void preview(long ms,String completion){
        if(retriever==null)return;
        int token=generation.incrementAndGet();currentMs=Math.min(durationMs,Math.max(0,ms));
        worker.execute(()->{
            if(analysing||token!=generation.get())return;
            Bitmap frame=null;
            try{
                frame=bitmapAt(currentMs);
                List<FacePath.Box> boxes=detect(frame);
                BodyClothing.Observation pose=null;
                try{pose=PoseBodyAdapter.detect(poseDetector,frame);}
                catch(Exception e){android.util.Log.w("LEDOA-AI","Pose unavailable for selection",e);}
                final BodyClothing.Observation poseForUi=pose;
                if(token!=generation.get()){if(frame!=null)frame.recycle();return;}
                Bitmap result=frame;long shown=currentMs;
                runOnUiThread(()->{
                    if(token!=generation.get()){if(result!=null)result.recycle();return;}
                    visibleFaces=boxes;visibleBody=poseForUi;
                    frameView.setFrame(result,boxes,path.interpolated(shown),path.nearest(shown));
                    updatingSeek=true;seek.setProgress((int)(1000.0*shown/durationMs));updatingSeek=false;
                    clock.setText(fmt(shown)+" / "+fmt(durationMs));
                    status.setText(completion!=null?completion:(selected==null?"터치해서 가릴 얼굴을 선택하세요.":"노란 얼굴 상자 선택됨 · 추적 또는 해당 시점에서 재선택 가능"));
                    controls();
                });
            }catch(Exception e){if(frame!=null)frame.recycle();runOnUiThread(()->status.setText("프레임 분석 실패: "+e.getMessage()));}
        });
    }
    private void onFaceTap(float u,float v){
        if(playing){status.setText("먼저 Ⅱ 일시정지를 누른 뒤 얼굴을 선택해 주세요.");return;}
        if(analysing||visibleFaces==null)return;
        FacePath.Box picked=null;
        for(FacePath.Box b:visibleFaces)if(b.expanded(.08f).contains(u,v)){
            if(picked==null||b.area()<picked.area())picked=b;
        }
        if(picked==null){status.setText("얼굴 영역 밖입니다. 얼굴 안쪽을 다시 터치하세요.");return;}
        selected=picked;anchorMs=currentMs;selectedBody=visibleBody;
        // A tap inside a face box during review is also a legitimate manual
        // re-identification. Preserve other manual corrections when revising it.
        boolean correcting=path.coveredThrough()>=currentMs && currentMs>path.firstTimestamp();
        manualCorrectionPending=correcting;
        if(correcting)path.anchorManual(anchorMs,picked);
        else path.anchor(anchorMs,picked);
        final boolean upperBodyLinked=bodyClothing.select(anchorMs,picked,visibleBody);
        frameView.setFrame(frameView.frame,visibleFaces,path.interpolated(currentMs),path.nearest(currentMs));
        status.setText(upperBodyLinked
            ?"얼굴+상의 등록 완료 · 얼굴 추적 버튼을 눌러주세요."
            :"얼굴 선택 완료 · 상의가 명확히 보이지 않아 얼굴 단독 추적으로 진행합니다.");
        controls();
    }
    /**
     * Manual region selection works when ML Kit cannot detect a side face.
     * A real user drag is required: no identity is inferred from clothing or
     * from the nearest background person. Earlier video frames stay intact.
     */
    private void onManualFaceRegion(float x1,float y1,float x2,float y2){
        if(analysing||playing||frameView.frame==null)return;
        float l=Math.max(0,Math.min(x1,x2)),t=Math.max(0,Math.min(y1,y2));
        float r=Math.min(1,Math.max(x1,x2)),b=Math.min(1,Math.max(y1,y2));
        if(r-l<.065f||b-t<.045f||r-l>.90f||b-t>.90f){
            status.setText("얼굴 부분만 사각형으로 지정해 주세요. 너무 작거나 큰 박스는 사용할 수 없습니다.");return;
        }
        Bitmap image=frameView.frame;
        Rect area=new Rect((int)(l*image.getWidth()),(int)(t*image.getHeight()),
                           Math.min(image.getWidth(),(int)Math.ceil(r*image.getWidth())),
                           Math.min(image.getHeight(),(int)Math.ceil(b*image.getHeight())));
        float[] appearance=appearanceIn(image,area);
        if(appearance==null){
            status.setText("선택한 얼굴에서 특징을 읽지 못했습니다. 조금 넓게 지정해 주세요.");return;
        }
        FacePath.Box box=new FacePath.Box(l,t,r-l,b-t,-1,appearance,true,false);
        selected=box;anchorMs=currentMs;selectedBody=visibleBody;
        path.anchorManual(anchorMs,box);
        manualCorrectionPending=true;
        manualBoxMode=false;manualBoxBtn.setText("수동 얼굴박스 지정");
        bodyClothing.select(anchorMs,box,visibleBody);
        frameView.setFrame(frameView.frame,visibleFaces,path.interpolated(currentMs),path.nearest(currentMs));
        status.setText("수동 얼굴 영역 지정 완료 · 앞 구간 보존 · '선택 얼굴 추적'을 누르면 여기서부터 다시 분석합니다.");
        controls();
    }
    private void startAnalysis(){
        if(selected==null||retriever==null||analysing)return;
        stopPlayback(false);
        final long start=anchorMs;
        final boolean partialCorrection=manualCorrectionPending;
        final Long nextManual=partialCorrection?path.nextManualAfter(start):null;
        final long end=nextManual==null?durationMs:Math.min(durationMs,nextManual);
        lastRunStartMs=start;lastRunEndMs=-1;
        final FacePath.Box anchor=selected;
        final BodyClothing.Observation initialBody=selectedBody;
        final boolean keepManualKeyframe=manualCorrectionPending;
        manualCorrectionPending=false;
        cancel.set(false);analysing=true;controls();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("선택 인물 연속 분석 중… 영상은 한 번만 읽고, 놓친 구간은 앞뒤로 확인합니다.");
        worker.execute(()->{
            final long began=SystemClock.elapsedRealtime();
            long processed=start;
            SequentialFrames reader=null;
            diagnostics.begin(start);
            analysisElapsedMs=decodeMs=faceMs=poseMs=motionMs=0;
            faceInferenceAttempts=poseInferenceAttempts=poseAdaptiveExtra=decodedFrames=sampledFrames=0;
            try{
                if(!keepManualKeyframe)path.anchor(start,anchor);
                continuity.reset();
                bodyClothing.select(start,anchor,initialBody);
                try{reader=new SequentialFrames(this,videoUri,start,cancel);decoderMode="SEQUENTIAL_MEDIACODEC";}
                catch(Exception unavailable){decoderMode="RETRIEVER_FALLBACK";}
                Bitmap initialFrame=bitmapAt(start);
                try{
                    MotionFrames.Gray seed=MotionFrames.grayscale(initialFrame);
                    if(seed!=null)continuity.process(start,seed.pixels,seed.w,seed.h,path.exact(start),null);
                }finally{if(initialFrame!=null&&!initialFrame.isRecycled())initialFrame.recycle();}
                long lastPoseMs=start,lastUi=0,lastKey=start;
                int total=0;
                for(long requested=start+50;requested<end;requested+=50){
                    if(cancel.get()||Thread.currentThread().isInterrupted())break;
                    boolean key=(requested-start)%STEP_MS==0||requested+50>=end;
                    if(reader==null&&!key)continue;
                    long time=SystemClock.elapsedRealtime();
                    diagnostics.progress(requested,"VIDEO_FRAME_DECODE");
                    Bitmap frame=null;long ms=requested;
                    if(reader!=null){
                        try{
                            SequentialFrames.Frame decoded=reader.at(requested);
                            if(decoded==null)break;
                            frame=decoded.bitmap;ms=decoded.ms;
                        }catch(Exception codecError){
                            decodedFrames+=reader.decodedFrames;
                            reader.close();reader=null;decoderMode="RETRIEVER_FALLBACK";
                            if(cancel.get())break;
                            frame=bitmapAt(requested);key=true;
                        }
                    }else frame=bitmapAt(requested);
                    decodeMs+=SystemClock.elapsedRealtime()-time;
                    if(frame==null)throw new java.io.IOException("영상 프레임을 읽지 못했습니다.");
                    // Stop before the next independently confirmed manual
                    // face keyframe. Otherwise a decoded frame may overwrite
                    // that verified anchor despite a requested segment bound.
                    if(ms>=end){frame.recycle();break;}
                    if(ms<=processed){frame.recycle();continue;}
                    if(ms-lastKey>=STEP_MS)key=true;
                    sampledFrames++;processed=ms;
                    try{
                        MotionFrames.Gray gray=MotionFrames.grayscale(frame);
                        if(!key){continuity.cache(ms,gray.pixels,gray.w,gray.h);continue;}
                        if(ms<=lastKey)continue;
                        diagnostics.progress(ms,"FACE_MODEL");time=SystemClock.elapsedRealtime();
                        List<FacePath.Box> found=detect(frame);faceInferenceAttempts++;
                        faceMs+=SystemClock.elapsedRealtime()-time;
                        BodyClothing.Assist assist=null;
                        if(bodyClothing.isEnabled()){
                            boolean trouble=!path.plausible(ms,found);
                            long interval=trouble?300:900;
                            if(ms-lastPoseMs>=interval){
                                time=SystemClock.elapsedRealtime();poseInferenceAttempts++;
                                if(trouble)poseAdaptiveExtra++;
                                diagnostics.progress(ms,"BODY_POSE_MODEL");
                                BodyClothing.Observation ob=null;
                                try{ob=PoseBodyAdapter.detect(poseDetector,frame);}
                                catch(Exception e){android.util.Log.w("LEDOA-AI","Pose sample unavailable",e);}
                                assist=bodyClothing.observe(ms,ob);
                                if(assist==null&&ob==null)assist=bodyClothing.recent(ms);
                                lastPoseMs=ms;poseMs+=SystemClock.elapsedRealtime()-time;
                            }else assist=bodyClothing.recent(ms);
                        }
                        FacePath.Point point=path.step(ms,found,assist);lastKey=ms;total++;
                        time=SystemClock.elapsedRealtime();
                        diagnostics.progress(ms,"BIDIRECTIONAL_HEAD_MOTION");
                        if(path.isIdentityLocked()){
                            // A motion estimate must not move a mask onto a bystander
                            // after the selected person's identity became uncertain.
                            continuity.reset();
                        }else continuity.process(ms,gray.pixels,gray.w,gray.h,point,assist);
                        motionMs+=SystemClock.elapsedRealtime()-time;
                        long now=SystemClock.elapsedRealtime();
                        if(now-lastUi>=400){
                            lastUi=now;final long position=ms,spent=now-began;
                            final int holes=path.uncoveredCount();
                            runOnUiThread(()->{if(!isDestroyed())status.setText("분석 "+fmt(position-start)+" / "+fmt(end-start)+
                                " · 경과 "+String.format(Locale.KOREA,"%.1f",spent/1000.)+"초 · 미확인 "+holes+
                    (path.isIdentityLocked()?" · 인물 재지정 필요":""));});
                        }
                    }finally{if(!frame.isRecycled())frame.recycle();}
                }
                boolean stopped=cancel.get()||Thread.currentThread().isInterrupted();
                if(reader!=null)decodedFrames+=reader.decodedFrames;
                analysisElapsedMs=SystemClock.elapsedRealtime()-began;
                long segmentEnd=Math.min(end,stopped?processed:lastKey+STEP_MS);
                lastRunEndMs=segmentEnd;
                // Preserve the original whole-video coverage when just one
                // manually delimited segment has been re-analysed.
                analysedEndMs=Math.max(segmentEnd,Math.max(path.latestTimestamp(),path.coveredThrough()));
                path.coverageEnd(analysedEndMs);
                int shortGapsReviewed=path.bridgeShortConfirmedGaps();
                int manualGapsReviewed=path.fillBetweenManualKeyframes();
                diagnostics.finished(segmentEnd,!stopped);
                final String summary=(stopped?"분석 중단":"분석 완료")+" · "+
                    String.format(Locale.KOREA,"%.1f",analysisElapsedMs/1000.)+"초 소요"+
                    " · 움직임 보완 "+path.flowEstimatedCount()+" · 가림 미확인 "+path.uncoveredCount()+
                    " · 재생으로 확인해 주세요.";
                runOnUiThread(()->{
                    if(isDestroyed())return;
                    analysing=false;getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    controls();preview(start,summary);
                });
            }catch(OutOfMemoryError oom){
                analysisElapsedMs=SystemClock.elapsedRealtime()-began;
                diagnostics.recordRecoverable(oom,"ANALYSIS_MEMORY");cancel.set(true);
                analysisFailed("메모리가 부족해 중단됐습니다. 현재까지의 추적 데이터는 저장할 수 있습니다.");
            }catch(Exception e){
                analysisElapsedMs=SystemClock.elapsedRealtime()-began;
                diagnostics.recordRecoverable(e,"ANALYSIS_EXCEPTION");
                analysisFailed("분석 중 오류: "+e.getMessage());
            }finally{if(reader!=null)reader.close();}
        });
    }
    private void analysisFailed(String message){
        runOnUiThread(()->{if(isDestroyed())return;analysing=false;
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            controls();status.setText(message);});
    }
    private void saveDiagnostic(){
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/plain");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE,"LEDOA_FACE_CRASH_v0.8.9.txt");
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
    private void saveTrack(){
        if(path.points().isEmpty())return;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/json");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE,"LEDOA_FACE_FLOW_TRACK_v0.8.9.json");startActivityForResult(i,SAVE_TRACK);
    }
    private void writeTrack(Uri uri){
        if(uri==null)return;
        try{
            JSONObject obj=new JSONObject();obj.put("schema",1).put("kind","preview-test-only");
            obj.put("strength",85).put("opacity",100).put("frameStepMs",STEP_MS).put("durationMs",durationMs);
            obj.put("reviewCount",path.reviewCount());
            obj.put("uncoveredCount",path.uncoveredCount());
            obj.put("analysisElapsedMs",analysisElapsedMs);
            obj.put("decodeMs",decodeMs).put("faceInferenceMs",faceMs).put("poseInferenceMs",poseMs).put("motionMs",motionMs);
            obj.put("decoderMode",decoderMode).put("decodedFrames",decodedFrames).put("sampledFrames",sampledFrames);
            obj.put("faceInferenceAttempts",faceInferenceAttempts);
            obj.put("reverseRepairedFrames",continuity.reverseCount());
            obj.put("confirmedDetectionBackfills",path.confirmedBackfillCount());
            obj.put("shortConfirmedGapReviews",path.confirmedGapReviewCount());
            obj.put("manualFaceKeyframes",path.manualKeyframeCount());
            obj.put("manualEditsMade",path.manualEditsMade());
            obj.put("manualInterpolatedReviewFrames",path.manualInterpolatedReviews());
            obj.put("lastCorrectedSegmentStartMs",lastRunStartMs);
            obj.put("lastCorrectedSegmentEndMs",lastRunEndMs);
            obj.put("manualAnchorsPreserved",true);
            obj.put("manualGapInterpolationRequiresReview",true);
            obj.put("manualCorrectionSupported",true);
            obj.put("peakCachedMotionFrames",continuity.peakCachedFrames());
            obj.put("motionSampleStepMs",50).put("analysedStartMs",path.firstTimestamp()).put("analysedEndMs",analysedEndMs);
            obj.put("bodyLinked",bodyClothing.isEnabled());
            obj.put("bodyConfirmedObservations",bodyClothing.heldCount());
            obj.put("bodyRejectedObservations",bodyClothing.rejectedCount());
            obj.put("faceBodyCorroborated",path.bodyValidatedCount());
            obj.put("faceBodyReacquired",path.bodyRecoveredCount());
            obj.put("screenEdgeReacquired",path.edgeRecoveredCount());
            obj.put("scaleChangeReacquired",path.scaleRecoveredCount());
            obj.put("scaleCandidateRejected",path.scaleRejectedCount());
            obj.put("roiSearchAttempts",roiAttempts);
            obj.put("roiFaceCandidatesAccepted",roiAccepted);
            obj.put("roiAmbiguousFrames",roiAmbiguous);
            obj.put("roiNoiseFramesDiscarded",roiDiscarded);
            obj.put("roiSearchThrottled",roiThrottled);
            obj.put("roiMemoryFailures",roiMemoryFailures);
            obj.put("roiDisabledForMemory",roiDisabled);
            obj.put("tinyBodyFaceRejected",path.wrongSizeBodyRejectedCount());
            obj.put("flowEstimatedFrames",path.flowEstimatedCount());
            obj.put("flowMatchingRejects",opticalBridge.rejectedCount());
            obj.put("flowExpiredGaps",opticalBridge.expiredCount());
            obj.put("flowMaximumUnverifiedMs",OpticalBridge.MAX_FACE_MISSING_MS);
            obj.put("flowRequiresReview",true);
            obj.put("flowEngine","BIDIRECTIONAL_LK_50MS_BOUNDED");
            obj.put("flowRejectionsFewPoints",opticalBridge.rejectedFewPoints());
            obj.put("flowRejectionsPhotometric",opticalBridge.rejectedPhotometric());
            obj.put("flowRejectionsGeometry",opticalBridge.rejectedGeometry());
            obj.put("flowRejectionsBody",opticalBridge.rejectedBody());
            obj.put("flowRejectionsOutside",opticalBridge.rejectedOutside());
            obj.put("flowRejectionsElapsed",opticalBridge.rejectedElapsed());
            obj.put("flowRejectionsNoAnchor",opticalBridge.rejectedNoAnchor());
            obj.put("crashDiagnosticAvailable",true);
            obj.put("analysisStabilityVersion","0.8.9");
            obj.put("onlySelectedPersonMode",true);
            obj.put("identityLockEvents",path.identityLockEvents());
            obj.put("identityLockedFrames",path.lockedFrames());
            obj.put("appearanceFluctuationFrames",path.appearanceFluctuationFrames());
            obj.put("identityLockThresholdMs",500);
            obj.put("manualReidentifyRequiredOnLoss",true);
            obj.put("mosaicRendering","SOFT_PIXEL_BILINEAR_14_CELLS");
            obj.put("roiDisabledForStability",ROI_DISABLED_FOR_STABILITY);
            obj.put("flowLazyTrustedFrames",lazyFlow.trustedUpdates());
            obj.put("flowLazyPyramidBuilds",lazyFlow.lazySeeds());
            obj.put("flowLazyAttempts",lazyFlow.flowAttempts());
            obj.put("flowLazySkipped",lazyFlow.flowSkips());
            obj.put("poseInferenceAttempts",poseInferenceAttempts);
            obj.put("poseAdaptiveExtra",poseAdaptiveExtra);
            obj.put("crowdedGapBlocked",path.crowdedGapBlockedCount());
            obj.put("privacyReviewRequiredForCrowds",true);
            // Do not disclose device-local document URIs or raw clothing features.
            JSONArray arr=new JSONArray();
            for(FacePath.Point p:path.points()){
                JSONObject o=new JSONObject().put("ms",p.ms).put("status",p.status.name());
                o.put("candidateCount",p.candidates).put("reason",p.reason);
                if(p.box!=null)o.put("x",p.box.x).put("y",p.box.y).put("w",p.box.w).put("h",p.box.h);
                arr.put(o);
            }obj.put("points",arr);
            try(OutputStream s=getContentResolver().openOutputStream(uri)){
                if(s==null)throw new Exception("출력 스트림 없음");s.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            status.setText("좌표 JSON을 저장했습니다. 완성된 모자이크 영상이 아닙니다.");
        }catch(Exception e){status.setText("좌표 저장 실패: "+e.getMessage());}
    }
    @Override protected void onDestroy(){
        stopPlayback(false);
        playbackHandler.removeCallbacksAndMessages(null);
        cancel.set(true);generation.incrementAndGet();
        worker.execute(()->{
        if(retriever!=null){try{retriever.release();}catch(java.io.IOException e){android.util.Log.w("LEDOA-AI","Video release failed",e);}}
        if(detector!=null)detector.close();
        if(roiDetector!=null)roiDetector.close();
        if(poseDetector!=null)poseDetector.close();
        });worker.shutdown();
        super.onDestroy();
    }
    private final class FrameView extends View {
        private Bitmap frame;
        private List<FacePath.Box> faces=new ArrayList<>();
        private FacePath.Point interpolated,nearest;
        private final Paint image=new Paint(Paint.FILTER_BITMAP_FLAG),line=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pixel=new Paint();
        private final RectF display=new RectF();
        private boolean drawingManual=false;
        private float downX,downY,dragX,dragY;
        FrameView(){
            super(MainActivity.this);
            setBackgroundColor(Color.BLACK);
            // Bilinear scaling softens the previous nine large jagged tiles.
            // The small bitmap still destroys most fine facial detail.
            pixel.setFilterBitmap(true);pixel.setAntiAlias(true);
        }
        void setFrame(Bitmap next,List<FacePath.Box> detections,FacePath.Point safe,FacePath.Point close){
            if(next!=null && frame!=next && frame!=null && !frame.isRecycled())frame.recycle();
            frame=next;faces=detections;interpolated=safe;nearest=close;invalidate();
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if(frame==null||frame.isRecycled()){
                line.setColor(Color.WHITE);line.setTextSize(dp(16));c.drawText("테스트용 영상 불러오기",dp(20),getHeight()/2f,line);return;
            }
            float fw=frame.getWidth(),fh=frame.getHeight();
            float scale=Math.min(getWidth()/fw,getHeight()/fh);
            float w=fw*scale,h=fh*scale,left=(getWidth()-w)/2f,top=(getHeight()-h)/2f;
            display.set(left,top,left+w,top+h);
            c.drawBitmap(frame,null,display,image);
            if(interpolated!=null&&interpolated.box!=null){
                float margin=(interpolated.status==FacePath.Status.FLOW_ESTIMATED)? .24f:.18f;
                drawMosaic(c,interpolated.box.expanded(margin));
            }
            if(nearest!=null&&(nearest.status==FacePath.Status.LOST ||
                    nearest.status==FacePath.Status.UNCERTAIN ||
                    nearest.status==FacePath.Status.BODY_HELD)){
                line.setColor(Color.RED);line.setTextSize(dp(15));
                c.drawText(nearest.status==FacePath.Status.BODY_HELD
                    ?"옷/상반신은 추적 중 · 얼굴 미확인 (가림 없음)"
                    :"추적 확인 필요 (이 구간은 가림이 없습니다)",
                    left+dp(8),top+dp(26),line);
            }
            if(nearest!=null && (nearest.status==FacePath.Status.FLOW_ESTIMATED||nearest.status==FacePath.Status.DETECTION_BRIDGED)){
                line.setColor(Color.YELLOW);line.setTextSize(dp(14));
                c.drawText("자동 보완 가림 · 직접 확인 필요",
                    left+dp(6),top+dp(45),line);
            }
            for(FacePath.Box b:faces){
                RectF rect=rectFor(b);line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(dp(1.5f));
                line.setColor(Color.rgb(136,185,224));c.drawRect(rect,line);line.setStyle(Paint.Style.FILL);
            }
            if(manualBoxMode && drawingManual){
                line.setStyle(Paint.Style.STROKE);
                line.setColor(Color.CYAN);line.setStrokeWidth(dp(2));
                c.drawRect(Math.min(downX,dragX),Math.min(downY,dragY),
                           Math.max(downX,dragX),Math.max(downY,dragY),line);
                line.setStyle(Paint.Style.FILL);
            }
            if(selected!=null&&anchorMs==currentMs){
                line.setStyle(Paint.Style.STROKE);line.setColor(Color.YELLOW);line.setStrokeWidth(dp(3));
                c.drawRect(rectFor(selected),line);line.setStyle(Paint.Style.FILL);
            }
        }
        private RectF rectFor(FacePath.Box b){return new RectF(display.left+b.x*display.width(),display.top+b.y*display.height(),display.left+(b.x+b.w)*display.width(),display.top+(b.y+b.h)*display.height());}
        private void drawMosaic(Canvas c,FacePath.Box b){
            Rect crop=new Rect(Math.max(0,(int)(b.x*frame.getWidth())),Math.max(0,(int)(b.y*frame.getHeight())),
                    Math.min(frame.getWidth(),(int)((b.x+b.w)*frame.getWidth())),Math.min(frame.getHeight(),(int)((b.y+b.h)*frame.getHeight())));
            if(crop.width()<2||crop.height()<2)return;
            Bitmap region=null,small=null;
            try{
                region=Bitmap.createBitmap(frame,crop.left,crop.top,crop.width(),crop.height());
                // More, smaller color cells and bilinear filtering result in
                // a finer, soft-edged mosaic without restoring facial details.
                // Strong, fully opaque anonymisation remains mandatory.
                int smallW=Math.max(8,Math.min(14,crop.width()/10));
                int smallH=Math.max(6,Math.min(24,
                    (int)Math.round(smallW*crop.height()/(double)crop.width())));
                small=Bitmap.createScaledBitmap(region,smallW,smallH,true);
                c.drawBitmap(small,null,rectFor(b),pixel); // 85% strength; full opacity.
            }finally{if(small!=null&&small!=region)small.recycle();if(region!=null)region.recycle();}
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            int action=e.getActionMasked();
            if(manualBoxMode){
                if(action==MotionEvent.ACTION_DOWN){
                    if(frame==null||!display.contains(e.getX(),e.getY()))return true;
                    drawingManual=true;downX=dragX=e.getX();downY=dragY=e.getY();
                    invalidate();return true;
                }
                if(action==MotionEvent.ACTION_MOVE && drawingManual){
                    dragX=Math.max(display.left,Math.min(display.right,e.getX()));
                    dragY=Math.max(display.top,Math.min(display.bottom,e.getY()));
                    invalidate();return true;
                }
                if(action==MotionEvent.ACTION_UP && drawingManual){
                    dragX=Math.max(display.left,Math.min(display.right,e.getX()));
                    dragY=Math.max(display.top,Math.min(display.bottom,e.getY()));
                    drawingManual=false;invalidate();
                    if(Math.abs(dragX-downX)<dp(18)||Math.abs(dragY-downY)<dp(18)){
                        status.setText("두 손가락이 아니라 한 손가락으로 얼굴 좌상단부터 우하단까지 드래그해 주세요.");
                        return true;
                    }
                    onManualFaceRegion((downX-display.left)/display.width(),
                        (downY-display.top)/display.height(),
                        (dragX-display.left)/display.width(),
                        (dragY-display.top)/display.height());
                    return true;
                }
                if(action==MotionEvent.ACTION_CANCEL){drawingManual=false;invalidate();}
                return true;
            }
            if(action!=MotionEvent.ACTION_UP)return true;
            if(frame==null||!display.contains(e.getX(),e.getY()))return true;
            float u=(e.getX()-display.left)/display.width(),v=(e.getY()-display.top)/display.height();
            onFaceTap(u,v);return true;
        }
    }
}

package kr.ledoa.cut.aitest;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Native, offline mosaic editor. Sampled AI is not a guarantee of full coverage. */
public final class MainActivity extends Activity {
    private static final int OPEN=1001,SAVE_VIDEO=1002,SAVE_LOG=1003;
    private static final int BG=0xff101722,PANEL=0xff1a2635,TEXT=0xffeaf1f8,MUTED=0xffa5b5c7,BLUE=0xff5eaaff;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel=new AtomicBoolean();
    private final AtomicInteger generation=new AtomicInteger();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final MosaicTimeline timeline=new MosaicTimeline();
    private final FacePath path=timeline.selectedPath;
    private com.google.mlkit.vision.face.FaceDetector detector;
    private MediaMetadataRetriever retriever;
    private FaceIdentity identity;
    private Uri sourceUri;
    private long durationMs,currentMs,anchorMs=-1;
    private int videoWidth=16,videoHeight=9;
    private volatile boolean busy,playing,destroyed;
    private boolean selectingManually,previewReady;
    private FacePath.Box selected;
    private List<FacePath.Box> visibleFaces=Collections.emptyList();
    private FrameView frame;
    private FrameLayout stage;
    private MosaicPlayer player;
    private TextView status,clock,strengthLabel,marginLabel,rangeLabel,modeHint;
    private Button open,track,play,save,stop,manual,review,log,rangeStart,rangeEnd;
    private RadioButton selectedMode,allMode;
    private SeekBar seek,strengthSeek,marginSeek;
    private ProgressBar progress;
    private BandView band;
    private LinearLayout detail;
    private long analysisElapsedMs,decodeMs,inferenceMs;
    private int detections,sampled;
    private String decoderMode="NONE",lastError="";
    private CrashDiagnostics diagnostics;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        diagnostics=new CrashDiagnostics(getApplicationContext());diagnostics.install();
        detector=FaceDetection.getClient(new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(.035f).enableTracking().build());
        renderUi();
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextColor(color);t.setTextSize(size);t.setPadding(dp(4),dp(3),dp(4),dp(3));return t;}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(10));return d;}
    private Button button(String text,boolean accent){Button b=new Button(this);b.setText(text);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(accent?BG:TEXT);b.setBackground(shape(accent?BLUE:PANEL));b.setMinHeight(dp(44));b.setPadding(dp(4),0,dp(4),0);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private void equal(LinearLayout row,View...views){for(View v:views){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(v,p);}}
    private void renderUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(12),dp(6),dp(12),dp(8));
        LinearLayout heading=row();heading.addView(label("LEDOA 모자이크",20,TEXT),new LinearLayout.LayoutParams(0,-2,1));heading.addView(label("v0.9.0",12,MUTED));root.addView(heading);
        stage=new FrameLayout(this);stage.setBackgroundColor(Color.BLACK);frame=new FrameView();stage.addView(frame,new FrameLayout.LayoutParams(-1,-1));root.addView(stage,new LinearLayout.LayoutParams(-1,0,1.2f));
        LinearLayout transport=row();play=button("▶ 재생",false);transport.addView(play,new LinearLayout.LayoutParams(dp(85),dp(42)));clock=label("00:00.0 / 00:00.0",13,TEXT);transport.addView(clock,new LinearLayout.LayoutParams(0,-2,1));root.addView(transport);
        seek=new SeekBar(this);seek.setMax(10000);root.addView(seek,new LinearLayout.LayoutParams(-1,dp(32)));
        band=new BandView();root.addView(band,new LinearLayout.LayoutParams(-1,dp(12)));root.addView(label("초록 추적 · 노랑 확인 · 빨강 누락 · 흰 선 장면 전환",10,MUTED));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){stopPlayer(false);}public void onProgressChanged(SeekBar s,int p,boolean user){if(user)clock.setText(fmt(durationMs*p/10000)+" / "+fmt(durationMs));}public void onStopTrackingTouch(SeekBar s){if(!busy)preview(durationMs*s.getProgress()/10000,null);}});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);scroll.addView(panel);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        RadioGroup modes=new RadioGroup(this);modes.setOrientation(RadioGroup.HORIZONTAL);selectedMode=new RadioButton(this);selectedMode.setId(View.generateViewId());selectedMode.setText("선택 얼굴");selectedMode.setTextColor(TEXT);allMode=new RadioButton(this);allMode.setId(View.generateViewId());allMode.setText("전체 얼굴");allMode.setTextColor(TEXT);modes.addView(selectedMode,new RadioGroup.LayoutParams(0,dp(42),1));modes.addView(allMode,new RadioGroup.LayoutParams(0,dp(42),1));selectedMode.setChecked(true);panel.addView(modes);
        modeHint=label("영상에서 가릴 얼굴을 터치하세요.",12,MUTED);panel.addView(modeHint);
        modes.setOnCheckedChangeListener((g,id)->{boolean all=id==allMode.getId();if(all==timeline.allFaces)return;stopPlayer(false);generation.incrementAndGet();timeline.allFaces=all;timeline.reset();selected=null;anchorMs=-1;updateControls();band.invalidate();if(sourceUri!=null)preview(currentMs,all?"모든 얼굴을 가립니다. 자동 추적을 눌러주세요.":"가릴 얼굴을 터치한 뒤 자동 추적을 눌러주세요.");});
        strengthLabel=label("모자이크 강도  85",13,TEXT);panel.addView(strengthLabel);strengthSeek=new SeekBar(this);strengthSeek.setMax(100);strengthSeek.setProgress(85);panel.addView(strengthSeek,new LinearLayout.LayoutParams(-1,dp(32)));strengthSeek.setOnSeekBarChangeListener(slider(p->{timeline.strength=Math.max(1,p);strengthLabel.setText("모자이크 강도  "+timeline.strength);frame.invalidate();}));
        marginLabel=label("얼굴 여백  18%",13,TEXT);panel.addView(marginLabel);marginSeek=new SeekBar(this);marginSeek.setMax(60);marginSeek.setProgress(18);panel.addView(marginSeek,new LinearLayout.LayoutParams(-1,dp(32)));marginSeek.setOnSeekBarChangeListener(slider(p->{timeline.margin=p;marginLabel.setText("얼굴 여백  "+p+"%");frame.invalidate();}));
        LinearLayout fix=row();manual=button("직접 영역 지정",false);review=button("다음 확인 구간",false);equal(fix,manual,review);panel.addView(fix);
        Button more=button("구간 · 진단  ▾",false);panel.addView(more,new LinearLayout.LayoutParams(-1,dp(40)));detail=new LinearLayout(this);detail.setOrientation(LinearLayout.VERTICAL);detail.setVisibility(View.GONE);panel.addView(detail);more.setOnClickListener(v->detail.setVisibility(detail.getVisibility()==View.GONE?View.VISIBLE:View.GONE));
        rangeLabel=label("적용 구간  전체 영상",12,MUTED);detail.addView(rangeLabel);LinearLayout ranges=row();rangeStart=button("현재부터",false);rangeEnd=button("현재까지",false);Button whole=button("전체 구간",false);equal(ranges,rangeStart,rangeEnd,whole);detail.addView(ranges);log=button("진단 기록 저장",false);detail.addView(log,new LinearLayout.LayoutParams(-1,dp(44)));
        status=label("1 영상 열기   2 얼굴 선택   3 자동 추적",13,0xffffd387);status.setMinLines(2);root.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
        LinearLayout actions=row();open=button("영상 열기",false);track=button("자동 추적",true);save=button("MP4 저장",false);equal(actions,open,track,save);root.addView(actions);stop=button("작업 취소",false);stop.setVisibility(View.GONE);root.addView(stop,new LinearLayout.LayoutParams(-1,dp(40)));
        setContentView(root);open.setOnClickListener(v->openVideo());track.setOnClickListener(v->analyse());play.setOnClickListener(v->togglePlayer());save.setOnClickListener(v->requestExport());stop.setOnClickListener(v->{cancel.set(true);status.setText("진행 중인 처리를 마친 뒤 취소합니다…");});
        manual.setOnClickListener(v->{stopPlayer(false);selectingManually=!selectingManually;manual.setText(selectingManually?"영역 지정 취소":"직접 영역 지정");status.setText(selectingManually?"얼굴의 왼쪽 위에서 오른쪽 아래로 드래그해 주세요.":"얼굴을 터치해 선택해 주세요.");frame.invalidate();});
        review.setOnClickListener(v->{stopPlayer(false);long next=timeline.nextReview(currentMs+50);if(next<0)next=timeline.nextReview(-1);if(next<0){status.setText("표시된 확인 구간이 없습니다. 전체 영상을 재생해 확인해 주세요.");return;}preview(next,"이 장면을 확인하세요. 선택 얼굴은 터치로 다시 연결할 수 있습니다.");});
        rangeStart.setOnClickListener(v->setRange(currentMs,timeline.endMs));rangeEnd.setOnClickListener(v->setRange(timeline.startMs,currentMs));whole.setOnClickListener(v->{if(!busy)setRange(0,durationMs);});
        log.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"LEDOA_MOSAIC_v0.9.0.json");startActivityForResult(i,SAVE_LOG);});
        updateControls();
    }
    interface IntValue{void set(int n);}
    private SeekBar.OnSeekBarChangeListener slider(IntValue action){return new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int p,boolean user){action.set(p);}};}
    private String fmt(long ms){return String.format(Locale.KOREA,"%02d:%02d.%d",ms/60000,(ms/1000)%60,(ms/100)%10);}
    private void setStatus(String s){if(!destroyed)status.setText(s);}
    private void updateControls(){
        boolean ready=sourceUri!=null&&!busy;open.setEnabled(!busy);play.setEnabled(ready);play.setText(playing?"Ⅱ 정지":"▶ 재생");
        track.setEnabled(ready&&!playing&&(timeline.allFaces||selected!=null));save.setEnabled(ready&&!playing&&timeline.analysedEndMs>=timeline.endMs&&timeline.endMs>0);
        selectedMode.setEnabled(!busy);allMode.setEnabled(!busy);manual.setEnabled(ready&&!playing&&!timeline.allFaces&&previewReady);review.setEnabled(ready&&!playing&&timeline.analysedEndMs>0);seek.setEnabled(ready);rangeStart.setEnabled(ready&&!playing);rangeEnd.setEnabled(ready&&!playing);log.setEnabled(ready&&!playing);strengthSeek.setEnabled(!busy);marginSeek.setEnabled(!busy);
        stop.setVisibility(busy?View.VISIBLE:View.GONE);modeHint.setText(timeline.allFaces?"장면이 바뀌면 보이는 얼굴을 새로 검출합니다.":"가릴 얼굴을 터치 · 장면에서 놓치면 다시 터치해 연결");
        rangeLabel.setText("적용 구간  "+fmt(timeline.startMs)+" ~ "+fmt(timeline.endMs));
    }
    private void setRange(long start,long end){if(busy||end<=start||end>durationMs){setStatus("시작보다 뒤쪽에 종료 시점을 지정해 주세요.");return;}stopPlayer(false);timeline.reset();timeline.startMs=start;timeline.endMs=end;selected=null;anchorMs=-1;band.invalidate();preview(start,"구간을 변경했습니다. 얼굴을 선택한 뒤 자동 추적해 주세요.");}
    private void openVideo(){stopPlayer(false);Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,OPEN);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(req==SAVE_VIDEO){export(uri);return;}if(req==SAVE_LOG){writeLog(uri);return;}if(req!=OPEN)return;
        generation.incrementAndGet();busy=true;updateControls();setStatus("영상을 불러오는 중…");
        worker.execute(()->{try{MediaMetadataRetriever next=new MediaMetadataRetriever();next.setDataSource(this,uri);long length=Long.parseLong(next.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));int w=Integer.parseInt(next.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),h=Integer.parseInt(next.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));String rotationText=next.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);int rotation=rotationText==null?0:Integer.parseInt(rotationText);if(length<=0)throw new IOException("영상 길이를 읽지 못했습니다.");if(retriever!=null)retriever.release();retriever=next;
            runOnUiThread(()->{if(destroyed)return;sourceUri=uri;durationMs=length;videoWidth=rotation%180==0?w:h;videoHeight=rotation%180==0?h:w;timeline.reset();timeline.startMs=0;timeline.endMs=length;currentMs=0;anchorMs=-1;selected=null;busy=false;previewReady=false;progress.setProgress(0);updateControls();band.invalidate();preview(0,null);});
        }catch(Exception e){runOnUiThread(()->{busy=false;updateControls();setStatus("영상 열기 실패: "+e.getMessage());});}});
    }
    private Bitmap bitmapAt(long ms){return retriever.getScaledFrameAtTime(Math.max(0,Math.min(durationMs-1,ms))*1000,MediaMetadataRetriever.OPTION_CLOSEST,640,640);}
    private FaceIdentity identity()throws IOException{if(identity==null)identity=new FaceIdentity(this);return identity;}
    private float[] appearance(Bitmap frame,Rect r){try{FaceIdentity id=identity();id.begin(frame);return id.crop(r);}catch(Exception e){throw new IllegalStateException("얼굴 특징 분석을 준비하지 못했습니다: "+e.getMessage(),e);}}
    private List<FacePath.Box> detect(Bitmap b,boolean needAppearance)throws Exception{
        List<FacePath.Box> out=new ArrayList<>();
        List<Face> detected=Tasks.await(detector.process(InputImage.fromBitmap(b,0)));
        FaceIdentity id=needAppearance&&!detected.isEmpty()?identity():null;if(id!=null)id.begin(b);
        for(Face face:detected){
            Rect r=face.getBoundingBox();float x=Math.max(0,r.left/(float)b.getWidth()),y=Math.max(0,r.top/(float)b.getHeight()),right=Math.min(1,r.right/(float)b.getWidth()),bottom=Math.min(1,r.bottom/(float)b.getHeight());if(right<=x||bottom<=y)continue;
            boolean nose=face.getLandmark(FaceLandmark.NOSE_BASE)!=null,eye=face.getLandmark(FaceLandmark.LEFT_EYE)!=null||face.getLandmark(FaceLandmark.RIGHT_EYE)!=null,mouth=face.getLandmark(FaceLandmark.MOUTH_BOTTOM)!=null;
            float[] features=id==null?null:id.describe(face);
            boolean evidence=nose&&(eye||mouth)||features!=null;
            boolean edge=(x<.035f||right>.965f||y<.035f)&&!evidence&&(nose||eye);
            out.add(new FacePath.Box(x,y,right-x,bottom-y,face.getTrackingId()==null?-1:face.getTrackingId(),features,evidence,edge));
        }
        if(out.size()>MosaicGl.MAX_FACES)throw new IOException("한 화면에 32명을 초과해 처리할 수 없습니다.");return out;
    }
    private void preview(long requested,String message){if(sourceUri==null||busy)return;stopPlayer(false);long ms=Math.max(0,Math.min(durationMs-1,requested));currentMs=ms;previewReady=false;int token=generation.incrementAndGet();updateControls();worker.execute(()->{if(token!=generation.get()||destroyed)return;Bitmap b=null;try{b=bitmapAt(ms);if(b==null)throw new IOException("프레임 없음");List<FacePath.Box> faces=detect(b,!timeline.allFaces);Bitmap image=b;runOnUiThread(()->{if(destroyed||token!=generation.get()){image.recycle();return;}visibleFaces=faces;frame.setFrame(image);previewReady=true;seek.setProgress((int)(10000*ms/Math.max(1,durationMs)));clock.setText(fmt(ms)+" / "+fmt(durationMs));updateControls();setStatus(message!=null?message:(faces.isEmpty()?"얼굴이 검출되지 않았습니다. 다른 시점 또는 직접 영역 지정을 사용해 주세요.":timeline.allFaces?faces.size()+"명 검출 · 자동 추적을 눌러주세요.":"가릴 얼굴을 터치해 선택하세요."));});}catch(Exception e){if(b!=null)b.recycle();runOnUiThread(()->setStatus("미리보기 오류: "+e.getMessage()));}});}
    private void select(FacePath.Box box,boolean manualAnchor){if(busy||playing||!previewReady||timeline.allFaces)return;if(currentMs<timeline.startMs||currentMs>=timeline.endMs){setStatus("적용 구간 안에서 얼굴을 선택해 주세요.");return;}if(timeline.analysedEndMs==0&&currentMs>timeline.startMs)timeline.startMs=currentMs;timeline.truncate(currentMs);selected=box;anchorMs=currentMs;if(manualAnchor)path.anchorManual(currentMs,box);else path.anchor(currentMs,box);selectingManually=false;manual.setText("직접 영역 지정");frame.invalidate();band.invalidate();updateControls();setStatus("얼굴 선택 완료 · 자동 추적을 누르면 이 시점부터 연결합니다.");}
    private void analyse(){if(busy||sourceUri==null||(!timeline.allFaces&&selected==null))return;stopPlayer(false);generation.incrementAndGet();cancel.set(false);busy=true;updateControls();progress.setProgress(0);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setStatus("자동 추적 중…");final boolean all=timeline.allFaces;final long start=all?timeline.startMs:anchorMs,end=timeline.endMs;final FacePath.Box anchor=selected;
        worker.execute(()->{long begun=SystemClock.elapsedRealtime(),lastUi=0,lastKey=start,processed=start;SequentialFrames reader=null;analysisElapsedMs=decodeMs=inferenceMs=0;sampled=detections=0;SceneCuts cuts=new SceneCuts();ContinuityBridge continuity=new ContinuityBridge(path,new LazyFlowBridge(new OpticalBridge()));diagnostics.begin(start);boolean complete=false;
            try{
                if(all)timeline.reset();else timeline.truncate(start);
                if(!all)path.anchor(start,anchor);
                try{reader=new SequentialFrames(this,sourceUri,start,cancel);decoderMode="SEQUENTIAL_MEDIACODEC";}catch(Exception unavailable){decoderMode="RETRIEVER_FALLBACK";}
                Bitmap seed=bitmapAt(start);try{MotionFrames.Gray g=MotionFrames.grayscale(seed);cuts.observe(start,g.pixels,g.w,g.h);List<FacePath.Box> first=all?detect(seed,false):Collections.singletonList(anchor);timeline.put(start,first);if(!all)continuity.process(start,g.pixels,g.w,g.h,path.exact(start),null);}finally{seed.recycle();}
                for(long requested=start+50;requested<end;requested+=50){
                    if(cancel.get()||destroyed)break;boolean key=(requested-start)%100==0||requested+50>=end;
                    if(reader==null&&!key)continue;long tick=SystemClock.elapsedRealtime();Bitmap b;long ms=requested;
                    if(reader!=null){try{SequentialFrames.Frame f=reader.at(requested,key?640:224);if(f==null){complete=processed>=end-150;break;}b=f.bitmap;ms=f.ms;}catch(Exception codecError){reader.close();reader=null;decoderMode="RETRIEVER_FALLBACK";if(cancel.get())break;b=bitmapAt(requested);key=true;}}
                    else b=bitmapAt(requested);decodeMs+=SystemClock.elapsedRealtime()-tick;
                    if(b==null)throw new IOException("영상 프레임을 읽지 못했습니다.");
                    if(ms>=end){b.recycle();complete=true;break;}if(ms<=processed){b.recycle();continue;}
                    try{
                        processed=ms;sampled++;MotionFrames.Gray g=MotionFrames.grayscale(b);boolean cut=cuts.observe(ms,g.pixels,g.w,g.h);
                        if(cut){timeline.cut(ms);continuity.reset();if(!key){Bitmap full=bitmapAt(ms);if(full!=null){b.recycle();b=full;g=MotionFrames.grayscale(b);}}key=true;}
                        if(!key){if(!all)continuity.cache(ms,g.pixels,g.w,g.h);continue;}
                        tick=SystemClock.elapsedRealtime();List<FacePath.Box> found=detect(b,!all);detections++;inferenceMs+=SystemClock.elapsedRealtime()-tick;timeline.put(ms,found);
                        if(!all){FacePath.Point p=path.step(ms,found);continuity.process(ms,g.pixels,g.w,g.h,p,null);}
                        lastKey=ms;diagnostics.progress(ms,"FACE_ANALYSIS");long now=SystemClock.elapsedRealtime();
                        if(now-lastUi>250){lastUi=now;final int pct=(int)(100*(ms-start)/Math.max(1,end-start));final long elapsed=now-begun;runOnUiThread(()->{if(destroyed)return;progress.setProgress(pct);setStatus("얼굴 추적 "+pct+"% · "+String.format(Locale.KOREA,"%.1f",elapsed/1000.)+"초 경과");});}
                    }finally{b.recycle();}
                    if(requested+50>=end)complete=true;
                }
                boolean stopped=cancel.get()||destroyed;complete=complete||processed>=end-150;timeline.analysedEndMs=stopped?Math.min(end,lastKey+100):(complete?end:Math.min(end,lastKey+100));path.coverageEnd(timeline.analysedEndMs);if(!all)path.bridgeShortConfirmedGaps();analysisElapsedMs=SystemClock.elapsedRealtime()-begun;diagnostics.finished(timeline.analysedEndMs,!stopped&&complete);
                final String result=(stopped?"추적 중단":complete?"추적 완료":"영상 끝까지 읽지 못했습니다")+" · "+String.format(Locale.KOREA,"%.1f",analysisElapsedMs/1000.)+"초 · 장면 전환 "+timeline.cuts().size()+"곳"+(all?" · 전체 재생으로 누락을 확인하세요.":" · 가림 없는 샘플 "+path.uncoveredCount()+"개. 확인 구간에서 다시 연결하세요.");
                runOnUiThread(()->finishWork(start,result));
            }catch(Throwable e){lastError=e.toString();diagnostics.recordRecoverable(e,"MOSAIC_ANALYSIS");timeline.analysedEndMs=Math.min(end,lastKey);runOnUiThread(()->finishWork(start,"추적 오류: "+e.getMessage()));}
            finally{if(reader!=null)reader.close();}
        });
    }
    private void finishWork(long at,String text){if(destroyed)return;busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);progress.setProgress(timeline.analysedEndMs>=timeline.endMs?100:0);updateControls();band.invalidate();preview(at,text);}
    private void togglePlayer(){if(playing){stopPlayer(true);return;}if(sourceUri==null||busy)return;generation.incrementAndGet();if(currentMs>=durationMs-100)currentMs=0;playing=true;frame.setVisibility(View.INVISIBLE);player=new MosaicPlayer(this,sourceUri,timeline,currentMs,videoWidth,videoHeight,new MosaicPlayer.Events(){public void ready(){setStatus("소리와 함께 재생 중 · 모자이크 누락을 확인하세요.");tickPlayer();}public void finished(){stopPlayer(false);preview(Math.max(0,durationMs-50),"재생 완료 · 확인 후 MP4 저장을 눌러주세요.");}public void failed(String m){stopPlayer(false);preview(currentMs,"재생 오류: "+m);}});stage.addView(player,new FrameLayout.LayoutParams(-1,-1));updateControls();setStatus("재생 준비 중…");}
    private void tickPlayer(){if(!playing||player==null)return;currentMs=player.position();seek.setProgress((int)(10000*currentMs/Math.max(1,durationMs)));clock.setText(fmt(currentMs)+" / "+fmt(durationMs));ui.postDelayed(this::tickPlayer,50);}
    private void stopPlayer(boolean reload){if(!playing)return;playing=false;ui.removeCallbacksAndMessages(null);if(player!=null){currentMs=player.position();player.release();stage.removeView(player);player=null;}frame.setVisibility(View.VISIBLE);updateControls();if(reload)preview(currentMs,null);}
    private void requestExport(){if(busy||timeline.analysedEndMs<timeline.endMs)return;stopPlayer(false);String message=timeline.allFaces?"전체 얼굴 모드도 옆얼굴·가려진 얼굴을 놓칠 수 있습니다. 재생으로 확인한 결과를 MP4로 저장합니다.":"가림 없는 샘플 "+path.uncoveredCount()+"개가 있습니다. 선택 얼굴이 장면에서 끊기면 다시 선택해 연결할 수 있습니다. 현재 보이는 결과대로 저장합니다.";new AlertDialog.Builder(this).setTitle("모자이크 결과 저장").setMessage(message).setNegativeButton("더 확인",null).setPositiveButton("MP4 저장",(d,w)->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("video/mp4").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"LEDOA_MOSAIC_"+System.currentTimeMillis()+".mp4");startActivityForResult(i,SAVE_VIDEO);}).show();}
    private void export(Uri destination){if(busy)return;generation.incrementAndGet();busy=true;cancel.set(false);progress.setProgress(0);updateControls();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setStatus("MP4 저장 중…");worker.execute(()->{File temporary=new File(getCacheDir(),"mosaic-export.mp4");try{MosaicExport.Result r=MosaicExport.run(this,sourceUri,temporary,timeline,cancel,p->runOnUiThread(()->{progress.setProgress(p);setStatus("MP4 저장 "+p+"%");}));if(cancel.get())throw new InterruptedException("저장을 취소했습니다.");try(InputStream in=new FileInputStream(temporary);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new IOException("저장 위치를 열지 못했습니다.");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(cancel.get())throw new InterruptedException("저장을 취소했습니다.");out.write(buffer,0,n);}}runOnUiThread(()->{finishWork(currentMs,"MP4 저장 완료 · "+r.width+"×"+r.height+(r.audio?" · 원본 소리 포함":" · 원본에 소리 없음"));new AlertDialog.Builder(this).setTitle("저장 완료").setMessage("선택한 위치에 MP4 파일을 저장했습니다.").setNegativeButton("닫기",null).setPositiveButton("영상 열기",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(destination,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){setStatus("파일 앱에서 저장한 영상을 열어주세요.");}}).show();});}catch(Throwable e){lastError=e.toString();try{DocumentsContract.deleteDocument(getContentResolver(),destination);}catch(Exception ignored){}runOnUiThread(()->finishWork(currentMs,"저장 실패: "+e.getMessage()));}finally{temporary.delete();}});}
    private void writeLog(Uri uri){try{JSONObject root=new JSONObject();root.put("version","0.9.0");root.put("identityModel","OpenCV_SFace_INT8_local");root.put("mode",timeline.allFaces?"ALL_FACES":"SELECTED_FACE");root.put("durationMs",durationMs);root.put("rangeStartMs",timeline.startMs);root.put("rangeEndMs",timeline.endMs);root.put("analysedEndMs",timeline.analysedEndMs);root.put("analysisElapsedMs",analysisElapsedMs);root.put("decodeMs",decodeMs);root.put("faceInferenceMs",inferenceMs);root.put("faceInferenceAttempts",detections);root.put("sampledFrames",sampled);root.put("decoderMode",decoderMode);root.put("lastError",lastError);root.put("sceneCuts",new JSONArray(timeline.cuts()));root.put("strength",timeline.strength);root.put("margin",timeline.margin);JSONArray points=new JSONArray();if(!timeline.allFaces)for(FacePath.Point p:path.points()){JSONObject j=new JSONObject();j.put("ms",p.ms);j.put("status",p.status.name());j.put("reason",p.reason);if(p.box!=null)boxJson(j,p.box);points.put(j);}else for(Map.Entry<Long,List<FacePath.Box>> e:timeline.samples().entrySet()){JSONObject j=new JSONObject();j.put("ms",e.getKey());JSONArray faces=new JSONArray();for(FacePath.Box b:e.getValue()){JSONObject f=new JSONObject();boxJson(f,b);faces.put(f);}j.put("faces",faces);points.put(j);}root.put("points",points);try(OutputStream o=getContentResolver().openOutputStream(uri,"wt")){o.write(root.toString(2).getBytes(StandardCharsets.UTF_8));}setStatus("진단 기록을 저장했습니다.");}catch(Exception e){setStatus("진단 저장 오류: "+e.getMessage());}}
    private void boxJson(JSONObject j,FacePath.Box b)throws JSONException{j.put("x",b.x);j.put("y",b.y);j.put("w",b.w);j.put("h",b.h);}
    @Override protected void onPause(){stopPlayer(false);super.onPause();}
    @Override protected void onDestroy(){destroyed=true;generation.incrementAndGet();cancel.set(true);stopPlayer(false);worker.execute(()->{detector.close();if(identity!=null)identity.close();if(retriever!=null)try{retriever.release();}catch(Exception ignored){}});worker.shutdown();if(frame.bitmap!=null)frame.bitmap.recycle();super.onDestroy();}
    private final class BandView extends View{private final Paint paint=new Paint();BandView(){super(MainActivity.this);}protected void onDraw(Canvas c){super.onDraw(c);if(durationMs<=0)return;paint.setColor(PANEL);c.drawRect(0,0,getWidth(),getHeight(),paint);if(timeline.allFaces){for(Map.Entry<Long,List<FacePath.Box>> e:timeline.samples().entrySet()){paint.setColor(e.getValue().isEmpty()?0xffe4aa54:0xff50c4b1);c.drawRect(getWidth()*e.getKey()/(float)durationMs,0,getWidth()*Math.min(durationMs,e.getKey()+100)/(float)durationMs,getHeight(),paint);}}else for(FacePath.Point p:path.points()){paint.setColor(p.box==null?0xffec7869:p.reason.contains("REVIEW")||p.status==FacePath.Status.FLOW_ESTIMATED?0xffe4aa54:0xff50c4b1);c.drawRect(getWidth()*p.ms/(float)durationMs,0,getWidth()*Math.min(durationMs,p.ms+100)/(float)durationMs,getHeight(),paint);}paint.setColor(Color.WHITE);for(long ms:timeline.cuts())c.drawRect(getWidth()*ms/(float)durationMs,0,getWidth()*ms/(float)durationMs+dp(2),getHeight(),paint);}}
    private final class FrameView extends View{
        private Bitmap bitmap;private final Paint image=new Paint(Paint.FILTER_BITMAP_FLAG),pen=new Paint(Paint.ANTI_ALIAS_FLAG),pixel=new Paint();private final RectF area=new RectF();private float downX,downY,endX,endY;private boolean dragging;
        FrameView(){super(MainActivity.this);pixel.setFilterBitmap(false);setContentDescription("얼굴 선택 미리보기");}
        void setFrame(Bitmap b){if(bitmap!=null&&bitmap!=b)bitmap.recycle();bitmap=b;invalidate();}
        RectF rect(FacePath.Box b){return new RectF(area.left+b.x*area.width(),area.top+b.y*area.height(),area.left+(b.x+b.w)*area.width(),area.top+(b.y+b.h)*area.height());}
        protected void onDraw(Canvas c){super.onDraw(c);if(bitmap==null||bitmap.isRecycled()){pen.setColor(MUTED);pen.setTextSize(dp(16));c.drawText("영상 열기로 시작하세요",dp(20),getHeight()/2f,pen);return;}float scale=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;area.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);c.drawBitmap(bitmap,null,area,image);
            List<FacePath.Box> masks=timeline.boxesAt(currentMs);if(timeline.allFaces&&timeline.analysedEndMs==0)masks=visibleFaces;if(selected!=null&&currentMs==anchorMs&&!timeline.allFaces)masks=Collections.singletonList(selected);for(FacePath.Box b:masks)mosaic(c,b.expanded(timeline.margin/100f));
            pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(dp(1.5f));pen.setColor(BLUE);for(FacePath.Box b:visibleFaces)c.drawRoundRect(rect(b),dp(3),dp(3),pen);if(selected!=null&&currentMs==anchorMs){pen.setStrokeWidth(dp(3));pen.setColor(0xffffd387);c.drawRect(rect(selected),pen);}if(dragging){pen.setColor(Color.CYAN);c.drawRect(Math.min(downX,endX),Math.min(downY,endY),Math.max(downX,endX),Math.max(downY,endY),pen);}pen.setStyle(Paint.Style.FILL);
        }
        private void mosaic(Canvas c,FacePath.Box b){Rect crop=new Rect(Math.max(0,(int)(b.x*bitmap.getWidth())),Math.max(0,(int)(b.y*bitmap.getHeight())),Math.min(bitmap.getWidth(),(int)((b.x+b.w)*bitmap.getWidth())),Math.min(bitmap.getHeight(),(int)((b.y+b.h)*bitmap.getHeight())));if(crop.width()<2||crop.height()<2)return;int cols=timeline.columns(),rows=Math.max(2,Math.round(cols*crop.height()/(float)crop.width()));Bitmap small=Bitmap.createBitmap(cols,rows,Bitmap.Config.ARGB_8888);Canvas temp=new Canvas(small);temp.drawBitmap(bitmap,crop,new Rect(0,0,cols,rows),pixel);c.drawBitmap(small,null,rect(b),pixel);small.recycle();}
        public boolean onTouchEvent(MotionEvent e){if(busy||playing||!previewReady||timeline.allFaces)return true;int act=e.getActionMasked();if(act==MotionEvent.ACTION_DOWN){if(!area.contains(e.getX(),e.getY()))return true;downX=endX=e.getX();downY=endY=e.getY();dragging=selectingManually;return true;}if(act==MotionEvent.ACTION_MOVE&&dragging){endX=Math.max(area.left,Math.min(area.right,e.getX()));endY=Math.max(area.top,Math.min(area.bottom,e.getY()));invalidate();return true;}if(act==MotionEvent.ACTION_CANCEL){dragging=false;invalidate();return true;}if(act!=MotionEvent.ACTION_UP)return true;if(!area.contains(e.getX(),e.getY())&&!dragging)return true;
            if(dragging){endX=Math.max(area.left,Math.min(area.right,e.getX()));endY=Math.max(area.top,Math.min(area.bottom,e.getY()));dragging=false;float x=(Math.min(downX,endX)-area.left)/area.width(),y=(Math.min(downY,endY)-area.top)/area.height(),w=Math.abs(endX-downX)/area.width(),h=Math.abs(endY-downY)/area.height();if(w<.03f||h<.03f){setStatus("얼굴을 감싸도록 조금 더 크게 드래그해 주세요.");invalidate();return true;}Rect r=new Rect((int)(x*bitmap.getWidth()),(int)(y*bitmap.getHeight()),(int)((x+w)*bitmap.getWidth()),(int)((y+h)*bitmap.getHeight()));select(new FacePath.Box(x,y,w,h,-1,appearance(bitmap,r),true,false),true);return true;}
            float x=(e.getX()-area.left)/area.width(),y=(e.getY()-area.top)/area.height();FacePath.Box picked=null;for(FacePath.Box b:visibleFaces)if(b.expanded(.08f).contains(x,y)&&(picked==null||b.area()<picked.area()))picked=b;if(picked!=null)select(picked,false);else setStatus("검출된 얼굴 안을 터치하거나 직접 영역 지정을 사용해 주세요.");return true;
        }
    }
}

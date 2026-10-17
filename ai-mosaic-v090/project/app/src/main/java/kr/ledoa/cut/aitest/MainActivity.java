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
    private FrameScrubber scrubber;
    private FilmstripView filmstrip;
    private boolean scrubbing;
    private int scrubSession;
    private long scrubTargetMs,displayedFrameMs=-1;
    private Button stepBack,stepNext;
    private TextView status,clock,strengthLabel,marginLabel,rangeLabel,modeHint;
    private Button open,track,play,save,stop,manual,review,log,rangeStart,rangeEnd;
    private RadioButton selectedMode,allMode,manualMode;
    private RadioGroup modes;
    private Button addManual,correctManual,previousPoint,nextPoint,pinPoint,deletePoint,undoManual,deleteManual,splitManual;
    private Spinner manualPicker;
    private TextView pointLabel,zoomLabel;
    private LinearLayout manualTools;
    private final ArrayList<Integer> manualIds=new ArrayList<>();
    private int activeManualId=-1,nextManualId=1;
    private boolean creatingManual,creatingCorrection,redrawingManual,refreshingPicker;
    private long frameStepMs=33;
    private final TimelineViewport viewport=new TimelineViewport();
    private final ArrayDeque<ManualUndo> manualUndo=new ArrayDeque<>();
    private static final class ManualUndo {
        final List<ManualTrack> tracks; final int active;
        ManualUndo(List<ManualTrack> tracks,int active){this.tracks=tracks;this.active=active;}
    }
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
        LinearLayout heading=row();heading.addView(label("LEDOA 모자이크",20,TEXT),new LinearLayout.LayoutParams(0,-2,1));heading.addView(label("v0.9.3",12,MUTED));root.addView(heading);
        stage=new FrameLayout(this);stage.setBackgroundColor(Color.BLACK);frame=new FrameView();stage.addView(frame,new FrameLayout.LayoutParams(-1,-1));root.addView(stage,new LinearLayout.LayoutParams(-1,0,1.2f));
        LinearLayout transport=row();play=button("▶ 재생",false);transport.addView(play,new LinearLayout.LayoutParams(dp(72),dp(42)));
        stepBack=button("◀",false);stepBack.setContentDescription("33밀리초 뒤로 이동");transport.addView(stepBack,new LinearLayout.LayoutParams(dp(36),dp(42)));
        clock=label("00:00.000 / 00:00.000",12,TEXT);transport.addView(clock,new LinearLayout.LayoutParams(0,-2,1));
        stepNext=button("▶",false);stepNext.setContentDescription("33밀리초 앞으로 이동");transport.addView(stepNext,new LinearLayout.LayoutParams(dp(36),dp(42)));root.addView(transport);
        stepBack.setOnClickListener(v->preview(currentMs-frameStepMs,null));stepNext.setOnClickListener(v->preview(currentMs+frameStepMs,null));
        seek=new SeekBar(this);seek.setMax(10000);seek.setVisibility(View.GONE);root.addView(seek,new LinearLayout.LayoutParams(-1,dp(32)));
        LinearLayout zoomRow=row();Button zoomOut=button("−",false),zoomFit=button("전체",false),zoomIn=button("＋",false);
        zoomLabel=label("타임라인 1×",11,MUTED);zoomRow.addView(zoomLabel,new LinearLayout.LayoutParams(0,dp(32),1));
        for(Button b:new Button[]{zoomOut,zoomFit,zoomIn})zoomRow.addView(b,new LinearLayout.LayoutParams(dp(54),dp(32)));root.addView(zoomRow);
        zoomOut.setOnClickListener(v->zoomTimeline(.5));zoomIn.setOnClickListener(v->zoomTimeline(2));zoomFit.setOnClickListener(v->{viewport.reset(durationMs);updateZoom();invalidateTimeline();});
        filmstrip=new FilmstripView();root.addView(filmstrip,new LinearLayout.LayoutParams(-1,dp(50)));
        band=new BandView();root.addView(band,new LinearLayout.LayoutParams(-1,dp(78)));root.addView(label("영상 띠를 드래그해 탐색 · ● 수동 지점 · 양 끝 구간 · 두 손가락 확대",10,MUTED));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){stopPlayer(false);}public void onProgressChanged(SeekBar s,int p,boolean user){if(user)clock.setText(fmt(durationMs*p/10000)+" / "+fmt(durationMs));}public void onStopTrackingTouch(SeekBar s){if(!busy)preview(durationMs*s.getProgress()/10000,null);}});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);scroll.addView(panel);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        modes=new RadioGroup(this);modes.setOrientation(RadioGroup.HORIZONTAL);selectedMode=new RadioButton(this);selectedMode.setId(View.generateViewId());selectedMode.setText("선택 얼굴");selectedMode.setTextColor(TEXT);allMode=new RadioButton(this);allMode.setId(View.generateViewId());allMode.setText("전체 얼굴");allMode.setTextColor(TEXT);manualMode=new RadioButton(this);manualMode.setId(View.generateViewId());manualMode.setText("수동 추적");manualMode.setTextColor(TEXT);
        selectedMode.setText("한 얼굴");allMode.setText("전체 얼굴");for(RadioButton b:new RadioButton[]{selectedMode,allMode,manualMode}){b.setTextSize(13);modes.addView(b,new RadioGroup.LayoutParams(0,dp(42),1));}selectedMode.setChecked(true);panel.addView(modes);
        modeHint=label("영상에서 가릴 얼굴을 터치하세요.",12,MUTED);panel.addView(modeHint);
        modes.setOnCheckedChangeListener((g,id)->{
            stopPlayer(false);generation.incrementAndGet();boolean only=id==manualMode.getId(),all=id==allMode.getId();
            if(!only&&all!=timeline.allFaces){timeline.allFaces=all;timeline.resetAuto();selected=null;anchorMs=-1;}
            timeline.manualOnly=only;activeManualId=-1;creatingManual=creatingCorrection=redrawingManual=selectingManually=false;refreshManualUi();updateControls();invalidateTimeline();
            if(sourceUri!=null)preview(currentMs,only?"＋ 수동 박스로 얼굴·번호판을 감싸세요. 자동 분석 없이 저장할 수 있습니다.":"자동 경로와 수동 박스를 함께 사용합니다. 가릴 얼굴을 선택하세요.");
        });
        strengthLabel=label("모자이크 강도  85",13,TEXT);panel.addView(strengthLabel);strengthSeek=new SeekBar(this);strengthSeek.setMax(100);strengthSeek.setProgress(85);panel.addView(strengthSeek,new LinearLayout.LayoutParams(-1,dp(32)));strengthSeek.setOnSeekBarChangeListener(slider(p->{timeline.strength=Math.max(1,p);strengthLabel.setText("모자이크 강도  "+timeline.strength);frame.invalidate();}));
        marginLabel=label("박스 여백  18%",13,TEXT);panel.addView(marginLabel);marginSeek=new SeekBar(this);marginSeek.setMax(60);marginSeek.setProgress(18);panel.addView(marginSeek,new LinearLayout.LayoutParams(-1,dp(32)));marginSeek.setOnSeekBarChangeListener(slider(p->{timeline.margin=p;marginLabel.setText("박스 여백  "+p+"%");frame.invalidate();}));
        LinearLayout fix=row();manual=button("직접 영역 지정",false);review=button("다음 확인 구간",false);equal(fix,manual,review);panel.addView(fix);
        LinearLayout manualActions=row();addManual=button("＋ 수동 박스",true);correctManual=button("자동 경로 보정",false);equal(manualActions,addManual,correctManual);panel.addView(manualActions);
        manualPicker=new Spinner(this);manualPicker.setContentDescription("편집할 수동 박스 선택");panel.addView(manualPicker,new LinearLayout.LayoutParams(-1,dp(42)));
        manualPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(refreshingPicker||position>=manualIds.size())return;int next=manualIds.get(position);if(next==activeManualId)return;activeManualId=next;creatingManual=creatingCorrection=redrawingManual=selectingManually=false;stopPlayer(false);updateControls();invalidateTimeline();frame.invalidate();if(sourceUri!=null)preview(currentMs,activeManualId<0?"자동 얼굴 선택으로 돌아왔습니다.":"박스 안을 끌어 이동 · 네 모서리로 크기 조절 · 놓으면 현재 지점 저장");}});
        manualTools=new LinearLayout(this);manualTools.setOrientation(LinearLayout.VERTICAL);panel.addView(manualTools);
        pointLabel=label("",12,MUTED);manualTools.addView(pointLabel);
        LinearLayout pointRow=row();previousPoint=button("◀ 지점",false);pinPoint=button("＋ 지점",false);deletePoint=button("지점 삭제",false);nextPoint=button("지점 ▶",false);equal(pointRow,previousPoint,pinPoint,deletePoint,nextPoint);manualTools.addView(pointRow);
        LinearLayout editRow=row();Button backFrame=button("◀ 미세",false),nextFrame=button("미세 ▶",false);undoManual=button("실행 취소",false);equal(editRow,backFrame,nextFrame,undoManual);manualTools.addView(editRow);
        LinearLayout trackRow=row();splitManual=button("여기서 나누기",false);deleteManual=button("박스 삭제",false);equal(trackRow,splitManual,deleteManual);manualTools.addView(trackRow);
        addManual.setOnClickListener(v->beginManual(false));correctManual.setOnClickListener(v->beginManual(true));
        previousPoint.setOnClickListener(v->jumpPoint(false));nextPoint.setOnClickListener(v->jumpPoint(true));pinPoint.setOnClickListener(v->pinManualPoint());deletePoint.setOnClickListener(v->removeManualPoint());
        backFrame.setOnClickListener(v->preview(currentMs-frameStepMs,null));nextFrame.setOnClickListener(v->preview(currentMs+frameStepMs,null));undoManual.setOnClickListener(v->undoManualEdit());
        splitManual.setOnClickListener(v->splitManualTrack());deleteManual.setOnClickListener(v->removeManualTrack());
        // Put the primary manual workflow before appearance settings on small screens.
        panel.removeView(manualActions);panel.removeView(manualPicker);panel.removeView(manualTools);
        panel.addView(manualActions,2);panel.addView(manualPicker,3);panel.addView(manualTools,4);
        Button more=button("구간 · 진단  ▾",false);panel.addView(more,new LinearLayout.LayoutParams(-1,dp(40)));detail=new LinearLayout(this);detail.setOrientation(LinearLayout.VERTICAL);detail.setVisibility(View.GONE);panel.addView(detail);more.setOnClickListener(v->detail.setVisibility(detail.getVisibility()==View.GONE?View.VISIBLE:View.GONE));
        rangeLabel=label("적용 구간  전체 영상",12,MUTED);detail.addView(rangeLabel);LinearLayout ranges=row();rangeStart=button("현재부터",false);rangeEnd=button("현재까지",false);Button whole=button("전체 구간",false);equal(ranges,rangeStart,rangeEnd,whole);detail.addView(ranges);log=button("진단 기록 저장",false);detail.addView(log,new LinearLayout.LayoutParams(-1,dp(44)));
        status=label("1 영상 열기   2 얼굴 선택   3 자동 추적",13,0xffffd387);status.setMinLines(2);root.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
        LinearLayout actions=row();open=button("영상 열기",false);track=button("자동 추적",true);save=button("MP4 저장",false);equal(actions,open,track,save);root.addView(actions);stop=button("작업 취소",false);stop.setVisibility(View.GONE);root.addView(stop,new LinearLayout.LayoutParams(-1,dp(40)));
        setContentView(root);open.setOnClickListener(v->openVideo());track.setOnClickListener(v->analyse());play.setOnClickListener(v->togglePlayer());save.setOnClickListener(v->requestExport());stop.setOnClickListener(v->{cancel.set(true);status.setText("진행 중인 처리를 마친 뒤 취소합니다…");});
        manual.setOnClickListener(v->{stopPlayer(false);activeManualId=-1;creatingManual=creatingCorrection=redrawingManual=false;refreshManualUi();selectingManually=!selectingManually;manual.setText(selectingManually?"영역 지정 취소":"직접 영역 지정");status.setText(selectingManually?"얼굴의 왼쪽 위에서 오른쪽 아래로 드래그해 주세요.":"얼굴을 터치해 선택해 주세요.");frame.invalidate();});
        review.setOnClickListener(v->{stopPlayer(false);long next=timeline.nextReview(currentMs+50);if(next<0)next=timeline.nextReview(-1);if(next<0){status.setText("표시된 확인 구간이 없습니다. 전체 영상을 재생해 확인해 주세요.");return;}preview(next,"이 장면을 확인하세요. 선택 얼굴은 터치로 다시 연결할 수 있습니다.");});
        rangeStart.setOnClickListener(v->setRange(currentMs,activeManual()==null?timeline.endMs:activeManual().end()));rangeEnd.setOnClickListener(v->setRange(activeManual()==null?timeline.startMs:activeManual().start(),currentMs));whole.setOnClickListener(v->{if(!busy)setRange(0,durationMs);});
        log.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"LEDOA_MOSAIC_v0.9.3.json");startActivityForResult(i,SAVE_LOG);});
        refreshManualUi();updateControls();
    }
    private ManualTrack activeManual(){return timeline.manual(activeManualId);}
    private void rememberManual(){manualUndo.addLast(new ManualUndo(timeline.manualSnapshot(),activeManualId));while(manualUndo.size()>50)manualUndo.removeFirst();}
    private void refreshManualUi(){
        refreshingPicker=true;manualIds.clear();manualIds.add(-1);ArrayList<String> names=new ArrayList<>();names.add(timeline.manualOnly?"편집할 박스를 선택하세요":"자동 얼굴 선택 / 수동 박스 선택");
        int selectedIndex=0;for(ManualTrack t:timeline.manualTracks()){manualIds.add(t.id);names.add((t.replacesSelected?"자동 보정 ":"수동 박스 ")+t.id+" · "+t.size()+"개 지점");if(t.id==activeManualId)selectedIndex=names.size()-1;}
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,names){
            public View getView(int pos,View old,ViewGroup parent){TextView v=(TextView)super.getView(pos,old,parent);v.setTextColor(TEXT);v.setTextSize(13);return v;}
            public View getDropDownView(int pos,View old,ViewGroup parent){TextView v=(TextView)super.getDropDownView(pos,old,parent);v.setTextColor(TEXT);v.setBackgroundColor(PANEL);v.setPadding(dp(10),dp(12),dp(10),dp(12));return v;}
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);manualPicker.setAdapter(adapter);manualPicker.setSelection(selectedIndex,false);refreshingPicker=false;
    }
    private void beginManual(boolean correction){
        if(busy||sourceUri==null||!previewReady)return;stopPlayer(false);
        if(correction&&(timeline.manualOnly||timeline.allFaces||timeline.analysedEndMs==0))return;
        if(timeline.manualTracks().size()>=16){setStatus("수동 박스는 최대 16개까지 사용할 수 있습니다.");return;}
        if(!correction&&timeline.analysedEndMs==0&&!timeline.manualOnly)manualMode.setChecked(true);
        creatingManual=true;redrawingManual=false;creatingCorrection=correction;selectingManually=false;activeManualId=-1;refreshManualUi();updateControls();
        List<FacePath.Box> auto=timeline.autoBoxesAt(currentMs);
        if(correction&&!auto.isEmpty()){commitNewManual(auto.get(0));return;}
        setStatus("얼굴·번호판을 감싸도록 드래그하세요. 생성 후 박스 안으로 이동, 네 모서리로 크기를 조절합니다.");frame.invalidate();
    }
    private void commitNewManual(FacePath.Box box){
        if(creatingCorrection&&(currentMs<timeline.startMs||currentMs>=timeline.endMs)){setStatus("자동 적용 구간 안에서 보정해 주세요.");return;}
        rememberManual();ManualTrack t=new ManualTrack(nextManualId++,creatingCorrection,currentMs,creatingCorrection?timeline.endMs:durationMs);t.put(currentMs,box);timeline.addManual(t);activeManualId=t.id;
        creatingManual=creatingCorrection=redrawingManual=selectingManually=false;refreshManualUi();updateControls();invalidateTimeline();frame.invalidate();
        setStatus("첫 지점 저장 · 다음 시점으로 이동한 뒤 박스를 옮기거나 모서리를 조절하세요. 파란 막대 양 끝으로 적용 구간을 정합니다.");
    }
    private void commitManualBox(FacePath.Box box){
        ManualTrack t=activeManual();if(t==null||!t.contains(currentMs))return;rememberManual();
        Long near=t.near(currentMs,Math.max(1,frameStepMs/2));t.put(near==null?currentMs:near,box);
        refreshManualUi();updateControls();invalidateTimeline();frame.invalidate();setStatus("지점 저장 · "+fmt(currentMs)+" · 위치와 가로·세로 크기를 함께 연결합니다.");
    }
    private void pinManualPoint(){ManualTrack t=activeManual();if(t==null)return;FacePath.Box b=t.at(currentMs,timeline.cuts());if(b==null){if(t.contains(currentMs)){redrawingManual=true;setStatus("이 장면에서 가릴 영역을 드래그해 새 지점을 지정하세요.");}else setStatus("선택 박스의 적용 구간 안에서 지점을 추가하세요.");return;}commitManualBox(b);}
    private void jumpPoint(boolean forward){ManualTrack t=activeManual();if(t==null)return;Long at=forward?t.next(currentMs):t.previous(currentMs);if(at==null||!t.contains(at)){setStatus(forward?"다음 지점이 없습니다.":"이전 지점이 없습니다.");return;}preview(at,null);}
    private void removeManualPoint(){ManualTrack t=activeManual();if(t==null)return;Long ms=t.near(currentMs,Math.max(1,frameStepMs/2));if(ms==null){setStatus("지점 표시를 터치한 뒤 삭제해 주세요.");return;}rememberManual();t.remove(ms);if(t.size()==0){timeline.removeManual(t.id);activeManualId=-1;}manualChanged("지점을 삭제했습니다. 실행 취소로 복원할 수 있습니다.");}
    private void removeManualTrack(){ManualTrack t=activeManual();if(t==null)return;rememberManual();timeline.removeManual(t.id);activeManualId=-1;manualChanged("선택 박스를 삭제했습니다. 실행 취소로 복원할 수 있습니다.");}
    private void splitManualTrack(){ManualTrack t=activeManual();if(t==null)return;if(currentMs<=t.start()||currentMs>=t.end()){setStatus("박스 적용 구간의 중간에서 나눠 주세요.");return;}if(timeline.manualTracks().size()>=16){setStatus("수동 박스는 최대 16개입니다.");return;}rememberManual();ManualTrack right=t.split(currentMs,nextManualId++,timeline.cuts());timeline.addManual(right);activeManualId=right.id;manualChanged("현재 시점에서 구간을 나눴습니다. 앞뒤 박스는 독립적으로 편집됩니다.");}
    private void undoManualEdit(){if(manualUndo.isEmpty()||busy||playing)return;ManualUndo state=manualUndo.removeLast();timeline.restoreManual(state.tracks);activeManualId=state.active;manualChanged("수동 편집을 되돌렸습니다.");}
    private void manualChanged(String message){refreshManualUi();updateControls();invalidateTimeline();frame.invalidate();setStatus(message);}
    private void zoomTimeline(double factor){if(durationMs<=0)return;viewport.reveal(currentMs);viewport.zoom(factor,Math.max(0,Math.min(1,viewport.fraction(currentMs))));updateZoom();invalidateTimeline();}
    private void updateZoom(){if(zoomLabel!=null)zoomLabel.setText(String.format(Locale.KOREA,"타임라인 %.1f×",Math.max(1,durationMs/viewport.span())));}
    private long playheadMs(){return scrubbing?scrubTargetMs:currentMs;}
    private void invalidateTimeline(){if(band!=null)band.invalidate();if(filmstrip!=null)filmstrip.invalidate();}
    private void cancelLiveScrub(){if(!scrubbing)return;scrubbing=false;scrubSession++;if(scrubber!=null)scrubber.clear();}
    private void requestLiveScrub(long requested){
        if(busy||sourceUri==null||durationMs<=0)return;
        if(!scrubbing){
            stopPlayer(false);generation.incrementAndGet();scrubbing=true;scrubSession++;previewReady=false;visibleFaces=Collections.emptyList();
            updateControls();setStatus("영상 띠를 앞뒤로 움직여 장면을 찾으세요. 손을 뗀 뒤 박스를 조절하면 지점이 저장됩니다.");
        }
        scrubTargetMs=Math.max(0,Math.min(durationMs-1,requested));
        clock.setText(fmt(scrubTargetMs)+" / "+fmt(durationMs));seek.setProgress((int)(10000*scrubTargetMs/Math.max(1,durationMs)));invalidateTimeline();
        if(scrubber==null)scrubber=new FrameScrubber(this,sourceUri,new FrameScrubber.Listener(){
            public void frame(int session,long ms,Bitmap bitmap){
                if(destroyed||!scrubbing||session!=scrubSession){bitmap.recycle();return;}
                currentMs=ms;displayedFrameMs=ms;frame.setFrame(bitmap);invalidateTimeline();
            }
            public void failed(int session,String message){if(!destroyed&&scrubbing&&session==scrubSession)setStatus("장면을 읽는 중입니다. 손을 떼면 선택한 시점을 표시합니다.");}
        });
        scrubber.request(scrubSession,scrubTargetMs);
    }
    private void finishLiveScrub(long ms){cancelLiveScrub();preview(ms,null);}
    private void releaseScrubber(){cancelLiveScrub();if(scrubber!=null){scrubber.close();scrubber=null;}}
    interface IntValue{void set(int n);}
    private SeekBar.OnSeekBarChangeListener slider(IntValue action){return new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int p,boolean user){action.set(p);}};}
    private String fmt(long ms){return String.format(Locale.KOREA,"%02d:%02d.%03d",ms/60000,(ms/1000)%60,ms%1000);}
    private void setStatus(String s){if(!destroyed)status.setText(s);}
    private void updateControls(){
        boolean ready=sourceUri!=null&&!busy&&!scrubbing;open.setEnabled(!busy);play.setEnabled(ready);play.setText(playing?"Ⅱ 정지":"▶ 재생");
        track.setEnabled(ready&&!playing&&!timeline.manualOnly&&(timeline.allFaces||selected!=null));save.setEnabled(ready&&!playing&&timeline.canExport());
        selectedMode.setEnabled(!busy&&!scrubbing);allMode.setEnabled(!busy&&!scrubbing);manualMode.setEnabled(!busy&&!scrubbing);manual.setEnabled(ready&&!playing&&!timeline.manualOnly&&!timeline.allFaces&&previewReady);review.setEnabled(ready&&!playing&&timeline.analysedEndMs>0);seek.setEnabled(sourceUri!=null&&!busy);rangeStart.setEnabled(ready&&!playing);rangeEnd.setEnabled(ready&&!playing);log.setEnabled(ready&&!playing);strengthSeek.setEnabled(!busy);marginSeek.setEnabled(!busy);
        stepBack.setEnabled(ready&&!playing&&previewReady);stepNext.setEnabled(ready&&!playing&&previewReady);stop.setVisibility(busy?View.VISIBLE:View.GONE);modeHint.setText(timeline.manualOnly?"얼굴·번호판 등 원하는 곳을 직접 지정합니다.":timeline.allFaces?"장면이 바뀌면 보이는 얼굴을 새로 검출합니다.":"가릴 얼굴을 터치 · 장면에서 놓치면 다시 터치해 연결");
        ManualTrack active=activeManual();
        addManual.setEnabled(ready&&!playing&&previewReady);correctManual.setEnabled(ready&&!playing&&previewReady&&!timeline.manualOnly&&!timeline.allFaces&&timeline.analysedEndMs>0);
        manualPicker.setEnabled(ready&&!playing);manualTools.setVisibility(active!=null||!manualUndo.isEmpty()?View.VISIBLE:View.GONE);
        boolean edit=ready&&!playing&&previewReady&&active!=null;for(Button b:new Button[]{previousPoint,nextPoint,pinPoint,deletePoint,splitManual,deleteManual})b.setEnabled(edit);
        undoManual.setEnabled(ready&&!playing&&!manualUndo.isEmpty());
        if(active!=null)pointLabel.setText((active.replacesSelected?"자동 보정 ":"수동 박스 ")+active.id+" · "+active.size()+"개 지점 · "+fmt(active.start())+" ~ "+fmt(active.end()));else pointLabel.setText("수동 편집을 되돌릴 수 있습니다.");
        rangeLabel.setText((active==null?"자동 적용 구간  ":"선택 박스 구간  ")+fmt(active==null?timeline.startMs:active.start())+" ~ "+fmt(active==null?timeline.endMs:active.end()));
    }
    private void setRange(long start,long end){if(busy||end<=start||end>durationMs){setStatus("시작보다 뒤쪽에 종료 시점을 지정해 주세요.");return;}stopPlayer(false);ManualTrack t=activeManual();if(t!=null){rememberManual();t.range(start,end,durationMs);updateControls();invalidateTimeline();frame.invalidate();setStatus("수동 적용 구간을 변경했습니다. 지점은 유지됩니다.");return;}timeline.resetAuto();timeline.startMs=start;timeline.endMs=end;selected=null;anchorMs=-1;invalidateTimeline();preview(start,"자동 구간을 변경했습니다. 수동 지점은 유지됩니다. 얼굴을 선택한 뒤 자동 추적해 주세요.");}
    private void openVideo(){stopPlayer(false);Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,OPEN);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(req==SAVE_VIDEO){export(uri);return;}if(req==SAVE_LOG){writeLog(uri);return;}if(req!=OPEN)return;
        cancelLiveScrub();if(scrubber!=null){scrubber.close();scrubber=null;}filmstrip.reset();generation.incrementAndGet();busy=true;updateControls();setStatus("영상을 불러오는 중…");
        worker.execute(()->{try{MediaMetadataRetriever next=new MediaMetadataRetriever();next.setDataSource(this,uri);long length=Long.parseLong(next.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));int w=Integer.parseInt(next.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),h=Integer.parseInt(next.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));String rotationText=next.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);int rotation=rotationText==null?0:Integer.parseInt(rotationText);if(length<=0)throw new IOException("영상 길이를 읽지 못했습니다.");if(retriever!=null)retriever.release();retriever=next;
            runOnUiThread(()->{if(destroyed)return;sourceUri=uri;durationMs=length;videoWidth=rotation%180==0?w:h;videoHeight=rotation%180==0?h:w;timeline.reset();timeline.startMs=0;timeline.endMs=length;currentMs=0;displayedFrameMs=-1;anchorMs=-1;selected=null;activeManualId=-1;nextManualId=1;manualUndo.clear();creatingManual=creatingCorrection=redrawingManual=false;viewport.reset(length);updateZoom();refreshManualUi();busy=false;previewReady=false;progress.setProgress(0);updateControls();invalidateTimeline();preview(0,null);});
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
    private void preview(long requested,String message){if(sourceUri==null||busy)return;cancelLiveScrub();stopPlayer(false);long ms=Math.max(0,Math.min(durationMs-1,requested));currentMs=ms;previewReady=false;int token=generation.incrementAndGet();updateControls();worker.execute(()->{if(token!=generation.get()||destroyed)return;Bitmap b=null;try{b=bitmapAt(ms);if(b==null)throw new IOException("프레임 없음");List<FacePath.Box> faces=(timeline.manualOnly||activeManualId>=0)?Collections.emptyList():detect(b,!timeline.allFaces);Bitmap image=b;runOnUiThread(()->{if(destroyed||token!=generation.get()){image.recycle();return;}visibleFaces=faces;displayedFrameMs=ms;frame.setFrame(image);previewReady=true;seek.setProgress((int)(10000*ms/Math.max(1,durationMs)));clock.setText(fmt(ms)+" / "+fmt(durationMs));viewport.reveal(ms);invalidateTimeline();updateControls();setStatus(message!=null?message:((timeline.manualOnly||activeManual()!=null)?"박스 안을 끌어 이동 · 모서리로 크기 조절 · 놓으면 지점 저장":((timeline.endMs>0&&timeline.analysedEndMs>=timeline.endMs)?"추적 완료 · 재생으로 확인 후 MP4 저장":faces.isEmpty()?"얼굴이 검출되지 않았습니다. 다른 시점 또는 직접 영역 지정을 사용해 주세요.":timeline.allFaces?faces.size()+"명 검출 · 자동 추적을 눌러주세요.":"가릴 얼굴을 터치해 선택하세요.")));});}catch(Exception e){if(b!=null)b.recycle();runOnUiThread(()->setStatus("미리보기 오류: "+e.getMessage()));}});}
    private void select(FacePath.Box box,boolean manualAnchor){if(busy||playing||!previewReady||timeline.manualOnly||timeline.allFaces)return;if(currentMs<timeline.startMs||currentMs>=timeline.endMs){setStatus("적용 구간 안에서 얼굴을 선택해 주세요.");return;}if(timeline.analysedEndMs==0&&currentMs>timeline.startMs)timeline.startMs=currentMs;timeline.truncate(currentMs);selected=box;anchorMs=currentMs;if(manualAnchor)path.anchorManual(currentMs,box);else path.anchor(currentMs,box);selectingManually=false;manual.setText("직접 영역 지정");frame.invalidate();invalidateTimeline();updateControls();setStatus("얼굴 선택 완료 · 자동 추적을 누르면 이 시점부터 연결합니다.");}
    private void analyse(){if(busy||timeline.manualOnly||sourceUri==null||(!timeline.allFaces&&selected==null))return;releaseScrubber();stopPlayer(false);generation.incrementAndGet();cancel.set(false);busy=true;updateControls();progress.setProgress(0);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setStatus("자동 추적 중…");final boolean all=timeline.allFaces;final long start=all?timeline.startMs:anchorMs,end=timeline.endMs;final FacePath.Box anchor=selected;
        worker.execute(()->{long begun=SystemClock.elapsedRealtime(),lastUi=0,lastKey=start,processed=start;SequentialFrames reader=null;analysisElapsedMs=decodeMs=inferenceMs=0;sampled=detections=0;SceneCuts cuts=new SceneCuts();ContinuityBridge continuity=new ContinuityBridge(path,new LazyFlowBridge(new OpticalBridge()));diagnostics.begin(start);boolean complete=false;
            try{
                if(all)timeline.resetAuto();else timeline.truncate(start);
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
    private void finishWork(long at,String text){if(destroyed)return;busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);progress.setProgress(timeline.analysedEndMs>=timeline.endMs?100:0);updateControls();invalidateTimeline();preview(at,text);}
    private void togglePlayer(){if(playing){stopPlayer(true);return;}if(sourceUri==null||busy)return;releaseScrubber();generation.incrementAndGet();if(currentMs>=durationMs-100)currentMs=0;playing=true;frame.setVisibility(View.INVISIBLE);player=new MosaicPlayer(this,sourceUri,timeline,currentMs,videoWidth,videoHeight,new MosaicPlayer.Events(){public void ready(){setStatus("소리와 함께 재생 중 · 모자이크 누락을 확인하세요.");tickPlayer();}public void finished(){stopPlayer(false);preview(Math.max(0,durationMs-50),"재생 완료 · 확인 후 MP4 저장을 눌러주세요.");}public void failed(String m){stopPlayer(false);preview(currentMs,"재생 오류: "+m);}});stage.addView(player,new FrameLayout.LayoutParams(-1,-1));updateControls();setStatus("재생 준비 중…");}
    private void tickPlayer(){if(!playing||player==null)return;currentMs=player.position();seek.setProgress((int)(10000*currentMs/Math.max(1,durationMs)));clock.setText(fmt(currentMs)+" / "+fmt(durationMs));viewport.reveal(currentMs);invalidateTimeline();ui.postDelayed(this::tickPlayer,50);}
    private void stopPlayer(boolean reload){if(!playing)return;playing=false;ui.removeCallbacksAndMessages(null);if(player!=null){currentMs=player.position();player.release();stage.removeView(player);player=null;}frame.setVisibility(View.VISIBLE);updateControls();if(reload)preview(currentMs,null);}
    private void requestExport(){if(busy||!timeline.canExport())return;stopPlayer(false);String message=timeline.manualOnly?"수동으로 지정한 위치·크기·적용 구간을 그대로 저장합니다. 재생으로 지점 사이의 움직임을 확인해 주세요.":timeline.allFaces?"전체 얼굴 모드도 옆얼굴·가려진 얼굴을 놓칠 수 있습니다. 재생으로 확인한 결과를 MP4로 저장합니다.":"가림 없는 샘플 "+path.uncoveredCount()+"개가 있습니다. 선택 얼굴이 장면에서 끊기면 다시 선택해 연결할 수 있습니다. 현재 보이는 결과대로 저장합니다.";new AlertDialog.Builder(this).setTitle("모자이크 결과 저장").setMessage(message).setNegativeButton("더 확인",null).setPositiveButton("MP4 저장",(d,w)->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("video/mp4").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"LEDOA_MOSAIC_"+System.currentTimeMillis()+".mp4");startActivityForResult(i,SAVE_VIDEO);}).show();}
    private void export(Uri destination){if(busy)return;releaseScrubber();generation.incrementAndGet();busy=true;cancel.set(false);progress.setProgress(0);updateControls();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setStatus("MP4 저장 중…");worker.execute(()->{File temporary=new File(getCacheDir(),"mosaic-export.mp4");try{MosaicExport.Result r=MosaicExport.run(this,sourceUri,temporary,timeline,cancel,p->runOnUiThread(()->{progress.setProgress(p);setStatus("MP4 저장 "+p+"%");}));if(cancel.get())throw new InterruptedException("저장을 취소했습니다.");try(InputStream in=new FileInputStream(temporary);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new IOException("저장 위치를 열지 못했습니다.");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(cancel.get())throw new InterruptedException("저장을 취소했습니다.");out.write(buffer,0,n);}}runOnUiThread(()->{finishWork(currentMs,"MP4 저장 완료 · "+r.width+"×"+r.height+(r.audio?" · 원본 소리 포함":" · 원본에 소리 없음"));new AlertDialog.Builder(this).setTitle("저장 완료").setMessage("선택한 위치에 MP4 파일을 저장했습니다.").setNegativeButton("닫기",null).setPositiveButton("영상 열기",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(destination,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){setStatus("파일 앱에서 저장한 영상을 열어주세요.");}}).show();});}catch(Throwable e){lastError=e.toString();try{DocumentsContract.deleteDocument(getContentResolver(),destination);}catch(Exception ignored){}runOnUiThread(()->finishWork(currentMs,"저장 실패: "+e.getMessage()));}finally{temporary.delete();}});}
    private void writeLog(Uri uri){try{JSONObject root=new JSONObject();root.put("version","0.9.3");root.put("identityModel","OpenCV_SFace_INT8_local");root.put("mode",timeline.allFaces?"ALL_FACES":"SELECTED_FACE");root.put("durationMs",durationMs);root.put("rangeStartMs",timeline.startMs);root.put("rangeEndMs",timeline.endMs);root.put("analysedEndMs",timeline.analysedEndMs);root.put("analysisElapsedMs",analysisElapsedMs);root.put("decodeMs",decodeMs);root.put("faceInferenceMs",inferenceMs);root.put("faceInferenceAttempts",detections);root.put("sampledFrames",sampled);root.put("decoderMode",decoderMode);root.put("lastError",lastError);root.put("sceneCuts",new JSONArray(timeline.cuts()));root.put("strength",timeline.strength);root.put("margin",timeline.margin);JSONArray points=new JSONArray();if(!timeline.allFaces)for(FacePath.Point p:path.points()){JSONObject j=new JSONObject();j.put("ms",p.ms);j.put("status",p.status.name());j.put("reason",p.reason);if(p.box!=null)boxJson(j,p.box);points.put(j);}else for(Map.Entry<Long,List<FacePath.Box>> e:timeline.samples().entrySet()){JSONObject j=new JSONObject();j.put("ms",e.getKey());JSONArray faces=new JSONArray();for(FacePath.Box b:e.getValue()){JSONObject f=new JSONObject();boxJson(f,b);faces.put(f);}j.put("faces",faces);points.put(j);}root.put("points",points);root.put("manualOnly",timeline.manualOnly);JSONArray manualData=new JSONArray();for(ManualTrack t:timeline.manualTracks()){JSONObject j=new JSONObject();j.put("id",t.id);j.put("replacesSelected",t.replacesSelected);j.put("startMs",t.start());j.put("endMs",t.end());JSONArray keys=new JSONArray();for(Map.Entry<Long,FacePath.Box> e:t.points().entrySet()){JSONObject k=new JSONObject();k.put("ms",e.getKey());boxJson(k,e.getValue());keys.put(k);}j.put("keyframes",keys);manualData.put(j);}root.put("manualTracks",manualData);try(OutputStream o=getContentResolver().openOutputStream(uri,"wt")){o.write(root.toString(2).getBytes(StandardCharsets.UTF_8));}setStatus("진단 기록을 저장했습니다.");}catch(Exception e){setStatus("진단 저장 오류: "+e.getMessage());}}
    private void boxJson(JSONObject j,FacePath.Box b)throws JSONException{j.put("x",b.x);j.put("y",b.y);j.put("w",b.w);j.put("h",b.h);}
    @Override protected void onPause(){if(scrubbing)finishLiveScrub(scrubTargetMs);stopPlayer(false);super.onPause();}
    @Override protected void onDestroy(){destroyed=true;cancelLiveScrub();if(scrubber!=null)scrubber.close();filmstrip.reset();generation.incrementAndGet();cancel.set(true);stopPlayer(false);worker.execute(()->{detector.close();if(identity!=null)identity.close();if(retriever!=null)try{retriever.release();}catch(Exception ignored){}});worker.shutdown();if(frame.bitmap!=null)frame.bitmap.recycle();super.onDestroy();}
    private final class FilmstripView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final ArrayList<Long> desired=new ArrayList<>();
        private final HashSet<Long> failed=new HashSet<>();
        private final android.util.LruCache<Long,Bitmap> cache=new android.util.LruCache<Long,Bitmap>(48){
            protected void entryRemoved(boolean evicted,Long key,Bitmap old,Bitmap replacement){if(old!=replacement&&!old.isRecycled())old.recycle();}
        };
        private final ScaleGestureDetector scale;
        private volatile int thumbnailEpoch;
        private boolean loading,multi;
        FilmstripView(){
            super(MainActivity.this);setContentDescription("영상 썸네일 타임라인: 좌우로 끌면 재생하지 않고 장면을 확인합니다");
            scale=new ScaleGestureDetector(MainActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                public boolean onScale(ScaleGestureDetector d){viewport.zoom(d.getScaleFactor(),fraction(d.getFocusX()));updateZoom();invalidateTimeline();return true;}
            });
        }
        private float fraction(float px){return (px-dp(14))/Math.max(1,getWidth()-dp(28));}
        private long key(float f){long ms=viewport.time(f);return Math.max(0,Math.min(durationMs-1,Math.round(ms/50.0)*50));}
        void reset(){thumbnailEpoch++;loading=false;desired.clear();failed.clear();cache.evictAll();invalidate();}
        private void loadNext(){
            if(loading||destroyed||busy||playing||scrubbing||sourceUri==null||retriever==null)return;
            Long missing=null;for(long at:desired)if(cache.get(at)==null&&!failed.contains(at)){missing=at;break;}if(missing==null)return;
            final long at=missing;final int epoch=thumbnailEpoch;loading=true;
            worker.execute(()->{
                Bitmap image=null;boolean tried=false;
                try{if(!destroyed&&!busy&&epoch==thumbnailEpoch){tried=true;image=retriever.getScaledFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST,160,96);}}catch(Exception ignored){}
                final Bitmap result=image;final boolean attempted=tried;
                runOnUiThread(()->{
                    if(destroyed||epoch!=thumbnailEpoch){if(result!=null)result.recycle();return;}
                    loading=false;
                    if(result!=null)cache.put(at,result);else if(attempted&&!busy&&!playing&&!scrubbing)failed.add(at);
                    invalidate();
                });
            });
        }
        protected void onDraw(Canvas c){
            super.onDraw(c);paint.setColor(PANEL);c.drawRoundRect(0,0,getWidth(),getHeight(),dp(6),dp(6),paint);
            if(durationMs<=0){paint.setColor(MUTED);paint.setTextSize(dp(12));c.drawText("영상을 열면 장면 미리보기가 표시됩니다",dp(14),dp(30),paint);return;}
            float tileWidth=dp(44)*Math.max(.6f,Math.min(1.8f,videoWidth/(float)Math.max(1,videoHeight)));int cells=Math.max(4,(int)Math.ceil((getWidth()-dp(28))/tileWidth));float width=(getWidth()-dp(28))/(float)cells;desired.clear();
            for(int i=0;i<cells;i++){
                long at=key((i+.5f)/cells);if(!desired.contains(at))desired.add(at);
                RectF cell=new RectF(dp(14)+i*width,dp(3),dp(14)+(i+1)*width-dp(1),getHeight()-dp(3));Bitmap bitmap=cache.get(at);
                if(bitmap==null||bitmap.isRecycled()){paint.setColor(0xff27394c);c.drawRect(cell,paint);paint.setColor(MUTED);paint.setTextSize(dp(9));c.drawText(String.format(Locale.KOREA,"%.1fs",at/1000.),cell.left+dp(3),cell.centerY()+dp(3),paint);}
                else{
                    float fit=Math.max(cell.width()/bitmap.getWidth(),cell.height()/bitmap.getHeight());float w=bitmap.getWidth()*fit,h=bitmap.getHeight()*fit;
                    c.save();c.clipRect(cell);c.drawBitmap(bitmap,null,new RectF(cell.centerX()-w/2,cell.centerY()-h/2,cell.centerX()+w/2,cell.centerY()+h/2),paint);c.restore();
                }
            }
            float cursor=dp(14)+viewport.fraction(playheadMs())*(getWidth()-dp(28));paint.setColor(Color.WHITE);c.drawRect(cursor-dp(1),0,cursor+dp(1),getHeight(),paint);
            paint.setColor(BLUE);c.drawCircle(cursor,dp(4),dp(4),paint);post(this::loadNext);
        }
        public boolean performClick(){super.performClick();return true;}
        public boolean onTouchEvent(MotionEvent e){
            if(busy||sourceUri==null||durationMs<=0)return true;scale.onTouchEvent(e);int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){multi=false;getParent().requestDisallowInterceptTouchEvent(true);requestLiveScrub(viewport.time(Math.max(0,Math.min(1,fraction(e.getX())))));return true;}
            if(action==MotionEvent.ACTION_POINTER_DOWN){multi=true;if(scrubbing)finishLiveScrub(scrubTargetMs);return true;}
            if(action==MotionEvent.ACTION_MOVE){
                if(multi||e.getPointerCount()>1||scale.isInProgress())return true;
                float f=fraction(e.getX());if(f<0||f>1){viewport.pan(f<0?-.04f:.04f);updateZoom();}
                requestLiveScrub(viewport.time(Math.max(0,Math.min(1,f))));return true;
            }
            if(action==MotionEvent.ACTION_CANCEL){if(scrubbing)finishLiveScrub(scrubTargetMs);multi=false;return true;}
            if(action==MotionEvent.ACTION_UP){performClick();if(!multi)finishLiveScrub(viewport.time(Math.max(0,Math.min(1,fraction(e.getX())))));multi=false;invalidateTimeline();return true;}
            return true;
        }
    }
    private final class BandView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scale;
        private float lastX,downX;
        private int gesture;
        private boolean multi,moved;
        private long dragStart,dragEnd,tappedPoint=-1;
        BandView(){
            super(MainActivity.this);setContentDescription("수동 모자이크 타임라인: 지점 선택, 양 끝 구간 조절, 확대 축소");
            scale=new ScaleGestureDetector(MainActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                public boolean onScale(ScaleGestureDetector d){viewport.zoom(d.getScaleFactor(),fraction(d.getFocusX()));updateZoom();invalidateTimeline();return true;}
            });
        }
        private float fraction(float x){return (x-dp(14))/Math.max(1,getWidth()-dp(28));}
        private float x(long ms){return dp(14)+viewport.fraction(ms)*Math.max(1,getWidth()-dp(28));}
        protected void onDraw(Canvas c){
            super.onDraw(c);paint.setColor(PANEL);c.drawRoundRect(0,0,getWidth(),getHeight(),dp(6),dp(6),paint);if(durationMs<=0)return;
            paint.setTextSize(dp(10));paint.setColor(MUTED);
            for(int i=0;i<5;i++){long ms=viewport.boundary(i/4f);float at=dp(14)+i*(getWidth()-dp(28))/4f;String text=String.format(Locale.KOREA,"%.2fs",ms/1000.);float width=paint.measureText(text);c.drawText(text,Math.max(dp(2),Math.min(getWidth()-width-dp(2),at-width/2)),dp(14),paint);}
            c.save();c.clipRect(dp(7),dp(16),getWidth()-dp(7),getHeight());
            if(!timeline.manualOnly){
                if(timeline.allFaces){for(Map.Entry<Long,List<FacePath.Box>> e:timeline.samples().entrySet()){float l=x(e.getKey()),r=x(e.getKey()+100);if(r<0||l>getWidth())continue;paint.setColor(e.getValue().isEmpty()?0xffe4aa54:0xff50c4b1);c.drawRect(l,dp(67),r,dp(74),paint);}}
                else for(FacePath.Point p:path.points()){float l=x(p.ms),r=x(p.ms+100);if(r<0||l>getWidth())continue;paint.setColor(p.box==null?0xffec7869:p.reason.contains("REVIEW")||p.status==FacePath.Status.FLOW_ESTIMATED?0xffe4aa54:0xff50c4b1);c.drawRect(l,dp(67),r,dp(74),paint);}
            }
            ManualTrack active=activeManual();
            if(active!=null){
                long start=gesture==1?dragStart:active.start(),end=gesture==2?dragEnd:active.end();float l=x(start),r=x(end);
                paint.setColor(BLUE);c.drawRoundRect(l,dp(30),r,dp(61),dp(5),dp(5),paint);
                paint.setColor(Color.WHITE);for(float handle:new float[]{l,r})if(handle>=dp(10)&&handle<=getWidth()-dp(10))c.drawRoundRect(handle-dp(5),dp(26),handle+dp(5),dp(65),dp(3),dp(3),paint);
                paint.setColor(Color.WHITE);c.drawRect(x(playheadMs())-dp(1),dp(22),x(playheadMs())+dp(1),getHeight(),paint);
                int number=0;float lastLabel=-1000;paint.setTextSize(dp(10));
                for(Map.Entry<Long,FacePath.Box> e:active.points().entrySet()){
                    if(e.getKey()<start||e.getKey()>=end)continue;number++;float at=x(e.getKey());if(at<0||at>getWidth())continue;
                    paint.setColor(PANEL);c.drawCircle(at,dp(46),dp(7),paint);
                    paint.setColor(Math.abs(e.getKey()-playheadMs())<=frameStepMs/2?0xffffd387:Color.WHITE);c.drawCircle(at,dp(46),dp(4.5f),paint);
                    if(at-lastLabel>dp(22)){paint.setColor(PANEL);c.drawRoundRect(at-dp(8),dp(16),at+dp(8),dp(29),dp(3),dp(3),paint);paint.setColor(Color.WHITE);String numberText=String.valueOf(number);c.drawText(numberText,at-paint.measureText(numberText)/2,dp(26),paint);lastLabel=at;}
                }
            }else{
                paint.setColor(MUTED);paint.setTextSize(dp(12));c.drawText(timeline.manualTracks().isEmpty()?"수동 박스를 추가하면 지점이 표시됩니다":"목록에서 수동 박스를 선택하세요",dp(16),dp(48),paint);
            }
            paint.setColor(0xffbbbbbb);for(long ms:timeline.cuts())c.drawRect(x(ms),dp(67),x(ms)+dp(1),dp(76),paint);
            if(active==null){paint.setColor(Color.WHITE);c.drawRect(x(playheadMs())-dp(1),dp(22),x(playheadMs())+dp(1),getHeight(),paint);}c.restore();
        }
        public boolean performClick(){super.performClick();return true;}
        public boolean onTouchEvent(MotionEvent e){
            if(busy||durationMs<=0)return true;scale.onTouchEvent(e);int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                stopPlayer(false);getParent().requestDisallowInterceptTouchEvent(true);lastX=downX=e.getX();multi=moved=false;gesture=0;tappedPoint=-1;
                ManualTrack t=activeManual();
                if(t!=null&&e.getY()>dp(23)){
                    dragStart=t.start();dragEnd=t.end();float a=x(dragStart),b=x(dragEnd);
                    if(a>=0&&a<=getWidth()&&Math.abs(e.getX()-a)<dp(18))gesture=1;
                    else if(b>=0&&b<=getWidth()&&Math.abs(e.getX()-b)<dp(18))gesture=2;
                    else for(long ms:t.points().keySet())if(t.contains(ms)&&Math.abs(e.getX()-x(ms))<dp(12)){tappedPoint=ms;break;}
                }
                if(gesture==0&&e.getY()<dp(24))gesture=3;invalidate();return true;
            }
            if(action==MotionEvent.ACTION_POINTER_DOWN){multi=true;gesture=0;if(scrubbing)finishLiveScrub(scrubTargetMs);return true;}
            if(action==MotionEvent.ACTION_MOVE){
                if(e.getPointerCount()>1||multi||scale.isInProgress())return true;
                if(Math.abs(e.getX()-downX)>dp(5))moved=true;
                if(gesture==3){viewport.pan((lastX-e.getX())/Math.max(1,getWidth()-dp(28)));updateZoom();filmstrip.invalidate();}
                else if(gesture==1)dragStart=Math.max(0,Math.min(dragEnd-1,viewport.boundary(Math.max(0,Math.min(1,fraction(e.getX()))))));
                else if(gesture==2)dragEnd=Math.min(durationMs,Math.max(dragStart+1,viewport.boundary(Math.max(0,Math.min(1,fraction(e.getX()))))));
                if(gesture==0&&moved)requestLiveScrub(viewport.time(Math.max(0,Math.min(1,fraction(e.getX())))));
                lastX=e.getX();invalidate();return true;
            }
            if(action==MotionEvent.ACTION_CANCEL){gesture=0;multi=false;if(scrubbing)finishLiveScrub(scrubTargetMs);invalidate();return true;}
            if(action!=MotionEvent.ACTION_UP)return true;
            performClick();ManualTrack t=activeManual();
            if(!multi){
                if((gesture==1||gesture==2)&&moved&&t!=null){rememberManual();t.range(dragStart,dragEnd,durationMs);manualChanged("적용 구간 변경 · 지점은 유지됩니다.");}
                else if(gesture!=3||!moved){long at=tappedPoint>=0&&!moved?tappedPoint:viewport.time(Math.max(0,Math.min(1,fraction(e.getX()))));if(scrubbing)finishLiveScrub(at);else preview(at,null);}
            }
            gesture=0;multi=false;invalidate();return true;
        }
    }
    private final class FrameView extends View {
        private Bitmap bitmap;
        private final Paint image=new Paint(Paint.FILTER_BITMAP_FLAG),pen=new Paint(Paint.ANTI_ALIAS_FLAG),pixel=new Paint();
        private final RectF area=new RectF();
        private float downX,downY,endX,endY;
        private int dragMode;
        private FacePath.Box dragBase,ghost;
        FrameView(){super(MainActivity.this);pixel.setFilterBitmap(false);setContentDescription("모자이크 박스 편집: 안쪽 드래그로 이동, 네 모서리 드래그로 크기 조절");}
        void setFrame(Bitmap b){if(bitmap!=null&&bitmap!=b)bitmap.recycle();bitmap=b;ghost=null;dragMode=0;invalidate();}
        RectF rect(FacePath.Box b){return new RectF(area.left+b.x*area.width(),area.top+b.y*area.height(),area.left+(b.x+b.w)*area.width(),area.top+(b.y+b.h)*area.height());}
        private long shownMs(){return displayedFrameMs>=0?displayedFrameMs:currentMs;}
        private FacePath.Box editable(){ManualTrack t=activeManual();return t==null?null:t.at(shownMs(),timeline.cuts());}
        protected void onDraw(Canvas c){
            super.onDraw(c);if(bitmap==null||bitmap.isRecycled()){pen.setColor(MUTED);pen.setTextSize(dp(16));c.drawText("영상 열기로 시작하세요",dp(20),getHeight()/2f,pen);return;}
            float scale=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight()),w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
            area.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);c.drawBitmap(bitmap,null,area,image);
            List<FacePath.Box> masks=timeline.boxesAt(shownMs(),ghost!=null?activeManualId:-1);
            if(!timeline.manualOnly&&timeline.allFaces&&timeline.analysedEndMs==0){masks=new ArrayList<>(masks);masks.addAll(visibleFaces);}
            if(selected!=null&&shownMs()==anchorMs&&!timeline.manualOnly&&!timeline.allFaces&&timeline.autoBoxesAt(shownMs()).isEmpty()&&activeManualId<0){masks=new ArrayList<>(masks);masks.add(selected);}
            for(FacePath.Box b:masks)mosaic(c,b.expanded(timeline.margin/100f));if(ghost!=null)mosaic(c,ghost.expanded(timeline.margin/100f));
            pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(dp(1.5f));pen.setColor(BLUE);
            if(activeManualId<0&&!timeline.manualOnly&&!creatingManual)for(FacePath.Box b:visibleFaces)c.drawRoundRect(rect(b),dp(3),dp(3),pen);
            FacePath.Box edit=ghost!=null?ghost:editable();
            if(edit!=null){RectF r=rect(edit);pen.setColor(0xffffd387);pen.setStrokeWidth(dp(2));c.drawRect(r,pen);pen.setStyle(Paint.Style.FILL);for(float[] point:new float[][]{{r.left,r.top},{r.right,r.top},{r.left,r.bottom},{r.right,r.bottom}}){c.drawRoundRect(point[0]-dp(6),point[1]-dp(6),point[0]+dp(6),point[1]+dp(6),dp(2),dp(2),pen);}}
            else if(selected!=null&&shownMs()==anchorMs&&!timeline.manualOnly){pen.setStrokeWidth(dp(3));pen.setColor(0xffffd387);c.drawRect(rect(selected),pen);}
            if(dragMode==1){pen.setStyle(Paint.Style.STROKE);pen.setColor(Color.CYAN);c.drawRect(Math.min(downX,endX),Math.min(downY,endY),Math.max(downX,endX),Math.max(downY,endY),pen);}pen.setStyle(Paint.Style.FILL);
        }
        private void mosaic(Canvas c,FacePath.Box b){
            Rect crop=new Rect(Math.max(0,(int)(b.x*bitmap.getWidth())),Math.max(0,(int)(b.y*bitmap.getHeight())),Math.min(bitmap.getWidth(),(int)((b.x+b.w)*bitmap.getWidth())),Math.min(bitmap.getHeight(),(int)((b.y+b.h)*bitmap.getHeight())));
            if(crop.width()<2||crop.height()<2)return;int cols=timeline.columns(),rows=Math.max(2,Math.round(cols*crop.height()/(float)crop.width()));
            Bitmap small=Bitmap.createBitmap(cols,rows,Bitmap.Config.ARGB_8888);Canvas temp=new Canvas(small);temp.drawBitmap(bitmap,crop,new Rect(0,0,cols,rows),pixel);c.drawBitmap(small,null,rect(b),pixel);small.recycle();
        }
        private float nx(float x){return Math.max(0,Math.min(1,(x-area.left)/area.width()));}
        private float ny(float y){return Math.max(0,Math.min(1,(y-area.top)/area.height()));}
        private void dragTo(float x,float y){
            endX=Math.max(area.left,Math.min(area.right,x));endY=Math.max(area.top,Math.min(area.bottom,y));if(dragBase==null)return;
            float dx=(x-downX)/area.width(),dy=(y-downY)/area.height(),l=dragBase.x,t=dragBase.y,r=l+dragBase.w,b=t+dragBase.h;
            if(dragMode==2){l=Math.max(0,Math.min(1-dragBase.w,l+dx));t=Math.max(0,Math.min(1-dragBase.h,t+dy));r=l+dragBase.w;b=t+dragBase.h;}
            else{boolean left=dragMode==3||dragMode==5,top=dragMode==3||dragMode==4;if(left)l=Math.max(0,Math.min(r-.008f,nx(x)));else r=Math.min(1,Math.max(l+.008f,nx(x)));if(top)t=Math.max(0,Math.min(b-.008f,ny(y)));else b=Math.min(1,Math.max(t+.008f,ny(y)));}
            ghost=new FacePath.Box(l,t,r-l,b-t,-1);
        }
        public boolean performClick(){super.performClick();return true;}
        public boolean onTouchEvent(MotionEvent e){
            if(busy||playing||!previewReady||bitmap==null)return true;int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                downX=endX=e.getX();downY=endY=e.getY();ghost=null;dragBase=null;dragMode=0;
                FacePath.Box edit=editable();
                if(edit!=null&&!creatingManual&&!selectingManually&&!redrawingManual){
                    RectF r=rect(edit);float[][] corners={{r.left,r.top},{r.right,r.top},{r.left,r.bottom},{r.right,r.bottom}};float best=dp(25);
                    for(int i=0;i<corners.length;i++){float distance=(float)Math.hypot(e.getX()-corners[i][0],e.getY()-corners[i][1]);if(distance<best){dragMode=3+i;best=distance;}}
                    if(dragMode==0&&r.contains(e.getX(),e.getY()))dragMode=2;if(dragMode>=2)dragBase=edit;
                }else if((creatingManual||selectingManually||redrawingManual)&&area.contains(e.getX(),e.getY()))dragMode=1;
                if(dragMode>0)getParent().requestDisallowInterceptTouchEvent(true);return true;
            }
            if(action==MotionEvent.ACTION_MOVE&&dragMode>0){dragTo(e.getX(),e.getY());invalidate();return true;}
            if(action==MotionEvent.ACTION_CANCEL){dragMode=0;ghost=null;invalidate();return true;}
            if(action!=MotionEvent.ACTION_UP)return true;performClick();
            int mode=dragMode;if(mode>0)dragTo(e.getX(),e.getY());dragMode=0;
            if(mode==1){
                float x=nx(Math.min(downX,endX)),y=ny(Math.min(downY,endY)),w=Math.abs(endX-downX)/area.width(),h=Math.abs(endY-downY)/area.height();
                if(w<.008f||h<.008f){setStatus("가릴 영역을 조금 더 크게 드래그해 주세요.");invalidate();return true;}
                FacePath.Box box=new FacePath.Box(x,y,w,h,-1);
                if(redrawingManual){redrawingManual=false;commitManualBox(box);}else if(creatingManual)commitNewManual(box);
                else try{Rect r=new Rect((int)(x*bitmap.getWidth()),(int)(y*bitmap.getHeight()),(int)((x+w)*bitmap.getWidth()),(int)((y+h)*bitmap.getHeight()));select(new FacePath.Box(x,y,w,h,-1,appearance(bitmap,r),true,false),true);}catch(Exception ex){setStatus(ex.getMessage());}
                ghost=null;invalidate();return true;
            }
            if(mode>=2){FacePath.Box result=ghost;ghost=null;if(result!=null&&Math.hypot(e.getX()-downX,e.getY()-downY)>dp(1))commitManualBox(result);invalidate();return true;}
            if(activeManual()!=null){setStatus("박스 안을 끌어 이동하거나 네 모서리를 조절하세요. 구간 밖에서는 타임라인 양 끝을 늘려주세요.");return true;}
            if(timeline.manualOnly){setStatus("＋ 수동 박스를 눌러 얼굴·번호판을 감싸 주세요.");return true;}
            if(timeline.allFaces||!area.contains(e.getX(),e.getY()))return true;
            float x=nx(e.getX()),y=ny(e.getY());FacePath.Box picked=null;for(FacePath.Box b:visibleFaces)if(b.expanded(.08f).contains(x,y)&&(picked==null||b.area()<picked.area()))picked=b;
            if(picked!=null)select(picked,false);else setStatus("검출된 얼굴을 터치하거나 직접 영역 지정을 사용해 주세요.");return true;
        }
    }
}

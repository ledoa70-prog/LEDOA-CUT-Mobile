package kr.ledoa.cut;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.AudioFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.speech.RecognitionListener;
import android.speech.RecognitionPart;
import android.speech.RecognitionService;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Adds speech without replacing the original editor's file picker, save bridge or playback. */
public class CaptionActivity extends MainActivity {
    private static final int AUDIO_PERMISSION=4811;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private WebView web;private SharedPreferences sources;
    private SpeechRecognizer recognizer;private ParcelFileDescriptor sourceDescriptor,writeDescriptor;
    private Future<?> preparation;private Thread feeder;private File pcm;
    private FileInputStream pipeProbe;private volatile boolean fileAudioWritten=false;
    private JSONObject request;private String pendingPermission;
    private volatile int generation=0;private volatile boolean active=false;private boolean receivedSegments=false;
    private final ArrayList<JSONObject> segments=new ArrayList<JSONObject>();
    private JSONObject lastSelection=new JSONObject();
    private Runnable timeout;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);sources=getSharedPreferences("caption-source-uris-v1",MODE_PRIVATE);
        web=findWeb(getWindow().getDecorView());
        if(web!=null)web.addJavascriptInterface(new Bridge(),"LedoaCaptions");
        File folder=new File(getCacheDir(),"caption-pcm");folder.mkdirs();
        File[] stale=folder.listFiles();if(stale!=null)for(File file:stale)file.delete();
    }
    private WebView findWeb(View v){if(v instanceof WebView)return (WebView)v;if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){WebView found=findWeb(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private boolean trusted(){return web!=null&&web.getUrl()!=null&&web.getUrl().startsWith("file:///android_asset/");}
    private void send(JSONObject event){
        main.post(()->{if(trusted())web.evaluateJavascript("window.LEDOA_CAPTION_EVENT&&window.LEDOA_CAPTION_EVENT("+event.toString()+")",null);});
    }
    private JSONObject event(String type,String id){JSONObject e=new JSONObject();try{e.put("type",type);e.put("id",id);}catch(Exception ignored){}return e;}
    private void progress(int token,String status,int percent){if(token!=generation||!active)return;try{JSONObject e=event("progress",request.optString("id"));e.put("message",status);e.put("percent",percent);send(e);}catch(Exception ignored){}}

    @Override public void onActivityResult(int code,int result,Intent data){
        if(result==Activity.RESULT_OK&&data!=null){
            if(data.getData()!=null)register(data.getData(),data.getFlags());
            ClipData clips=data.getClipData();if(clips!=null)for(int i=0;i<clips.getItemCount();i++)register(clips.getItemAt(i).getUri(),data.getFlags());
        }
        super.onActivityResult(code,result,data);
    }
    private synchronized void register(Uri uri,int flags){
        if(uri==null||!"content".equals(uri.getScheme()))return;
        try {
            if((flags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0)
                try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            String name="";long size=-1;
            try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                if(c!=null&&c.moveToFirst()){name=c.getString(0);size=c.isNull(1)?-1:c.getLong(1);}
            }
            if(name==null||name.length()==0||size<0)return;
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder id=new StringBuilder();for(byte b:digest)id.append(String.format("%02x",b&255));
            JSONObject record=new JSONObject();record.put("sourceId",id.toString());record.put("uri",uri.toString());record.put("name",name);record.put("size",size);
            sources.edit().putString(id.toString(),record.toString()).apply();
            lastSelection=new JSONObject(record.toString());lastSelection.remove("uri");
        }catch(Exception ignored){}
    }
    private synchronized JSONObject resolve(String name,long size){
        JSONObject answer=new JSONObject();JSONArray hits=new JSONArray();
        try{
            for(Object value:sources.getAll().values()){
                try{JSONObject r=new JSONObject((String)value);if(name.equals(r.optString("name"))&&size==r.optLong("size",-1)){r.remove("uri");hits.put(r);}}catch(Exception ignored){}
            }
            answer.put("matches",hits);if(hits.length()==1)answer.put("sourceId",hits.getJSONObject(0).getString("sourceId"));
        }catch(Exception ignored){}return answer;
    }
    private ComponentName googleService(){
        List<ResolveInfo> matches=getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE),0);
        for(String pkg:new String[]{"com.google.android.googlequicksearchbox","com.google.android.tts"})
            for(ResolveInfo info:matches)if(info.serviceInfo!=null&&pkg.equals(info.serviceInfo.packageName))return new ComponentName(pkg,info.serviceInfo.name);
        return null;
    }
    public final class Bridge {
        @JavascriptInterface public String capabilities(){
            JSONObject r=new JSONObject();try{r.put("version","0.8.3");r.put("sdk",Build.VERSION.SDK_INT);r.put("googleAvailable",googleService()!=null);r.put("fileInput",Build.VERSION.SDK_INT>=34);r.put("maxSourceSeconds",120);}catch(Exception ignored){}return r.toString();
        }
        @JavascriptInterface public String resolveSource(String name,long size){return resolve(name,size).toString();}
        @JavascriptInterface public String lastSource(){synchronized(CaptionActivity.this){return lastSelection.toString();}}
        @JavascriptInterface public void transcribe(String json){main.post(()->start(json));}
        @JavascriptInterface public void cancel(){main.post(()->{pendingPermission=null;if(active)fail("CANCELLED","자동자막 생성을 취소했습니다.");});}
    }
    private void start(String json){
        if(!trusted())return;
        if(active){JSONObject e=event("error","");try{e.put("code","BUSY");e.put("message","이미 음성을 처리하고 있습니다.");send(e);}catch(Exception ignored){}return;}
        try{
            request=new JSONObject(json);String id=request.getString("id");if(id.length()>120)throw new IllegalArgumentException();
            double start=request.getDouble("sourceStart"),end=request.getDouble("sourceEnd");
            if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||end-start>35.01)throw new IllegalArgumentException("시험 구간은 원본 음성 35초 이하이어야 합니다.");
            if(Build.VERSION.SDK_INT<34)throw new IllegalStateException("구글 파일 인식 시험은 Android 14 이상에서 지원합니다. SRT 불러오기는 사용할 수 있습니다.");
            ComponentName service=googleService();if(service==null)throw new IllegalStateException("구글 음성인식 서비스를 찾지 못했습니다. Google 앱과 Google 음성 서비스를 설치·업데이트해 주세요.");
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){pendingPermission=json;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_PERMISSION);return;}
            String saved=sources.getString(request.getString("sourceId"),null);
            if(saved==null)throw new IllegalStateException("원본 파일을 다시 연결해 주세요.");
            JSONObject record=new JSONObject(saved);
            if(!record.getString("name").equals(request.getString("name"))||record.getLong("size")!=request.getLong("size"))throw new IllegalArgumentException("연결한 원본 파일이 선택한 영상과 다릅니다.");
            final Uri uri=Uri.parse(record.getString("uri"));final int token=++generation;
            active=true;receivedSegments=false;segments.clear();
            pcm=new File(new File(getCacheDir(),"caption-pcm"),"audio-"+token+".pcm");final File target=pcm;
            progress(token,"영상에서 음성을 추출하고 있습니다.",0);
            preparation=worker.submit(()->{
                try{CaptionAudio.extract(CaptionActivity.this,uri,target,start,end,p->{if(token!=generation)throw new InterruptedException();progress(token,"음성 추출 "+p+"%",p/4);});
                    main.post(()->{if(token==generation&&active)recognize(token,service);else target.delete();});
                }catch(Exception error){main.post(()->{if(token==generation&&active)fail("AUDIO",error instanceof SecurityException?"원본 파일 접근 권한이 만료되었습니다. 원본 연결을 눌러 다시 선택해 주세요.":safeMessage(error));});}
            });
            timeout=()->{if(token==generation&&active)fail("TIMEOUT","처리 시간이 초과되었습니다. 15초 구간으로 다시 시험해 주세요.");};main.postDelayed(timeout,210000);
        }catch(Exception error){fail("SETUP",safeMessage(error));}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==AUDIO_PERMISSION){String json=pendingPermission;pendingPermission=null;if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){if(json!=null)start(json);}else fail("PERMISSION","Android 음성인식을 사용하려면 오디오 권한이 필요합니다.");}
    }
    private void recognize(final int token,ComponentName service){
        try{
            // Always supply the file descriptor; never intentionally fall back to microphone input.
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();sourceDescriptor=pipe[0];
            pipeProbe=new FileInputStream(sourceDescriptor.getFileDescriptor());fileAudioWritten=false;
            final ParcelFileDescriptor writeEnd=pipe[1];writeDescriptor=writeEnd;
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,sourceDescriptor);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000);
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,RecognizerIntent.EXTRA_AUDIO_SOURCE);
            intent.putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING,true);
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING,RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
            recognizer=SpeechRecognizer.createSpeechRecognizer(this,service);
            final File input=pcm;
            final Runnable feed=()->{
                if(feeder!=null)return;
                feeder=new Thread(()->{
                    try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd);FileInputStream stream=new FileInputStream(input)){
                        byte[] block=new byte[640];int n;long began=System.nanoTime(),sent=0;
                        while(token==generation&&active&&(n=stream.read(block))>0){
                            output.write(block,0,n);sent+=n;
                            long sleep=began+sent*1000000000L/32000-System.nanoTime();if(sleep>0)Thread.sleep(sleep/1000000,(int)(sleep%1000000));
                        }
                        if(token==generation&&active)fileAudioWritten=true;
                    }catch(Exception ignored){}
                },"ledoa-caption-file-audio");feeder.start();
            };
            recognizer.setRecognitionListener(new RecognitionListener(){
                public void onReadyForSpeech(Bundle b){if(token==generation&&active){progress(token,"구글 음성인식 중 · 영상 길이만큼 걸릴 수 있습니다.",30);feed.run();}}
                public void onBeginningOfSpeech(){}public void onRmsChanged(float rms){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){}
                public void onPartialResults(Bundle b){}public void onEvent(int t,Bundle b){}
                public void onError(int code){if(token==generation&&active){try{writeEnd.close();}catch(Exception ignored){}fail("GOOGLE_"+code,speechError(code));}}
                public void onResults(Bundle b){if(token!=generation||!active)return;if(!receivedSegments)collect(b);complete(token);}
                public void onSegmentResults(Bundle b){if(token!=generation||!active)return;receivedSegments=true;collect(b);progress(token,"인식한 문장을 정리하고 있습니다.",70);}
                public void onEndOfSegmentedSession(){complete(token);}
            });
            final SpeechStartGate gate=new SpeechStartGate(new SpeechStartGate.Actions(){
                public boolean current(){return token==generation&&active;}
                public void start(boolean unchecked){
                    try{
                        if(unchecked)progress(token,"사전 지원 조회를 제공하지 않아 영상 음성을 직접 인식해 봅니다.",28);
                        recognizer.startListening(intent);
                        main.postDelayed(()->{if(token==generation&&active)feed.run();},1500);
                    }catch(Exception error){if(token==generation&&active)fail("START",safeMessage(error));}
                }
                public void rejected(int code){fail("SUPPORT_"+code,speechError(code));}
            });
            recognizer.checkRecognitionSupport(intent,main::post,new RecognitionSupportCallback(){
                public void onSupportResult(RecognitionSupport support){gate.supported();}
                public void onError(int error){gate.queryFailed(error);}
            });
        }catch(Exception error){fail("RECOGNIZER",safeMessage(error));}
    }
    private void collect(Bundle bundle){
        try{
            ArrayList<String> texts=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if(texts==null||texts.isEmpty()||texts.get(0).trim().length()==0)return;
            JSONObject item=new JSONObject();item.put("text",texts.get(0));JSONArray words=new JSONArray();
            ArrayList<RecognitionPart> parts=bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS,RecognitionPart.class);
            if(parts!=null)for(RecognitionPart part:parts){JSONObject w=new JSONObject();String text=part.getFormattedText();if(text==null)text=part.getRawText();w.put("text",text);w.put("start",part.getTimestampMillis()/1000.0);words.put(w);}
            item.put("words",words);segments.add(item);
        }catch(Exception ignored){}
    }
    private void complete(int token){
        if(token!=generation||!active)return;
        // Optional audio-source extras can be ignored by a service. Reject results
        // if it did not consume our file, rather than accepting microphone input.
        try{if(!fileAudioWritten||pipeProbe==null||pipeProbe.available()!=0){
            fail("FILE_INPUT_NOT_CONSUMED","구글 서비스가 전달한 영상 음성을 끝까지 읽지 않았습니다. 이 기기의 파일 입력 호환성을 추가로 확인해야 합니다. (파일 입력)");return;
        }}catch(Exception error){fail("FILE_INPUT_UNVERIFIED","영상 음성 입력을 확인할 수 없어 결과를 적용하지 않았습니다.");return;}
        if(segments.isEmpty()){fail("NO_SPEECH","이 구간에서 인식할 말을 찾지 못했습니다.");return;}
        try{JSONObject out=event("result",request.optString("id"));JSONArray data=new JSONArray();for(JSONObject item:segments)data.put(item);out.put("segments",data);out.put("sourceStart",request.getDouble("sourceStart"));out.put("sourceEnd",request.getDouble("sourceEnd"));out.put("engine","android-google");cleanup();send(out);}catch(Exception e){fail("RESULT",safeMessage(e));}
    }
    private void fail(String code,String message){try{JSONObject e=event("error",request==null?"":request.optString("id"));e.put("code",code);e.put("message",message);cleanup();send(e);}catch(Exception ignored){cleanup();}}
    private static String safeMessage(Exception error){String message=error.getMessage();return message==null||message.length()==0?"음성 처리에 실패했습니다. 원본 파일을 다시 연결해 주세요.":message;}
    private static String speechError(int code){
        switch(code){case 1:case 2:return "구글 음성인식 네트워크 연결을 확인해 주세요.";case 3:return "영상 음성을 읽지 못했습니다. 구글 파일 입력 지원을 확인해야 합니다.";case 6:case 7:return "이 구간에서 말을 인식하지 못했습니다.";case 8:return "구글 음성인식이 사용 중입니다. 잠시 후 다시 눌러 주세요.";case 9:return "Google 앱의 마이크·오디오 권한을 확인해 주세요.";case 12:case 13:return "구글 한국어 음성인식 언어를 설치·업데이트해 주세요.";default:return "구글 음성인식이 중단되었습니다. 오류 번호: "+code;}
    }
    private void cleanup(){
        active=false;generation++;if(timeout!=null)main.removeCallbacks(timeout);timeout=null;
        if(preparation!=null)preparation.cancel(true);preparation=null;
        if(feeder!=null)feeder.interrupt();feeder=null;
        if(sourceDescriptor!=null)try{sourceDescriptor.close();}catch(Exception ignored){}sourceDescriptor=null;
        if(writeDescriptor!=null)try{writeDescriptor.close();}catch(Exception ignored){}writeDescriptor=null;
        if(pipeProbe!=null)try{pipeProbe.close();}catch(Exception ignored){}pipeProbe=null;
        if(recognizer!=null){try{recognizer.cancel();recognizer.destroy();}catch(Exception ignored){}recognizer=null;}
        if(pcm!=null)pcm.delete();pcm=null;
    }
    @Override protected void onStop(){if(active)fail("BACKGROUND","앱이 백그라운드로 이동해 인식을 중단했습니다. 생성된 기존 자막은 유지됩니다.");super.onStop();}
    @Override protected void onDestroy(){cleanup();worker.shutdownNow();super.onDestroy();}
}

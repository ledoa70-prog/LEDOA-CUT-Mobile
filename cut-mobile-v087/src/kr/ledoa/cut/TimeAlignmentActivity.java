package kr.ledoa.cut;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Adds local alignment through a separate bridge; the confirmed Google bridge is unchanged. */
public class TimeAlignmentActivity extends CaptionActivity {
    private static final String MODEL_SHA="0f1aa81911a6dc8fe61ae0c13f4bbebd37145c5ad69c83da24d9256bb4e21623";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService alignmentWorker=Executors.newSingleThreadExecutor();
    private volatile int serial=0;private boolean aligning=false;private Future<?> task;private WebView view;
    private volatile OrtSession.RunOptions running;
    @Override public void onCreate(Bundle state){super.onCreate(state);view=find(getWindow().getDecorView());if(view!=null)view.addJavascriptInterface(new AlignmentBridge(),"LedoaAlignment");}
    private WebView find(View v){if(v instanceof WebView)return (WebView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){WebView w=find(g.getChildAt(i));if(w!=null)return w;}}return null;}
    private boolean trusted(){return view!=null&&view.getUrl()!=null&&view.getUrl().startsWith("file:///android_asset/");}
    private void send(int token,JSONObject event){ui.post(()->{if(token==serial&&trusted())view.evaluateJavascript("window.LEDOA_ALIGNMENT_EVENT&&window.LEDOA_ALIGNMENT_EVENT("+event+")",null);});}
    private JSONObject event(String type,String id){JSONObject e=new JSONObject();try{e.put("type",type);e.put("id",id);}catch(Exception ignored){}return e;}
    private void progress(int token,String id,String message,int percent){try{JSONObject e=event("progress",id);e.put("message",message);e.put("percent",percent);send(token,e);}catch(Exception ignored){}}
    private void check(int token)throws InterruptedException{if(token!=serial||Thread.currentThread().isInterrupted())throw new InterruptedException();}
    public final class AlignmentBridge {
        @JavascriptInterface public String capabilities(){return "{\"available\":true,\"local\":true,\"maxSeconds\":120,\"version\":\"0.8.5\"}";}
        @JavascriptInterface public void align(String json){ui.post(()->start(json));}
        @JavascriptInterface public void cancel(){ui.post(()->cancelAlignment());}
    }
    private void cancelAlignment(){serial++;aligning=false;OrtSession.RunOptions r=running;if(r!=null)try{r.setTerminate(true);}catch(Exception ignored){}if(task!=null)task.cancel(true);}
    private void start(String json){
        if(!trusted()||aligning)return;final int token=++serial;String id="";
        try{
            JSONObject input=new JSONObject(json);id=input.getString("id");if(id.length()>120)throw new IllegalArgumentException();
            double start=input.getDouble("sourceStart"),end=input.getDouble("sourceEnd");
            if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||end-start>120.01)throw new IllegalArgumentException("시간 정렬은 한 번에 원본 음성 120초까지 할 수 있습니다.");
            JSONArray cues=input.getJSONArray("cues");if(cues.length()==0||cues.length()>200)throw new IllegalArgumentException("정렬할 문단을 확인해 주세요.");
            ArrayList<String> texts=new ArrayList<String>();int count=0;for(int i=0;i<cues.length();i++){String text=cues.getString(i);texts.add(text);count+=text.length();}if(count>10000)throw new IllegalArgumentException("정렬할 자막을 더 짧게 나눠 주세요.");
            SharedPreferences preferences=getSharedPreferences("caption-source-uris-v1",MODE_PRIVATE);
            String record=preferences.getString(input.getString("sourceId"),null);if(record==null)throw new IllegalArgumentException("원본 파일을 다시 연결해 주세요.");
            JSONObject source=new JSONObject(record);if(!source.getString("name").equals(input.getString("name"))||source.getLong("size")!=input.getLong("size"))throw new IllegalArgumentException("연결한 원본 파일이 다릅니다.");
            final Uri uri=Uri.parse(source.getString("uri"));final String job=id;aligning=true;
            task=alignmentWorker.submit(()->{
                File pcm=new File(getCacheDir(),"time-alignment-"+token+".pcm");
                try{
                    progress(token,job,"시간 정렬용 음성을 추출하고 있습니다.",2);
                    CaptionAudio.extract(TimeAlignmentActivity.this,uri,pcm,start,end,p->{check(token);progress(token,job,"음성 추출 "+p+"%",2+p/10);});
                    File model=prepareModel(token,job);check(token);
                    Map<String,Integer> vocab=new HashMap<String,Integer>();JSONObject vocabulary=new JSONObject(assetText("alignment/vocab.json"));
                    java.util.Iterator<String> keys=vocabulary.keys();while(keys.hasNext()){String key=keys.next();vocab.put(key,vocabulary.getInt(key));}
                    CtcAlignment.Transcript transcript=CtcAlignment.tokenize(texts,vocab);float[] audio=readPcm(pcm);check(token);
                    double energy=0;for(float value:audio)energy+=value*value;if(Math.sqrt(energy/Math.max(1,audio.length))<.0006)throw new IllegalArgumentException("읽을 수 있는 음성이 너무 작거나 없습니다. 기존 자막은 유지했습니다.");
                    progress(token,job,"한국어 시간 정렬 모델을 준비하고 있습니다.",15);
                    OrtEnvironment environment=OrtEnvironment.getEnvironment();
                    try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
                        options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);options.setCPUArenaAllocator(false);options.setMemoryPatternOptimization(false);
                        try(OrtSession session=environment.createSession(model.getAbsolutePath(),options);OrtSession.RunOptions runOptions=new OrtSession.RunOptions()){
                            running=runOptions;float[][] logp=infer(session,environment,audio,runOptions,token,job);check(token);
                            progress(token,job,"수정한 문장을 음성에 맞추고 있습니다.",90);
                            java.util.List<CtcAlignment.Word> aligned=CtcAlignment.align(logp,transcript,1204,.02,end-start,()->check(token));check(token);
                            double confidence=0;int good=0;JSONArray words=new JSONArray();
                            for(CtcAlignment.Word word:aligned){confidence+=word.confidence;if(!word.review)good++;JSONObject w=new JSONObject();w.put("text",word.text);w.put("cue",word.cue);w.put("start",start+word.start);w.put("end",start+word.end);w.put("confidence",word.confidence);w.put("review",word.review);words.put(w);}
                            if(confidence/aligned.size()<.025||good<Math.max(1,aligned.size()/4))throw new IllegalArgumentException("자막과 음성이 충분히 일치하지 않아 배치하지 않았습니다. 인식 구간과 수정한 문장을 확인해 주세요.");
                            JSONObject result=event("result",job);result.put("words",words);result.put("engine","korean-ctc-local");result.put("sourceStart",start);result.put("sourceEnd",end);send(token,result);
                        }finally{running=null;}
                    }
                }catch(InterruptedException ignored){}catch(OutOfMemoryError error){error(token,job,"시간 정렬에 필요한 메모리가 부족합니다. 앱을 다시 열어 15초 구간부터 시도해 주세요.");}
                catch(Throwable error){if(token==serial)error(token,job,error.getMessage()==null?"시간 정렬을 완료하지 못했습니다. 기존 자막은 유지했습니다.":error.getMessage());}
                finally{pcm.delete();ui.post(()->{if(token==serial)aligning=false;});}
            });
        }catch(Exception error){error(token,id,error.getMessage());aligning=false;}
    }
    private void error(int token,String id,String message){try{JSONObject e=event("error",id);e.put("message",message);send(token,e);}catch(Exception ignored){}}
    private String assetText(String name)throws Exception{try(InputStream in=getAssets().open(name);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);return new String(out.toByteArray(),"UTF-8");}}
    private File prepareModel(int token,String id)throws Exception{
        File folder=new File(getFilesDir(),"time-alignment-v1");folder.mkdirs();File target=new File(folder,"korean-"+MODEL_SHA.substring(0,12)+".onnx");
        if(target.isFile()&&target.length()==318464664L)return target;
        File temp=new File(folder,"model.part");MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=getAssets().open("alignment/model.onnx");FileOutputStream out=new FileOutputStream(temp)){
            byte[] b=new byte[1024*1024];int n,last=-1;long done=0;while((n=in.read(b))>0){check(token);out.write(b,0,n);digest.update(b,0,n);done+=n;int p=(int)(done*100/318464664L);if(p!=last){progress(token,id,"첫 실행 준비 "+p+"% · 모델을 기기에 설치하고 있습니다.",12+p/50);last=p;}}
        }catch(Exception e){temp.delete();throw e;}
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format("%02x",b&255));
        if(!MODEL_SHA.equals(hash.toString())){temp.delete();throw new IllegalArgumentException("시간 정렬 모델을 확인하지 못했습니다. 설치 파일로 다시 업데이트해 주세요.");}
        if(!temp.renameTo(target))throw new IllegalArgumentException("모델을 저장할 공간이 부족합니다. 저장 공간을 확보한 뒤 다시 눌러 주세요.");return target;
    }
    private float[] readPcm(File file)throws Exception{
        if(file.length()>3840032L)throw new IllegalArgumentException("음성 구간이 너무 깁니다.");
        float[] samples=new float[(int)(file.length()/2)];try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n,index=0;while((n=in.read(b))>0)for(int i=0;i+1<n;i+=2)samples[index++]=(short)((b[i]&255)|(b[i+1]<<8))/32768f;}return samples;
    }
    private float[][] infer(OrtSession session,OrtEnvironment env,float[] audio,OrtSession.RunOptions options,int token,String id)throws Exception{
        int frames=(audio.length+319)/320;float[][] output=new float[frames][];int core=400,context=40;
        for(int begin=0;begin<frames;begin+=core){
            check(token);int finish=Math.min(frames,begin+core),left=Math.max(0,begin-context),right=Math.min(audio.length, (finish+context)*320);
            int count=right-left*320;float[] samples=new float[count+1600];double mean=0,variance=0;for(int i=0;i<count;i++)mean+=audio[left*320+i];mean/=count;
            for(int i=0;i<count;i++){double value=audio[left*320+i]-mean;variance+=value*value;}double scale=1/Math.sqrt(variance/count+1e-7);
            for(int i=0;i<count;i++)samples[i]=(float)((audio[left*320+i]-mean)*scale);
            progress(token,id,"음성과 단어 분석 "+(begin/400+1)+"/"+((frames+399)/400)+" · 기기 안에서 처리 중",20+(int)(65.0*begin/frames));
            try(OnnxTensor input=OnnxTensor.createTensor(env,FloatBuffer.wrap(samples),new long[]{1,samples.length});OrtSession.Result result=session.run(java.util.Collections.singletonMap("input_values",input),options)){
                float[][] values=((float[][][])result.get(0).getValue())[0];
                for(int f=begin;f<finish;f++){int local=f-left;if(local>=values.length-2)throw new IllegalArgumentException("음성 분석의 시간 범위를 확인하지 못했습니다.");float[] row=values[local];if(row.length<=1204)throw new IllegalArgumentException("한국어 정렬 모델 구성이 다릅니다.");CtcAlignment.logSoftmax(row);output[f]=row;}
            }
        }return output;
    }
    @Override protected void onDestroy(){cancelAlignment();alignmentWorker.shutdownNow();super.onDestroy();}
}

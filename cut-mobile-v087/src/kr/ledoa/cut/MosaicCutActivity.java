package kr.ledoa.cut;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.ViewGroup;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import kr.ledoa.cut.aitest.MosaicActivity;

/** In-app mosaic editor; all native sources are rebuilt, with the existing editor origin retained. */
public class MosaicCutActivity extends TimeAlignmentActivity {
    private static final int EDIT_MOSAIC=5871;
    private final Handler main=new Handler(Looper.getMainLooper());
    private WebView web;
    private SharedPreferences sources,jobs;
    private volatile boolean trusted,destroyed;
    private File uploadPart;
    private OutputStream upload;
    private String uploadId,uploadName;
    private long uploadSize,uploadWritten;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);sources=getSharedPreferences("caption-source-uris-v1",MODE_PRIVATE);jobs=getSharedPreferences("mosaic-jobs-v1",MODE_PRIVATE);
        web=find(getWindow().getDecorView());
        if(web!=null){trusted=web.getUrl()!=null&&web.getUrl().startsWith("file:///android_asset/");web.addJavascriptInterface(new MosaicBridge(),"LedoaMosaic");web.setWebViewClient(new WebViewClient(){@Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){trusted=false;}@Override public void onPageFinished(WebView view,String url){trusted=url!=null&&url.startsWith("file:///android_asset/");}});}
        folder().mkdirs();
    }
    private WebView find(View view){if(view instanceof WebView)return (WebView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){WebView found=find(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private File folder(){return new File(getFilesDir(),"mosaic-edits-v1");}
    private void check(){if(destroyed||!trusted)throw new IllegalStateException("편집 화면을 다시 열어 주세요.");}
    private JSONObject record(String key){try{return new JSONObject(jobs.getString(key,"{}"));}catch(Exception ignored){return new JSONObject();}}
    private void savePending(JSONObject event){jobs.edit().putString("pending",event.toString()).commit();send(event);}
    private void send(JSONObject event){main.post(()->{if(!destroyed&&web!=null&&web.getUrl()!=null&&web.getUrl().startsWith("file:///android_asset/"))web.evaluateJavascript("window.LEDOA_MOSAIC_EVENT&&window.LEDOA_MOSAIC_EVENT("+event+")",null);});}
    private String sourceRecord(Uri uri,String name,long size)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8));StringBuilder id=new StringBuilder();for(byte value:digest)id.append(String.format("%02x",value&255));
        JSONObject record=new JSONObject();record.put("sourceId",id.toString());record.put("uri",uri.toString());record.put("name",name);record.put("size",size);sources.edit().putString(id.toString(),record.toString()).commit();return id.toString();
    }
    private JSONObject validSource(String id,String name,long size){
        try{JSONObject source=new JSONObject(sources.getString(id,"{}"));if(!name.equals(source.optString("name"))||size!=source.optLong("size",-1))return null;
            try(android.os.ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(Uri.parse(source.getString("uri")),"r")){if(descriptor==null)return null;}return source;
        }catch(Exception ignored){return null;}
    }
    private File resultFile(String token)throws Exception{
        if(!token.matches("[a-f0-9-]{36}"))throw new IOException("결과 파일을 찾지 못했습니다.");JSONObject job=record("job:"+token);
        if(!"ready".equals(job.optString("state")))throw new IOException("아직 적용할 결과가 없습니다.");
        File file=new File(folder(),token+".mp4");if(!file.isFile()||file.length()!=job.getLong("size"))throw new IOException("결과 파일이 변경되었습니다.");return file;
    }
    private synchronized void closeUpload(){try{if(upload!=null)upload.close();}catch(Exception ignored){}upload=null;if(uploadPart!=null)uploadPart.delete();uploadPart=null;uploadId=null;uploadWritten=0;}

    public final class MosaicBridge {
        @JavascriptInterface public String capabilities(){return "{\"available\":true,\"version\":\"0.8.7\",\"mosaicVersion\":\"0.9.4\",\"chunkBytes\":262144}";}
        @JavascriptInterface public String pending(){check();return jobs.getString("pending","{}");}
        @JavascriptInterface public String session(String token){
            check();try{resultFile(token);JSONObject job=record("job:"+token);JSONObject source=validSource(job.getString("sourceId"),job.getString("sourceName"),job.getLong("sourceSize"));
                if(source!=null&&new File(folder(),token+".json").isFile()){JSONObject answer=new JSONObject();answer.put("available",true);answer.put("sourceId",source.getString("sourceId"));answer.put("name",source.getString("name"));answer.put("size",source.getLong("size"));return answer.toString();}
            }catch(Exception ignored){}return "{\"available\":false}";
        }
        @JavascriptInterface public String resolveSource(String id,String name,long size){
            check();JSONObject answer=new JSONObject();try{
                JSONObject found=validSource(id,name,size);
                if(found==null){for(String key:sources.getAll().keySet()){JSONObject candidate=validSource(key,name,size);if(candidate!=null){if(found!=null)return "{}";found=candidate;}}}
                if(found!=null)answer.put("sourceId",found.getString("sourceId"));
            }catch(Exception ignored){}return answer.toString();
        }
        @JavascriptInterface public void beginSource(String id,String name,long size){
            check();synchronized(MosaicCutActivity.this){closeUpload();try{
                if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}")||size<=0||size>folder().getUsableSpace()-32*1024*1024L)throw new IOException("영상을 준비할 저장 공간이 부족합니다.");
                uploadId=id;uploadName=name;uploadSize=size;uploadWritten=0;uploadPart=new File(folder(),"source-"+UUID.randomUUID()+".part");upload=new FileOutputStream(uploadPart);
            }catch(Exception error){closeUpload();throw new IllegalStateException(error.getMessage());}}
        }
        @JavascriptInterface public void appendSource(String id,String data){
            check();synchronized(MosaicCutActivity.this){try{
                if(upload==null||!id.equals(uploadId)||data.length()>360000)throw new IOException("영상 준비 요청이 다릅니다.");byte[] bytes=Base64.decode(data,Base64.DEFAULT);
                if(uploadWritten+bytes.length>uploadSize)throw new IOException("영상 크기가 다릅니다.");upload.write(bytes);uploadWritten+=bytes.length;
            }catch(Exception error){closeUpload();throw new IllegalStateException(error.getMessage());}}
        }
        @JavascriptInterface public String finishSource(String id){
            check();synchronized(MosaicCutActivity.this){try{
                if(upload==null||!id.equals(uploadId)||uploadWritten!=uploadSize)throw new IOException("영상 준비를 완료하지 못했습니다.");upload.close();upload=null;
                File source=new File(folder(),"source-"+UUID.randomUUID()+".video");if(!uploadPart.renameTo(source))throw new IOException("영상을 보관하지 못했습니다.");uploadPart=null;
                String result=sourceRecord(Uri.fromFile(source),uploadName,uploadWritten);uploadId=null;return result;
            }catch(Exception error){closeUpload();throw new IllegalStateException(error.getMessage());}}
        }
        @JavascriptInterface public void cancelSource(String id){synchronized(MosaicCutActivity.this){if(id!=null&&id.equals(uploadId))closeUpload();}}
        @JavascriptInterface public void edit(String json){main.post(()->startEditor(json));}
        @JavascriptInterface public String readResult(String token,long offset,int count){
            check();try{File file=resultFile(token);if(offset<0||offset>file.length()||count<1||count>262144)throw new IOException("결과 읽기 범위를 확인해 주세요.");
                byte[] bytes=new byte[(int)Math.min(count,file.length()-offset)];try(RandomAccessFile input=new RandomAccessFile(file,"r")){input.seek(offset);input.readFully(bytes);}return Base64.encodeToString(bytes,Base64.NO_WRAP);
            }catch(Exception error){throw new IllegalStateException(error.getMessage());}
        }
        @JavascriptInterface public void acknowledge(String id){check();JSONObject pending=record("pending");if(id.equals(pending.optString("id")))jobs.edit().remove("pending").commit();}
    }
    private void startEditor(String json){
        String id="";try{
            check();JSONObject input=new JSONObject(json);id=input.getString("id");if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("편집 요청을 확인해 주세요.");
            JSONObject pending=record("pending");if("editing".equals(pending.optString("state")))throw new IOException("이미 모자이크를 편집하고 있습니다.");
            JSONObject source=validSource(input.getString("sourceId"),input.getString("name"),input.getLong("size"));if(source==null)throw new IOException("원본 영상을 다시 준비해 주세요.");
            double start=input.getDouble("sourceStart"),end=input.getDouble("sourceEnd"),at=input.getDouble("sourceTime");
            if(!Double.isFinite(start)||!Double.isFinite(end)||!Double.isFinite(at)||start<0||end<=start)throw new IOException("영상 구간을 확인해 주세요.");
            String token=UUID.randomUUID().toString();JSONObject job=new JSONObject();job.put("id",id);job.put("clipId",input.getString("clipId"));job.put("token",token);job.put("sourceId",source.getString("sourceId"));job.put("sourceName",source.getString("name"));job.put("sourceSize",source.getLong("size"));job.put("state","editing");
            jobs.edit().putString("job:"+token,job.toString()).commit();savePending(job);
            Intent intent=new Intent(this,MosaicActivity.class).putExtra("ledoa.integrated",true).putExtra("ledoa.source",source.getString("uri")).putExtra("ledoa.output",new File(folder(),token+".mp4").getAbsolutePath()).putExtra("ledoa.recipe",new File(folder(),token+".json").getAbsolutePath()).putExtra("ledoa.startMs",Math.round(start*1000)).putExtra("ledoa.endMs",Math.round(end*1000)).putExtra("ledoa.timeMs",Math.round(at*1000));
            String previous=input.optString("previousToken");if(previous.matches("[a-f0-9-]{36}")){JSONObject old=record("job:"+previous);File recipe=new File(folder(),previous+".json");if(old.optString("sourceId").equals(source.getString("sourceId"))&&recipe.isFile())intent.putExtra("ledoa.restore",recipe.getAbsolutePath());}
            startActivityForResult(intent,EDIT_MOSAIC);
        }catch(Exception error){JSONObject event=new JSONObject();try{event.put("id",id);event.put("state","error");event.put("message",error.getMessage());}catch(Exception ignored){}savePending(event);}
    }
    @Override public void onActivityResult(int request,int result,Intent data){
        if(request!=EDIT_MOSAIC){super.onActivityResult(request,result,data);return;}
        JSONObject job=record("pending");String token=job.optString("token");
        try{
            if(result==Activity.RESULT_OK&&token.matches("[a-f0-9-]{36}")){
                File output=new File(folder(),token+".mp4"),recipe=new File(folder(),token+".json");if(!output.isFile()||output.length()<1024||!recipe.isFile())throw new IOException("모자이크 결과를 확인하지 못했습니다.");
                String name=job.getString("sourceName").replaceFirst("\\.[^.]+$","")+"_모자이크.mp4";job.put("name",name);job.put("size",output.length());job.put("resultSourceId",sourceRecord(Uri.fromFile(output),name,output.length()));job.put("state","ready");
                jobs.edit().putString("job:"+token,job.toString()).commit();
            }else{
                job.put("state","cancelled");if(token.matches("[a-f0-9-]{36}")){new File(folder(),token+".mp4").delete();new File(folder(),token+".json").delete();jobs.edit().remove("job:"+token).commit();}
            }
        }catch(Exception error){try{job.put("state","error");job.put("message",error.getMessage());}catch(Exception ignored){}}
        savePending(job);
    }
    @Override protected void onResume(){super.onResume();main.postDelayed(()->{if(jobs!=null){JSONObject pending=record("pending");if(pending.has("id"))send(pending);}},500);}
    @Override protected void onDestroy(){destroyed=true;closeUpload();super.onDestroy();}
}

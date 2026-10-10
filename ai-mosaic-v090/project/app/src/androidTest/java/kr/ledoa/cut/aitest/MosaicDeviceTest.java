package kr.ledoa.cut.aitest;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.media.*;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class MosaicDeviceTest {
 Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 File fixture(String name)throws Exception{File f=new File(context.getFilesDir(),name);try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);OutputStream out=new FileOutputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}return f;}
 @Test public void exportPixelsAudioTimingAndRotation()throws Exception{
  for(String name:new String[]{"sample.mp4","rotated.mp4"}){
   File input=fixture(name),output=new File(context.getFilesDir(),"export-"+name);
   MosaicTimeline t=new MosaicTimeline();t.allFaces=true;t.startMs=500;t.endMs=1500;t.analysedEndMs=1500;t.strength=100;t.margin=0;
   for(int ms=500;ms<1500;ms+=100)t.put(ms,List.of(new FacePath.Box(.10f,.1f,.30f,.7f,-1)));
   MosaicExport.Result result=MosaicExport.run(context,Uri.fromFile(input),output,t,new AtomicBoolean(),p->{});
   assertTrue(result.frames>40);assertTrue(result.audio);assertTrue(output.length()>10000);
   MediaMetadataRetriever original=new MediaMetadataRetriever(),rendered=new MediaMetadataRetriever();original.setDataSource(input.getPath());rendered.setDataSource(output.getPath());
   long originalDuration=Long.parseLong(original.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));long outputDuration=Long.parseLong(rendered.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));assertTrue("duration preserved",Math.abs(originalDuration-outputDuration)<120);
   for(long ms:new long[]{200,700,1700}){
    Bitmap a=original.getFrameAtTime(ms*1000,MediaMetadataRetriever.OPTION_CLOSEST),b=rendered.getFrameAtTime(ms*1000,MediaMetadataRetriever.OPTION_CLOSEST);
    assertEquals("width "+name,a.getWidth(),b.getWidth());assertEquals("height "+name,a.getHeight(),b.getHeight());
    double inside=error(a,b,.13f,.13f,.37f,.77f),outside=error(a,b,.55f,.1f,.95f,.9f);
    assertTrue("outside mask unchanged "+name+" "+ms+" error="+outside,outside<12);
    if(ms==700)assertTrue("mosaic burned in "+name+" error="+inside,inside>12);else assertTrue("range excludes frame "+name+" "+ms+" error="+inside,inside<12);
    if(ms==700)try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"export-"+name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}a.recycle();b.recycle();
   }
   original.release();rendered.release();
   MediaExtractor e=new MediaExtractor();e.setDataSource(output.getPath());boolean audio=false;for(int i=0;i<e.getTrackCount();i++)if(e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/"))audio=true;e.release();assertTrue("audio track copied",audio);
  }
 }
 double error(Bitmap a,Bitmap b,float l,float t,float r,float d){double sum=0;int n=0;for(int y=(int)(t*a.getHeight());y<d*a.getHeight();y+=3)for(int x=(int)(l*a.getWidth());x<r*a.getWidth();x+=3){int p=a.getPixel(x,y),q=b.getPixel(x,y);sum+=Math.abs(((p>>16)&255)-((q>>16)&255))+Math.abs(((p>>8)&255)-((q>>8)&255))+Math.abs((p&255)-(q&255));n+=3;}return sum/n;}
 @Test public void learnedIdentityIsStableUnderLighting()throws Exception{
  File input=fixture("faces.mp4");MediaMetadataRetriever r=new MediaMetadataRetriever();r.setDataSource(input.getPath());Bitmap first=r.getFrameAtTime(0),changed=first.copy(Bitmap.Config.ARGB_8888,true);
  android.graphics.Canvas canvas=new android.graphics.Canvas(changed);android.graphics.Paint paint=new android.graphics.Paint();paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{.8f,0,0,0,10,0,.8f,0,0,10,0,0,.8f,0,10,0,0,0,1,0}));canvas.drawBitmap(first,0,0,paint);
  com.google.mlkit.vision.face.FaceDetector detector=com.google.mlkit.vision.face.FaceDetection.getClient(new com.google.mlkit.vision.face.FaceDetectorOptions.Builder().setPerformanceMode(2).setLandmarkMode(1).build());
  try(FaceIdentity id=new FaceIdentity(context)){
   java.util.List<com.google.mlkit.vision.face.Face> a=com.google.android.gms.tasks.Tasks.await(detector.process(com.google.mlkit.vision.common.InputImage.fromBitmap(first,0))),b=com.google.android.gms.tasks.Tasks.await(detector.process(com.google.mlkit.vision.common.InputImage.fromBitmap(changed,0)));
   assertFalse(a.isEmpty());assertFalse(b.isEmpty());id.begin(first);float[] x=id.describe(a.get(0));id.begin(changed);float[] y=id.describe(b.get(0));assertEquals(128,x.length);assertTrue("same face under changed lighting",FaceAppearance.score(x,y)>.85f);
  }finally{detector.close();first.recycle();changed.recycle();r.release();}
 }
 @Test public void cancelledExportLeavesNoPartialFile()throws Exception{File out=new File(context.getFilesDir(),"cancel.mp4");try{MosaicExport.run(context,Uri.fromFile(fixture("sample.mp4")),out,new MosaicTimeline(),new AtomicBoolean(true),p->{});fail("cancel must stop");}catch(InterruptedException expected){}assertFalse(out.exists());}
 static Object field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
 @Test public void selectedFaceAnalysisUsesBundledIdentity()throws Exception{
  File input=fixture("faces.mp4");
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   scenario.onActivity(a->a.onActivityResult(1001,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(input))));waitReady(scenario);
   scenario.onActivity(a->{try{
    java.util.List<FacePath.Box> faces=(java.util.List<FacePath.Box>)field(a,"visibleFaces");assertFalse(faces.isEmpty());assertEquals(128,faces.get(0).appearance.length);
    java.lang.reflect.Method select=MainActivity.class.getDeclaredMethod("select",FacePath.Box.class,boolean.class);select.setAccessible(true);select.invoke(a,faces.get(0),false);((Button)field(a,"track")).performClick();
   }catch(ReflectiveOperationException e){throw new RuntimeException(e);}});
   long deadline=SystemClock.elapsedRealtime()+60000;AtomicBoolean done=new AtomicBoolean();
   while(SystemClock.elapsedRealtime()<deadline){scenario.onActivity(a->done.set(!(boolean)field(a,"busy")));if(done.get())break;SystemClock.sleep(100);}assertTrue("selected analysis finished",done.get());waitReady(scenario);
   scenario.onActivity(a->{MosaicTimeline t=(MosaicTimeline)field(a,"timeline");assertFalse(t.allFaces);assertEquals(t.endMs,t.analysedEndMs);assertFalse("selected face remains masked",t.boxesAt(1500).isEmpty());assertTrue(((Button)field(a,"save")).isEnabled());});
  }
 }
 @Test public void openAnalyseReplayUi()throws Exception{
  File input=fixture("faces.mp4");
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   scenario.onActivity(a->a.onActivityResult(1001,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(input))));
   waitReady(scenario);
   scenario.onActivity(a->{((RadioButton)field(a,"allMode")).performClick();});waitReady(scenario);
   scenario.onActivity(a->{assertTrue(((Button)field(a,"track")).isEnabled());((Button)field(a,"track")).performClick();});
   long deadline=SystemClock.elapsedRealtime()+60000;AtomicBoolean done=new AtomicBoolean();
   while(SystemClock.elapsedRealtime()<deadline){scenario.onActivity(a->done.set(!(boolean)field(a,"busy")));if(done.get())break;SystemClock.sleep(100);}assertTrue("analysis finished",done.get());waitReady(scenario);
   scenario.onActivity(a->{MosaicTimeline t=(MosaicTimeline)field(a,"timeline");assertEquals(t.endMs,t.analysedEndMs);assertFalse("real bundled face model finds faces",t.boxesAt(500).isEmpty());assertTrue(((Button)field(a,"save")).isEnabled());((Button)field(a,"play")).performClick();});
   SystemClock.sleep(1000);scenario.onActivity(a->{assertTrue((boolean)field(a,"playing"));((Button)field(a,"play")).performClick();});waitReady(scenario);
   InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,new FileOutputStream(new File(context.getExternalFilesDir(null),"editor-phone.png")));
  }
 }
 void waitReady(ActivityScenario<MainActivity> s){long end=SystemClock.elapsedRealtime()+30000;AtomicBoolean ready=new AtomicBoolean();while(SystemClock.elapsedRealtime()<end){s.onActivity(a->ready.set(!(boolean)field(a,"busy")&&(boolean)field(a,"previewReady")));if(ready.get())return;SystemClock.sleep(100);}fail("preview ready timeout");}
}

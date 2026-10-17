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
   MediaExtractor sourceAudio=new MediaExtractor();sourceAudio.setDataSource(input.getPath());
   for(int i=0;i<sourceAudio.getTrackCount();i++)if(sourceAudio.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")){sourceAudio.selectTrack(i);android.util.Log.i("MosaicAudioTest","first AAC timestamp us="+sourceAudio.getSampleTime());break;}
   sourceAudio.release();
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
   MediaExtractor e=new MediaExtractor();e.setDataSource(output.getPath());boolean audio=false;
   for(int i=0;i<e.getTrackCount();i++)if(e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")){
    audio=true;e.selectTrack(i);int packets=0;long first=-1,last=-1;java.nio.ByteBuffer bytes=java.nio.ByteBuffer.allocateDirect(262144);
    while(true){bytes.clear();int count=e.readSampleData(bytes,0);if(count<0)break;long pts=e.getSampleTime();assertTrue("non-negative audio timestamp",pts>=0);if(first<0)first=pts;last=pts;packets++;e.advance();}
    assertTrue("AAC samples copied",packets>80);assertTrue("audio starts at video start",first<50000);assertTrue("audio reaches video end",last>1900000);
   }
   e.release();assertTrue("audio track copied "+name,audio);
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
 @Test public void manualCornersAndTimelineControls()throws Exception{
  File input=fixture("sample.mp4");
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   scenario.onActivity(a->{((RadioButton)field(a,"manualMode")).performClick();a.onActivityResult(1001,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(input)));});waitReady(scenario);
   InstrumentationRegistry.getInstrumentation().waitForIdleSync();
   scenario.onActivity(a->{
    ((Button)field(a,"addManual")).performClick();android.view.View frame=(android.view.View)field(a,"frame");android.graphics.RectF area=(android.graphics.RectF)field(frame,"area");
    assertTrue("video preview laid out",area.width()>0&&area.height()>0);
    drag(frame,area.left+area.width()*.15f,area.top+area.height()*.2f,area.left+area.width()*.5f,area.top+area.height()*.45f);
    MosaicTimeline t=(MosaicTimeline)field(a,"timeline");assertEquals(1,t.manualTracks().size());assertEquals(1,t.manualTracks().get(0).size());
    assertTrue("manual mode saves without AI",((Button)field(a,"save")).isEnabled());assertFalse(((Button)field(a,"track")).isEnabled());
    android.view.View band=(android.view.View)field(a,"band");tap(band,band.getWidth()*.5f,band.getHeight()*.6f);
   });waitReady(scenario);InstrumentationRegistry.getInstrumentation().waitForIdleSync();
   scenario.onActivity(a->{
    long ms=(long)field(a,"currentMs");assertTrue("timeline seeks",ms>900&&ms<1100);
    MosaicTimeline t=(MosaicTimeline)field(a,"timeline");ManualTrack track=t.manualTracks().get(0);FacePath.Box first=track.points().firstEntry().getValue();
    android.view.View frame=(android.view.View)field(a,"frame");android.graphics.RectF area=(android.graphics.RectF)field(frame,"area");
    drag(frame,area.left+(first.x+first.w)*area.width(),area.top+(first.y+first.h)*area.height(),area.left+(first.x+first.w+.12f)*area.width(),area.top+(first.y+first.h+.15f)*area.height());
    assertEquals("resize creates second point",2,track.size());assertEquals("first point not resized",.35f,track.points().firstEntry().getValue().w,.01f);
    assertEquals("new point wider",.47f,track.at(ms,t.cuts()).w,.015f);assertEquals("new point taller",.40f,track.at(ms,t.cuts()).h,.015f);
    FacePath.Box middle=track.at(ms/2,t.cuts());assertTrue("intermediate width interpolates",middle.w>.35f&&middle.w<.47f);
    android.view.View band=(android.view.View)field(a,"band");float density=a.getResources().getDisplayMetrics().density;
    drag(band,band.getWidth()-14*density,46*density,14*density+(band.getWidth()-28*density)*.8f,46*density);
    assertTrue("timeline end handle trims interval",track.end()>1500&&track.end()<1700);
    ((Button)field(a,"undoManual")).performClick();assertEquals("undo restores interval",2000,t.manualTracks().get(0).end());
    t.cut(1100);tap(band,14*density+(band.getWidth()-28*density)*.65f,46*density);
   });
   waitReady(scenario);InstrumentationRegistry.getInstrumentation().waitForIdleSync();
   scenario.onActivity(a->{
    MosaicTimeline t=(MosaicTimeline)field(a,"timeline");assertTrue("old shot box not carried across cut",t.boxesAt((long)field(a,"currentMs")).isEmpty());
    ((Button)field(a,"pinPoint")).performClick();android.view.View frame=(android.view.View)field(a,"frame");android.graphics.RectF area=(android.graphics.RectF)field(frame,"area");
    drag(frame,area.left+area.width()*.25f,area.top+area.height()*.4f,area.left+area.width()*.7f,area.top+area.height()*.6f);
    assertEquals("new shot can be drawn into existing track",3,t.manualTracks().get(0).size());assertFalse(t.boxesAt((long)field(a,"currentMs")).isEmpty());
   });
   InstrumentationRegistry.getInstrumentation().waitForIdleSync();
   Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"manual-editor.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();
  }
 }
 void tap(android.view.View view,float x,float y){drag(view,x,y,x,y);}
 void drag(android.view.View view,float x,float y,float right,float bottom){
  long now=SystemClock.uptimeMillis();int step=0;for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP}){
   android.view.MotionEvent e=android.view.MotionEvent.obtain(now,now+20*step++,action,action==android.view.MotionEvent.ACTION_DOWN?x:right,action==android.view.MotionEvent.ACTION_DOWN?y:bottom,0);view.dispatchTouchEvent(e);e.recycle();
  }
 }
 @Test public void manualMovingMaskBurnsIntoExportWithAudio()throws Exception{
  File input=fixture("sample.mp4"),output=new File(context.getFilesDir(),"manual-output.mp4");MosaicTimeline t=new MosaicTimeline();t.manualOnly=true;t.strength=100;t.margin=0;t.endMs=2000;
  ManualTrack track=new ManualTrack(1,false,250,1500);track.put(250,new FacePath.Box(.1f,.15f,.2f,.3f,-1));track.put(1250,new FacePath.Box(.5f,.25f,.35f,.5f,-1));t.addManual(track);
  MosaicExport.Result result=MosaicExport.run(context,Uri.fromFile(input),output,t,new AtomicBoolean(),p->{});assertTrue(result.audio);assertTrue(result.frames>40);
  MediaMetadataRetriever original=new MediaMetadataRetriever(),rendered=new MediaMetadataRetriever();original.setDataSource(input.getPath());rendered.setDataSource(output.getPath());
  for(long ms:new long[]{100,750,1700}){
   Bitmap a=original.getFrameAtTime(ms*1000,MediaMetadataRetriever.OPTION_CLOSEST),b=rendered.getFrameAtTime(ms*1000,MediaMetadataRetriever.OPTION_CLOSEST);
   if(ms==750){FacePath.Box box=t.boxesAt(ms).get(0);assertTrue("moving and resizing manual mask burned into output",error(a,b,box.x+.02f,box.y+.02f,box.x+box.w-.02f,box.y+box.h-.02f)>12);assertTrue("outside moving mask preserved",error(a,b,.02f,.6f,.15f,.9f)<12);}
   else assertTrue("outside manual interval remains original",error(a,b,.1f,.1f,.9f,.9f)<12);a.recycle();b.recycle();
  }
  original.release();rendered.release();
 }

 void waitReady(ActivityScenario<MainActivity> s){long end=SystemClock.elapsedRealtime()+30000;AtomicBoolean ready=new AtomicBoolean();while(SystemClock.elapsedRealtime()<end){s.onActivity(a->ready.set(!(boolean)field(a,"busy")&&(boolean)field(a,"previewReady")));if(ready.get())return;SystemClock.sleep(100);}fail("preview ready timeout");}
}

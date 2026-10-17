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
    ((Button)field(a,"addManual")).performClick();assertTrue("unified add button selects an automatic anchor",(boolean)field(a,"selectingManually"));
    android.view.View frame=(android.view.View)field(a,"frame");android.graphics.RectF area=(android.graphics.RectF)field(frame,"area");FacePath.Box face=faces.get(0);
    drag(frame,area.left+face.x*area.width(),area.top+face.y*area.height(),area.left+(face.x+face.w)*area.width(),area.top+(face.y+face.h)*area.height());
    assertTrue("drawn region can start automatic tracking",((Button)field(a,"track")).isEnabled());assertTrue(((MosaicTimeline)field(a,"timeline")).manualTracks().isEmpty());((Button)field(a,"track")).performClick();
   }catch(Exception e){throw new RuntimeException(e);}});
   long deadline=SystemClock.elapsedRealtime()+60000;AtomicBoolean done=new AtomicBoolean();
   while(SystemClock.elapsedRealtime()<deadline){scenario.onActivity(a->done.set(!(boolean)field(a,"busy")));if(done.get())break;SystemClock.sleep(100);}assertTrue("selected analysis finished",done.get());waitReady(scenario);
   scenario.onActivity(a->{MosaicTimeline t=(MosaicTimeline)field(a,"timeline");assertFalse(t.allFaces);assertEquals(t.endMs,t.analysedEndMs);assertFalse("selected face remains masked",t.boxesAt(1500).isEmpty());assertTrue(((Button)field(a,"save")).isEnabled());
    int count=t.samples().size();rangeDrag(a,true,0,.2f);rangeDrag(a,false,1,.8f);assertTrue("selected-face automatic range trims",t.startMs>500&&t.endMs<2500);assertTrue(t.canExport());assertEquals(count,t.samples().size());assertTrue(t.boxesAt(200).isEmpty());
   });
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
    Button add=(Button)field(a,"addManual");android.graphics.Rect visible=new android.graphics.Rect();assertTrue("manual add button visible before scrolling",add.getGlobalVisibleRect(visible));assertTrue("manual add button fully visible",visible.height()>=add.getHeight()-2);
    add.performClick();android.view.View frame=(android.view.View)field(a,"frame");android.graphics.RectF area=(android.graphics.RectF)field(frame,"area");
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
    option(a,10);android.view.View frame=(android.view.View)field(a,"frame");android.graphics.RectF area=(android.graphics.RectF)field(frame,"area");
    drag(frame,area.left+area.width()*.25f,area.top+area.height()*.4f,area.left+area.width()*.7f,area.top+area.height()*.6f);
    assertEquals("new shot can be drawn into existing track",3,t.manualTracks().get(0).size());assertFalse(t.boxesAt((long)field(a,"currentMs")).isEmpty());
    android.view.View band=(android.view.View)field(a,"band");Bitmap strip=Bitmap.createBitmap(band.getWidth(),band.getHeight(),Bitmap.Config.ARGB_8888);band.draw(new android.graphics.Canvas(strip));float density=a.getResources().getDisplayMetrics().density;TimelineViewport viewport=(TimelineViewport)field(a,"viewport");int px=Math.round(14*density+viewport.fraction((long)field(a,"currentMs"))*(band.getWidth()-28*density));assertEquals("current point remains visible over playhead",0xffffd387,strip.getPixel(px,Math.round(46*density)));strip.recycle();
   });
   InstrumentationRegistry.getInstrumentation().waitForIdleSync();
   SystemClock.sleep(150); // Wait for a display frame after invalidate(), not just an idle message queue.
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

 @Test public void filmstripScrubsForwardBackwardBeforeReleaseAndKeepsPoints()throws Exception{
  File input=fixture("sample.mp4");final long[] touchStart={0},shown={-1};final Bitmap[] snapshot={null};
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   scenario.onActivity(a->{((RadioButton)field(a,"manualMode")).performClick();a.onActivityResult(1001,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(input)));});waitReady(scenario);waitFilmstrip(scenario);
   InstrumentationRegistry.getInstrumentation().waitForIdleSync();
   scenario.onActivity(a->{
    ((Button)field(a,"addManual")).performClick();android.view.View frame=(android.view.View)field(a,"frame");android.graphics.RectF area=(android.graphics.RectF)field(frame,"area");
    drag(frame,area.left+area.width()*.1f,area.top+area.height()*.1f,area.left+area.width()*.4f,area.top+area.height()*.4f);
    android.view.View film=(android.view.View)field(a,"filmstrip");assertTrue("filmstrip is visible",film.getHeight()>0&&film.isShown());
    android.util.LruCache<?,?> cache=(android.util.LruCache<?,?>)field(film,"cache");assertTrue("actual video thumbnails loaded",cache.size()>=3);
    touchStart[0]=SystemClock.uptimeMillis();filmTouch(a,touchStart[0],android.view.MotionEvent.ACTION_DOWN,.1f);filmTouch(a,touchStart[0],android.view.MotionEvent.ACTION_MOVE,.8f);
   });
   waitScrub(scenario,1400,1750);
   scenario.onActivity(a->{
    assertTrue("finger still down",(boolean)field(a,"scrubbing"));assertFalse("no play/pause needed",(boolean)field(a,"playing"));assertFalse("point edits wait for release",(boolean)field(a,"previewReady"));
    assertEquals("scrubbing does not insert keyframes",1,((MosaicTimeline)field(a,"timeline")).manualTracks().get(0).size());
    shown[0]=(long)field(a,"displayedFrameMs");snapshot[0]=((Bitmap)field(field(a,"frame"),"bitmap")).copy(Bitmap.Config.ARGB_8888,false);
   });
   MediaMetadataRetriever verifier=new MediaMetadataRetriever();verifier.setDataSource(input.getPath());Bitmap expected=verifier.getScaledFrameAtTime(shown[0]*1000,MediaMetadataRetriever.OPTION_CLOSEST,480,480);
   assertEquals(expected.getWidth(),snapshot[0].getWidth());assertEquals(expected.getHeight(),snapshot[0].getHeight());assertTrue("displayed pixels match requested video time before ACTION_UP",error(expected,snapshot[0],.02f,.02f,.98f,.98f)<3);expected.recycle();snapshot[0].recycle();verifier.release();
   scenario.onActivity(a->filmTouch(a,touchStart[0],android.view.MotionEvent.ACTION_MOVE,.25f));waitScrub(scenario,350,650);
   scenario.onActivity(a->{
    for(float f:new float[]{.9f,.1f,.7f,.2f,.85f,.63f})filmTouch(a,touchStart[0],android.view.MotionEvent.ACTION_MOVE,f);
    filmTouch(a,touchStart[0],android.view.MotionEvent.ACTION_UP,.63f);
   });waitReady(scenario);SystemClock.sleep(200);
   scenario.onActivity(a->{
    long ms=(long)field(a,"currentMs");assertTrue("release settles at latest requested time",ms>1200&&ms<1320);assertEquals(ms,(long)field(a,"displayedFrameMs"));assertFalse((boolean)field(a,"scrubbing"));
    ManualTrack t=((MosaicTimeline)field(a,"timeline")).manualTracks().get(0);assertEquals(1,t.size());
    android.view.View frame=(android.view.View)field(a,"frame");android.graphics.RectF area=(android.graphics.RectF)field(frame,"area");
    drag(frame,area.left+area.width()*.25f,area.top+area.height()*.25f,area.left+area.width()*.33f,area.top+area.height()*.29f);
    assertEquals("can add next manual point immediately after scrubbing",2,t.size());assertEquals(ms,(long)t.points().lastKey());
   });
   SystemClock.sleep(150);Bitmap capture=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"filmstrip-editor.png"))){capture.compress(Bitmap.CompressFormat.PNG,100,out);}capture.recycle();
   File next=fixture("faces.mp4");scenario.onActivity(a->a.onActivityResult(1001,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(next))));waitReady(scenario);waitFilmstrip(scenario);SystemClock.sleep(150);
   scenario.onActivity(a->{assertEquals("new source duration",3000,(long)field(a,"durationMs"));assertEquals("old scrub callback cannot replace new source",0,(long)field(a,"displayedFrameMs"));assertTrue(((MosaicTimeline)field(a,"timeline")).manualTracks().isEmpty());});
   capture=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"filmstrip-face-editor.png"))){capture.compress(Bitmap.CompressFormat.PNG,100,out);}capture.recycle();
  }
 }
 @Test public void automaticRangeHandlesRetainDataAndGateUnanalysedExpansion()throws Exception{
  File input=fixture("faces.mp4");
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   scenario.onActivity(a->{((RadioButton)field(a,"allMode")).performClick();a.onActivityResult(1001,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(input)));});waitReady(scenario);
   scenario.onActivity(a->{
    rangeDrag(a,true,0,.2f);rangeDrag(a,false,1,.8f);MosaicTimeline t=(MosaicTimeline)field(a,"timeline");
    assertEquals(600,t.startMs,5);assertEquals(2400,t.endMs,5);assertTrue(t.manualTracks().isEmpty());assertFalse(t.canExport());((Button)field(a,"track")).performClick();
   });waitFinished(scenario);
   scenario.onActivity(a->{
    MosaicTimeline t=(MosaicTimeline)field(a,"timeline");assertTrue(t.canExport());assertFalse(t.autoBoxesAt(1500).isEmpty());assertTrue(t.autoBoxesAt(300).isEmpty());assertTrue(t.autoBoxesAt(2500).isEmpty());
    int samples=t.samples().size();long analysed=t.analysedEndMs;rangeDrag(a,false,.8f,.7f);assertEquals(2100,t.endMs,5);assertEquals(samples,t.samples().size());assertEquals(analysed,t.analysedEndMs);assertTrue(t.canExport());
    ((Button)field(a,"undoManual")).performClick();assertEquals(2400,t.endMs,5);assertEquals(samples,t.samples().size());
    rangeDrag(a,true,.2f,.1f);assertFalse("expansion before first analysed frame blocks saving",t.canExport());assertFalse(((Button)field(a,"save")).isEnabled());
    ((Button)field(a,"undoManual")).performClick();assertTrue(t.canExport());
    rangeDrag(a,false,.8f,.95f);assertFalse("unanalysed tail blocks saving",t.canExport());assertFalse(((Button)field(a,"save")).isEnabled());((Button)field(a,"track")).performClick();
   });waitFinished(scenario);waitFilmstripComplete(scenario);
   scenario.onActivity(a->{MosaicTimeline t=(MosaicTimeline)field(a,"timeline");assertTrue(t.canExport());assertEquals(2850,t.endMs,5);assertFalse(t.autoBoxesAt(2700).isEmpty());assertTrue(t.autoBoxesAt(2950).isEmpty());});
   capture("automatic-range-editor.png");
  }
 }
 @Test public void longPressMenuDeletesSplitsAndUndoRestores()throws Exception{
  File input=fixture("sample.mp4");final long[] down={0};
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   scenario.onActivity(a->{((RadioButton)field(a,"manualMode")).performClick();a.onActivityResult(1001,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(input)));});waitReady(scenario);
   scenario.onActivity(a->{((Button)field(a,"addManual")).performClick();android.view.View frame=(android.view.View)field(a,"frame");android.graphics.RectF area=(android.graphics.RectF)field(frame,"area");drag(frame,area.left+area.width()*.1f,area.top+area.height()*.1f,area.left+area.width()*.4f,area.top+area.height()*.4f);filmTouch(a,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_DOWN,.5f);filmTouch(a,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,.5f);});waitReady(scenario);
   scenario.onActivity(a->{option(a,10);assertEquals(2,((MosaicTimeline)field(a,"timeline")).manualTracks().get(0).size());down[0]=SystemClock.uptimeMillis();bandTouch(a,down[0],android.view.MotionEvent.ACTION_DOWN,.5f);});
   SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+300);waitReady(scenario);
   scenario.onActivity(a->{
    PopupMenu menu=(PopupMenu)field(a,"options");assertNotNull("long press opened menu",menu);assertTrue(menu.getMenu().findItem(11).isEnabled());
    bandTouch(a,down[0],android.view.MotionEvent.ACTION_UP,.5f);menu.getMenu().performIdentifierAction(11,0);menu.dismiss();MosaicTimeline t=(MosaicTimeline)field(a,"timeline");assertEquals(1,t.manualTracks().get(0).size());
    ((Button)field(a,"undoManual")).performClick();assertEquals(2,t.manualTracks().get(0).size());
    option(a,12);assertEquals(2,t.manualTracks().size());((Button)field(a,"undoManual")).performClick();assertEquals(1,t.manualTracks().size());
    option(a,13);assertTrue(t.manualTracks().isEmpty());((Button)field(a,"undoManual")).performClick();assertEquals(1,t.manualTracks().size());assertEquals(2,t.manualTracks().get(0).size());
   });waitFilmstripComplete(scenario);capture("simple-manual-editor.png");
  }
 }
 void option(MainActivity a,int id){
  ((Button)field(a,"more")).performClick();PopupMenu menu=(PopupMenu)field(a,"options");assertNotNull(menu);assertTrue("selected menu action enabled "+id,menu.getMenu().findItem(id).isEnabled());assertTrue(menu.getMenu().performIdentifierAction(id,0));menu.dismiss();
 }
 void rangeDrag(MainActivity a,boolean start,float from,float to){
  android.view.View band=(android.view.View)field(a,"band");float d=a.getResources().getDisplayMetrics().density;drag(band,14*d+(band.getWidth()-28*d)*from,46*d,14*d+(band.getWidth()-28*d)*to,46*d);
 }
 void bandTouch(MainActivity a,long down,int action,float fraction){
  android.view.View band=(android.view.View)field(a,"band");float d=a.getResources().getDisplayMetrics().density;android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,14*d+(band.getWidth()-28*d)*fraction,46*d,0);band.dispatchTouchEvent(event);event.recycle();
 }
 void waitFinished(ActivityScenario<MainActivity> scenario){long end=SystemClock.elapsedRealtime()+60000;AtomicBoolean done=new AtomicBoolean();while(SystemClock.elapsedRealtime()<end){scenario.onActivity(a->done.set(!(boolean)field(a,"busy")));if(done.get())break;SystemClock.sleep(100);}assertTrue("analysis completed",done.get());waitReady(scenario);}
 void waitFilmstripComplete(ActivityScenario<MainActivity> scenario){
  long end=SystemClock.elapsedRealtime()+30000;AtomicBoolean done=new AtomicBoolean();while(SystemClock.elapsedRealtime()<end){scenario.onActivity(a->{Object film=field(a,"filmstrip");android.util.LruCache<Long,Bitmap> cache=(android.util.LruCache<Long,Bitmap>)field(film,"cache");List<Long> desired=(List<Long>)field(film,"desired");boolean full=!desired.isEmpty();for(long key:desired)full&=cache.get(key)!=null;done.set(full);});if(done.get())return;SystemClock.sleep(100);}fail("visible thumbnails did not finish loading");
 }
 void capture(String name)throws Exception{SystemClock.sleep(150);Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();try(FileOutputStream output=new FileOutputStream(new File(context.getExternalFilesDir(null),name))){image.compress(Bitmap.CompressFormat.PNG,100,output);}image.recycle();}
 void filmTouch(MainActivity a,long down,int action,float fraction){
  android.view.View film=(android.view.View)field(a,"filmstrip");float density=a.getResources().getDisplayMetrics().density;
  android.view.MotionEvent e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,14*density+(film.getWidth()-28*density)*fraction,film.getHeight()*.5f,0);film.dispatchTouchEvent(e);e.recycle();
 }
 void waitScrub(ActivityScenario<MainActivity> scenario,long min,long max){
  long end=SystemClock.elapsedRealtime()+15000;AtomicBoolean ready=new AtomicBoolean();while(SystemClock.elapsedRealtime()<end){scenario.onActivity(a->{long ms=(long)field(a,"displayedFrameMs");ready.set((boolean)field(a,"scrubbing")&&ms>=min&&ms<=max);});if(ready.get())return;SystemClock.sleep(70);}fail("live scrub frame did not update while finger held down");
 }
 void waitFilmstrip(ActivityScenario<MainActivity> scenario){
  long end=SystemClock.elapsedRealtime()+20000;AtomicBoolean ready=new AtomicBoolean();while(SystemClock.elapsedRealtime()<end){scenario.onActivity(a->ready.set(((android.util.LruCache<?,?>)field(field(a,"filmstrip"),"cache")).size()>=3));if(ready.get())return;SystemClock.sleep(100);}fail("video thumbnails did not load");
 }

 void waitReady(ActivityScenario<MainActivity> s){long end=SystemClock.elapsedRealtime()+30000;AtomicBoolean ready=new AtomicBoolean();while(SystemClock.elapsedRealtime()<end){s.onActivity(a->ready.set(!(boolean)field(a,"busy")&&(boolean)field(a,"previewReady")));if(ready.get())return;SystemClock.sleep(100);}fail("preview ready timeout");}
}

package kr.ledoa.cut.aitest;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.webkit.WebView;
import android.widget.*;
import android.util.Base64;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import kr.ledoa.cut.MosaicCutActivity;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Actual WebView -> in-app editor -> MediaCodec export -> WebView/IndexedDB round trip. */
@RunWith(AndroidJUnit4.class)
public class CutIntegrationTest {
 final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
 WebView web;
 String js(String script)throws Exception{
  CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
  inst.runOnMainSync(()->web.evaluateJavascript(script,value->{result.set(value);done.countDown();}));
  assertTrue("JavaScript response",done.await(15,TimeUnit.SECONDS));return result.get();
 }
 void until(String expression)throws Exception{long end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end){if("true".equals(js("!!("+expression+")")))return;SystemClock.sleep(100);}fail("Timed out: "+expression);}
 MosaicActivity child()throws Exception{
  long end=SystemClock.elapsedRealtime()+30000;AtomicReference<MosaicActivity> found=new AtomicReference<>();
  while(SystemClock.elapsedRealtime()<end){inst.runOnMainSync(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a instanceof MosaicActivity)found.set((MosaicActivity)a);});if(found.get()!=null)return found.get();SystemClock.sleep(100);}throw new AssertionError("In-app mosaic editor did not open");
 }
 void ready(MosaicActivity a){long end=SystemClock.elapsedRealtime()+30000;AtomicBoolean ready=new AtomicBoolean();while(SystemClock.elapsedRealtime()<end){inst.runOnMainSync(()->ready.set(!(boolean)MosaicDeviceTest.field(a,"busy")&&(boolean)MosaicDeviceTest.field(a,"previewReady")));if(ready.get())return;SystemClock.sleep(100);}fail("Integrated preview not ready");}
 @Test public void selectedClipApplyReloadReeditCancelAndUndo()throws Exception{
  // Clear only this emulator test profile before opening the production entry activity.
  inst.getTargetContext().getSharedPreferences("mosaic-jobs-v1",0).edit().clear().commit();
  try(ActivityScenario<MosaicCutActivity> scenario=ActivityScenario.launch(MosaicCutActivity.class)){
   scenario.onActivity(a->web=(WebView)MosaicDeviceTest.field(a,"web"));
   until("typeof state!=='undefined' && window.LedoaMosaic && window.LedoaMosaicEditor && !v038Restoring");
   js("(async()=>{const db=await v038Db();const t=db.transaction(['project','media'],'readwrite');t.objectStore('project').clear();t.objectStore('media').clear();db.close();state.clips=[];state.texts=[];localStorage.removeItem('ledoa-mosaic-pending-v1');recalc();renderAll();})()");
   byte[] bytes;try(InputStream in=inst.getContext().getAssets().open("sample.mp4");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[32768];int n;while((n=in.read(b))>0)out.write(b,0,n);bytes=out.toByteArray();}
   String encoded=Base64.encodeToString(bytes,Base64.NO_WRAP);
   js("(()=>{const s=atob('"+encoded+"'),b=new Uint8Array(s.length);for(let i=0;i<s.length;i++)b[i]=s.charCodeAt(i);const dt=new DataTransfer();dt.items.add(new File([b],'mobile-original.mp4',{type:'video/mp4'}));const input=document.getElementById('mediaInput');input.files=dt.files;input.dispatchEvent(new Event('change',{bubbles:true}));})()");
   until("state.lastImportResult?.added===1&&state.lastImportResult.saved");
   js("(()=>{const c=state.clips[0];c.trimStart=.2;c.trimEnd=1.8;c.speed=1.25;c.volume=.4;state.selectedClipId=c.id;state.texts.push({id:'kept',kind:'caption',text:'기존 자막',start:.1,end:1,bgOpacity:0,font:'Gowun Dodum',size:42});recalc();seek(.3);renderAll();openSheet('clipSheet');document.getElementById('mosaicEditBtn').click();})()");
   MosaicActivity a=child();ready(a);assertEquals("true",js("LedoaMosaicEditor.busy"));
   inst.runOnMainSync(()->((RadioButton)MosaicDeviceTest.field(a,"manualMode")).performClick());ready(a);
   inst.runOnMainSync(()->{
    ((Button)MosaicDeviceTest.field(a,"addManual")).performClick();android.view.View frame=(android.view.View)MosaicDeviceTest.field(a,"frame");RectF area=(RectF)MosaicDeviceTest.field(frame,"area");
    new MosaicDeviceTest().drag(frame,area.left+area.width()*.15f,area.top+area.height()*.2f,area.left+area.width()*.5f,area.top+area.height()*.6f);
    MosaicTimeline t=(MosaicTimeline)MosaicDeviceTest.field(a,"timeline");assertEquals(1,t.manualTracks().size());assertEquals(1800,t.manualTracks().get(0).end());assertTrue(((Button)MosaicDeviceTest.field(a,"save")).isEnabled());
    ((Button)MosaicDeviceTest.field(a,"save")).performClick();
   });
   until("!LedoaMosaicEditor.busy&&!!state.clips[0].mosaic");
   assertEquals("true",js("state.clips[0].trimStart===.2&&state.clips[0].trimEnd===1.8&&state.clips[0].speed===1.25&&state.clips[0].volume===.4&&state.texts[0].text==='기존 자막'&&state.texts[0].bgOpacity===0"));
   String token=new JSONTokener(js("state.clips[0].mosaic.token")).nextValue().toString();assertTrue(new File(inst.getTargetContext().getFilesDir(),"mosaic-edits-v1/"+token+".json").isFile());
   js("LedoaMosaicEditor.open()");MosaicActivity again=child();ready(again);
   inst.runOnMainSync(()->{MosaicTimeline t=(MosaicTimeline)MosaicDeviceTest.field(again,"timeline");assertTrue(t.manualOnly);assertEquals(1,t.manualTracks().size());assertEquals(1,t.manualTracks().get(0).size());assertEquals(1800,t.manualTracks().get(0).end());again.onBackPressed();});
   until("!LedoaMosaicEditor.busy");assertEquals(JSONObject.quote(token),js("state.clips[0].mosaic.token"));
   js("document.getElementById('undoBtn').click()");assertEquals("true",js("!state.clips[0].mosaic&&state.clips[0].file.name==='mobile-original.mp4'&&state.texts[0].text==='기존 자막'"));
   js("LedoaMosaicEditor.open()");MosaicActivity last=child();ready(last);
   inst.runOnMainSync(()->((RadioButton)MosaicDeviceTest.field(last,"manualMode")).performClick());ready(last);
   inst.runOnMainSync(()->{((Button)MosaicDeviceTest.field(last,"addManual")).performClick();android.view.View frame=(android.view.View)MosaicDeviceTest.field(last,"frame");RectF area=(RectF)MosaicDeviceTest.field(frame,"area");new MosaicDeviceTest().drag(frame,area.left+area.width()*.2f,area.top+area.height()*.2f,area.left+area.width()*.6f,area.top+area.height()*.7f);((Button)MosaicDeviceTest.field(last,"save")).performClick();});
   until("!LedoaMosaicEditor.busy&&!!state.clips[0].mosaic");js("location.reload()");until("typeof state!=='undefined'&&!v038Restoring&&!!state.clips[0]?.mosaic&&state.clips[0].file?.size>1000");
   assertEquals("true",js("state.texts[0].bgOpacity===0&&state.clips[0].speed===1.25&&JSON.parse(LedoaMosaic.session(state.clips[0].mosaic.token)).available"));
   inst.getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,new FileOutputStream(new File(inst.getTargetContext().getExternalFilesDir(null),"cut-mosaic-integrated.png")));
  }
 }
 @Test public void recipeRoundTripPreservesManualAndAutomaticGeometry()throws Exception{
  MosaicTimeline t=new MosaicTimeline();t.allFaces=true;t.rangeAuto(200,1800,2000);t.analysedEndMs=1800;t.strength=55;t.margin=12;t.put(500,java.util.List.of(new FacePath.Box(.1f,.2f,.3f,.4f,-1)));
  ManualTrack m=new ManualTrack(3,false,300,1700);m.put(300,new FacePath.Box(.1f,.2f,.2f,.3f,-1));m.put(1500,new FacePath.Box(.4f,.3f,.4f,.5f,-1));t.addManual(m);
  File f=new File(inst.getTargetContext().getCacheDir(),"roundtrip.json");MosaicProject.save(f,t,2000,3);MosaicTimeline restored=new MosaicTimeline();assertEquals(3,MosaicProject.restore(f,restored,2000));assertEquals(2,restored.boxesAt(700).size());assertEquals(55,restored.strength);assertEquals(2,restored.manual(3).size());
  String before=restored.boxesAt(700).toString();try{MosaicProject.restore(f,restored,3000);fail("different source rejected");}catch(IOException expected){}assertEquals(before,restored.boxesAt(700).toString());
 }
}

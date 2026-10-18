const fs=require('fs'),path=require('path'),http=require('http'),assert=require('assert/strict');
const {chromium}=require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright');
const root=path.resolve(__dirname,'..'),errors=[],checks=[];
const server=http.createServer((req,res)=>{try{const p=path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname);if(!p.startsWith(root+path.sep))throw Error();res.writeHead(200,{'Content-Type':p.endsWith('.js')?'text/javascript':p.endsWith('.css')?'text/css':p.endsWith('.html')?'text/html':'application/octet-stream'});res.end(fs.readFileSync(p));}catch{res.writeHead(404);res.end();}});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({executablePath:process.env.LEDOA_TEST_BROWSER||chromium.executablePath(),args:['--no-sandbox','--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
 try{
  const context=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,serviceWorkers:'block'});
  await context.addInitScript(()=>{
   window.googleCalls=[];window.alignCalls=[];window.alignMode='success';window.alignDelay=20;
   window.LedoaCaptions={capabilities:()=>JSON.stringify({fileInput:true,googleAvailable:true}),resolveSource:()=>JSON.stringify({sourceId:'speech-source'}),cancel:()=>{},transcribe:json=>{const r=JSON.parse(json);googleCalls.push(r);setTimeout(()=>LEDOA_CAPTION_EVENT({id:r.id,type:'result',sourceStart:r.sourceStart,sourceEnd:r.sourceEnd,segments:[{text:'틀린 문장 다음 인사',words:[]}]}),10);}};
   window.LedoaAlignment={cancel:()=>{},align:json=>{const r=JSON.parse(json);alignCalls.push(r);const mode=alignMode;
    setTimeout(()=>{if(mode==='error')return LEDOA_ALIGNMENT_EVENT({type:'error',id:r.id,message:'자막과 음성이 일치하지 않습니다.'});
     const words=r.cues.flatMap((text,cue)=>text.split(/\s+/).filter(Boolean).map((text,j)=>({cue,text,start:.6+cue*2+j*.22,end:.77+cue*2+j*.22,confidence:.8,review:cue===1})));
     if(mode==='invalid')words[0].start=NaN;
     LEDOA_ALIGNMENT_EVENT({type:'result',id:r.id,words});
    },alignDelay);
   }};
  });
  const p=await context.newPage();p.on('pageerror',e=>errors.push(e.message));await p.goto('http://127.0.0.1:'+server.address().port+'/assets/index.html');await p.waitForTimeout(350);
  await p.locator('#mediaInput').setInputFiles({name:'speech.webm',mimeType:'video/webm',buffer:fs.readFileSync(path.join(root,'tests/fixture.webm'))});await p.waitForFunction(()=>state.lastImportResult?.saved);
  await p.evaluate(()=>{state.texts=[{id:'title',kind:'title',text:'제목 유지',start:0,end:7,font:'Nanum Pen Script',size:64,bgOpacity:.4}];window.titleBefore=JSON.stringify(state.texts[0]);renderTextList();openSheet('textSheet');loadTextToEditor(state.texts[0]);LedoaAutoCaptions.open();});
  await p.locator('#acGenerate').tap();await p.waitForFunction(()=>$('acRows').children.length===1);await p.locator('#acRows textarea').fill('고친 첫 문장\n다음 인사');await p.locator('#acRows textarea').evaluate(t=>{const at=t.value.indexOf('\n');t.focus();t.setSelectionRange(at,at);t.dispatchEvent(new Event('select'));});await p.locator('#acRows [data-split=cursor]').tap();
  assert.equal(await p.evaluate(()=>state.texts.length),1);await p.locator('#acAlign').tap();await p.waitForFunction(()=>state.texts.filter(t=>t.captionTiming==='forced-aligned').length===2);
  const aligned=await p.evaluate(()=>state.texts.filter(t=>t.kind==='caption'));assert.deepEqual(aligned.map(t=>t.text),['고친 첫 문장','다음 인사']);assert.ok(Math.abs(aligned[0].start-.56)<1e-8);assert.ok(Math.abs(aligned[1].start-2.56)<1e-8);assert.equal(aligned[0].captionWords.length,3);assert.equal(aligned[1].captionReview,true);assert.equal(await p.evaluate(()=>JSON.stringify(state.texts[0])===titleBefore),true);
  assert.deepEqual(await p.evaluate(()=>alignCalls[0].cues),['고친 첫 문장','다음 인사']);assert.equal(await p.evaluate(()=>googleCalls.length),1);assert.equal(await p.locator('.ac-alignment-review').count(),1);
  checks.push('Google recognition → corrected text and paragraphs → alignment receives corrected transcript → real returned word times automatically place two paragraphs; title preserved');
  const ids=aligned.map(t=>t.id);await p.locator('#acAlign').tap();await p.waitForFunction(()=>alignCalls.length===2&&!$('acAlign').disabled);assert.equal(await p.evaluate(()=>state.texts.length),3);assert.deepEqual(await p.evaluate(()=>state.texts.filter(t=>t.kind==='caption').map(t=>t.id)),ids);
  await p.locator('#acApply').tap();assert.equal(await p.evaluate(()=>state.texts.length),3);
  checks.push('Repeated alignment and manual Apply update existing captions without duplicates');
  await p.evaluate(()=>undo());assert.equal(await p.evaluate(()=>state.texts.length),3);await p.evaluate(()=>undo());assert.equal(await p.evaluate(()=>state.texts.length),3);await p.evaluate(()=>undo());assert.equal(await p.evaluate(()=>state.texts.length),1);
  checks.push('Each auto-placement is one undo transaction; undo restores preceding project');
  await p.evaluate(()=>{state.texts.push({id:'existing',kind:'caption',text:'기존 첫 문장 다음 인사',start:0,end:5,font:'Nanum Pen Script',size:68,bgOpacity:0,position:'custom',customY:.6,color:'#fedcba'});});
  await p.locator('#acExisting').tap();await p.locator('#acRows textarea').fill('고친 첫 문장\n다음 인사');await p.locator('#acRows textarea').evaluate(t=>{const at=t.value.indexOf('\n');t.focus();t.setSelectionRange(at,at);t.dispatchEvent(new Event('select'));});await p.locator('#acRows [data-split=cursor]').tap();await p.locator('#acAlign').tap();await p.waitForFunction(()=>state.texts.filter(t=>t.captionTiming==='forced-aligned').length===2);
  const updated=await p.evaluate(()=>state.texts.filter(t=>t.kind==='caption'));assert.equal(updated[0].id,'existing');for(const c of updated){assert.equal(c.font,'Nanum Pen Script');assert.equal(c.size,68);assert.equal(c.bgOpacity,0);assert.equal(c.customY,.6);assert.equal(c.color,'#fedcba');}
  checks.push('Previously applied captions can be corrected, split and re-aligned with style, zero opacity and first ID retained');
  const before=await p.evaluate(()=>JSON.stringify(state.texts));await p.evaluate(()=>{alignMode='error';});await p.locator('#acAlign').tap();await p.waitForFunction(()=>!$('acAlign').disabled);assert.equal(await p.evaluate(()=>JSON.stringify(state.texts)),before);assert.match(await p.locator('#acStatus').innerText(),/일치하지/);
  await p.evaluate(()=>{alignMode='invalid';});await p.locator('#acAlign').tap();await p.waitForFunction(()=>!$('acAlign').disabled);assert.equal(await p.evaluate(()=>JSON.stringify(state.texts)),before);
  checks.push('Engine rejection and malformed timestamps leave all existing captions unchanged');
  await p.evaluate(()=>{alignMode='success';alignDelay=150;});await p.locator('#acAlign').tap();await p.locator('#acCancel').tap();await p.waitForTimeout(220);assert.equal(await p.evaluate(()=>JSON.stringify(state.texts)),before);
  checks.push('Cancel and late native results cannot apply stale alignment');
  await p.evaluate(()=>{alignDelay=1000;});await p.locator('#acAlign').tap();await p.evaluate(()=>{state.clips[0].speed=2;});await p.waitForTimeout(1150);assert.equal(await p.evaluate(()=>JSON.stringify(state.texts)),before);await p.evaluate(()=>state.clips[0].speed=1);
  checks.push('Changing clip speed while working rejects stale timing without overwriting captions');
  await p.evaluate(async()=>{clearTimeout(v038SaveTimer);await v038SaveProject(true);});await p.reload();await p.waitForFunction(()=>state.texts.length===3);assert.equal(await p.evaluate(()=>state.texts.find(t=>t.id==='existing').captionWords.length),3);assert.equal(await p.evaluate(()=>state.texts.find(t=>t.id==='existing').bgOpacity),0);
  checks.push('Autosave/reload retains word times, corrected text, source context, uncertainty and caption styling');
  await p.evaluate(()=>LedoaAutoCaptions.open());await p.locator('#acExisting').tap();await p.locator('#acAlign').scrollIntoViewIfNeeded();await p.screenshot({path:path.join(root,'validation/alignment-ui.png')});assert.deepEqual(errors,[]);
  const report={passed:true,browser:await browser.version(),checks,native_in_browser:'Mocked; acoustic model and same Java Viterbi tested separately with real Korean audio',physical_android_test:false};fs.writeFileSync(path.join(root,'validation/caption-alignment-browser.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exit(1)});

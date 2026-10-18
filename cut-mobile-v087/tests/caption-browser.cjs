const fs=require('fs'),path=require('path'),http=require('http'),assert=require('assert/strict');
const {chromium}=require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES?process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright':'playwright');
const root=path.resolve(__dirname,'..'),errors=[],checks=[];
const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.ttf':'font/ttf','.png':'image/png'};
const server=http.createServer((req,res)=>{try{const p=path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname);if(!p.startsWith(root+path.sep))throw Error();res.writeHead(200,{'Content-Type':types[path.extname(p)]||'application/octet-stream','Cache-Control':'no-store'});res.end(fs.readFileSync(p));}catch{res.writeHead(404);res.end();}});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({executablePath:process.env.LEDOA_TEST_BROWSER||chromium.executablePath(),args:['--no-sandbox','--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
 try{
  const context=await browser.newContext({viewport:{width:390,height:844},serviceWorkers:'block',acceptDownloads:true});
  await context.addInitScript(()=>{
   window.mockRequests=[];window.mockCancels=0;window.mockMode='result';
   window.LedoaCaptions={capabilities:()=>JSON.stringify({sdk:36,googleAvailable:true,fileInput:true}),resolveSource:()=>JSON.stringify({sourceId:'fixture-source'}),cancel:()=>mockCancels++,
    transcribe:json=>{const r=JSON.parse(json);mockRequests.push(r);if(mockMode==='wait')return;
      setTimeout(()=>LEDOA_CAPTION_EVENT(mockMode==='error'?{id:r.id,type:'error',message:'시험용 네트워크 오류'}:{id:r.id,type:'result',sourceStart:r.sourceStart,sourceEnd:r.sourceEnd,segments:[{text:'제네시스 자동차 이야기입니다.',words:[{text:'제네시스',start:.2},{text:'자동차',start:1.1},{text:'이야기입니다.',start:2.1}]}]}),30);
    }};
  });
  const p=await context.newPage();p.on('pageerror',e=>errors.push(e.message));await p.goto('http://127.0.0.1:'+server.address().port+'/assets/index.html');await delay(500);
  await p.locator('#mediaInput').setInputFiles({name:'speech-fixture.webm',mimeType:'video/webm',buffer:fs.readFileSync(path.join(root,'tests/fixture.webm'))});
  await p.waitForFunction(()=>state.lastImportResult?.saved);
  await p.evaluate(()=>{const c=state.clips[0];c.trimStart=1;c.trimEnd=7;c.speed=2;state.selectedClipId=c.id;recalc();state.texts.push({id:'existing-caption',text:'기존 자막',kind:'caption',start:0,end:1,font:'Gowun Dodum',size:42,bgOpacity:0,position:'shorts-safe'},{id:'existing-title',text:'기존 제목',kind:'title',start:0,end:3});renderTextList();openSheet('textSheet');});
  await p.locator('#autoCaptionBtn').click();assert.ok(await p.locator('#autoCaptionSheet').isVisible());
  await p.locator('#acGenerate').click();await p.waitForFunction(()=>$('acReview')&&!$('acReview').classList.contains('hidden'));
  assert.equal(await p.evaluate(()=>state.texts.length),2);assert.equal(await p.evaluate(()=>mockRequests[0].sourceStart),1);assert.equal(await p.evaluate(()=>mockRequests[0].sourceEnd),7);
  assert.equal(Number(await p.locator('.ac-cue input').first().inputValue()),.1);
  checks.push('Mock native result mapped through trim and 2x speed; existing captions untouched before Apply');
  await p.locator('.ac-cue textarea').first().fill('제네시스 수정된 자막');await p.locator('#acApply').click();
  const applied=await p.evaluate(()=>state.texts.filter(t=>t.captionEngine==='android-google'));
  assert.ok(applied.length>0);assert.equal(applied[0].text,'제네시스 수정된 자막');assert.equal(await p.evaluate(()=>state.texts.find(t=>t.id==='existing-caption').bgOpacity),0);
  assert.equal(await p.locator('#acApply').isEnabled(),false);await p.evaluate(()=>undo());assert.equal(await p.evaluate(()=>state.texts.length),2);
  checks.push('Reviewed text applied once, existing title and caption preserved, batch undo restores both');
  await p.locator('#acGenerate').click();await p.waitForFunction(()=>$('acReview')&&!$('acReview').classList.contains('hidden'));
  await p.evaluate(()=>{state.clips[0].speed=1;recalc();});await p.locator('#acApply').click();assert.match(await p.locator('#acStatus').innerText(),/変更|변경/);assert.equal(await p.evaluate(()=>state.texts.length),2);
  checks.push('Stale results rejected after clip speed changes');
  await p.locator('#acDiscard').click();await p.evaluate(()=>{mockMode='wait';});await p.locator('#acGenerate').click();await p.waitForFunction(()=>mockRequests.length===3);
  const late=await p.evaluate(()=>mockRequests.at(-1).id);await p.locator('#acCancel').click();await p.evaluate(id=>LEDOA_CAPTION_EVENT({type:'result',id,sourceStart:1,sourceEnd:7,segments:[{text:'늦게 도착한 결과',words:[]}]}),late);
  assert.ok(!(await p.locator('#acReview').isVisible()));assert.equal(await p.evaluate(()=>state.texts.length),2);assert.ok(await p.evaluate(()=>mockCancels>0));
  checks.push('Cancellation ignores late native callbacks and preserves all existing texts');
  await p.evaluate(()=>{mockMode='error';});await p.locator('#acGenerate').click();await p.waitForFunction(()=>$('acStatus').textContent.includes('네트워크 오류'));assert.equal(await p.evaluate(()=>state.texts.length),2);
  checks.push('Native error displayed without committing captions');
  const srt='1\n00:00:00,300 --> 00:00:01,200\nSRT 테스트 <문자>\n\n2\n00:00:01,300 --> 00:00:02,000\n두 번째 문장\n';
  await p.locator('#acSrtFile').setInputFiles({name:'captions.srt',mimeType:'application/x-subrip',buffer:Buffer.from(srt)});await p.waitForFunction(()=>$('acRows').children.length===2);
  await p.locator('#acApply').click();assert.equal(await p.evaluate(()=>state.texts.filter(t=>t.captionEngine==='srt').length),2);
  await p.evaluate(async()=>{clearTimeout(v038SaveTimer);await v038SaveProject(true);});
  const downloadPromise=p.waitForEvent('download');await p.locator('#acExport').click();const download=await downloadPromise;const exportText=fs.readFileSync(await download.path(),'utf8');assert.ok(exportText.includes('SRT 테스트 <문자>'));assert.ok(!exportText.includes('기존 제목'));
  checks.push('Real SRT file input, literal text handling, timeline Apply and download export');
  await p.reload();await p.waitForFunction(()=>state.clips.length===1&&state.texts.length===4);assert.equal(await p.evaluate(()=>state.texts.filter(t=>t.captionEngine==='srt').length),2);await p.evaluate(()=>LedoaAutoCaptions.open());
  await p.locator('#acSrtFile').setInputFiles({name:'invalid.srt',mimeType:'text/plain',buffer:Buffer.from('1\n00:00:40,000 --> 00:00:41,000\n범위 초과')});await p.waitForFunction(()=>$('acStatus').textContent.includes('벗어'));assert.equal(await p.evaluate(()=>state.texts.length),4);
  checks.push('Autosave restores caption metadata and rejects SRT outside video bounds');
  await p.evaluate(()=>{mockMode='wait';});await p.locator('#acGenerate').click();await p.waitForFunction(()=>mockRequests.length===1);await p.evaluate(()=>hideSheets());await p.waitForFunction(()=>mockCancels>0);assert.equal(await p.evaluate(()=>state.texts.length),4);
  checks.push('Backdrop/history style sheet hiding cancels pending native work');
  await p.evaluate(()=>LedoaAutoCaptions.open());await p.locator('#acSrtFile').setInputFiles({name:'preview.srt',mimeType:'text/plain',buffer:Buffer.from(srt)});await p.waitForFunction(()=>$('acRows').children.length===2);
  await p.screenshot({path:path.join(root,'validation/caption-ui.png')});
  assert.deepEqual(errors,[]);
  const report={passed:true,browser:await browser.version(),checks,native_results:'Mock fixtures only; real Google service and physical Android not tested'};fs.writeFileSync(path.join(root,'validation/caption-browser.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exit(1)});

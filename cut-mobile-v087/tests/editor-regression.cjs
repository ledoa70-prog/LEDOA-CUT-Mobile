const fs=require('fs'),path=require('path'),http=require('http'),assert=require('assert/strict');
const {chromium}=require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES?process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright':'playwright');
const root=path.resolve(__dirname,'..'),errors=[],checks=[];
const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.ttf':'font/ttf','.png':'image/png'};
const server=http.createServer((req,res)=>{try{const p=path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname);if(!p.startsWith(root+path.sep))throw Error();res.writeHead(200,{'Content-Type':types[path.extname(p)]||'application/octet-stream','Cache-Control':'no-store'});res.end(fs.readFileSync(p));}catch{res.writeHead(404);res.end();}});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const fixture=fs.readFileSync(path.join(root,'tests/fixture.webm'));
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const origin='http://127.0.0.1:'+server.address().port;
 const b=await chromium.launch({executablePath:process.env.LEDOA_TEST_BROWSER||chromium.executablePath(),args:['--no-sandbox','--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
 try{
  const c=await b.newContext({viewport:{width:390,height:844},serviceWorkers:'block'}),p=await c.newPage();p.on('pageerror',e=>errors.push(e.message));
  await p.goto(origin+'/assets/index.html');await delay(500);
  await p.evaluate(()=>{
   setCanvasRatio('16:9');window.mediaWrites=0;window.maxSources=0;window.uiTicks=0;
   const put=IDBObjectStore.prototype.put;IDBObjectStore.prototype.put=function(...a){if(this.name==='media')mediaWrites++;return put.apply(this,a);};
   window.checkTimer=setInterval(()=>{uiTicks++;maxSources=Math.max(maxSources,document.querySelectorAll('video[src]').length);},5);
  });
  const files=Array.from({length:12},(_,i)=>({name:`wedding-${String(i+1).padStart(2,'0')}.webm`,mimeType:'video/webm',buffer:fixture}));
  const start=Date.now();await p.locator('#mediaInput').setInputFiles(files);
  await p.waitForFunction(()=>state.lastImportResult?.added===12&&state.lastImportResult.saved,null,{timeout:30000});
  const stats=await p.evaluate(()=>({clips:state.clips.length,total:state.total,names:state.clips.map(c=>c.name),sources:document.querySelectorAll('video[src]').length,maxSources,mediaWrites,uiTicks,importing:state.importing,progress:$('importProgressV066').style.display,result:state.lastImportResult}));
  assert.equal(stats.clips,12);assert.ok(stats.maxSources<=2,JSON.stringify(stats));assert.equal(stats.mediaWrites,12);assert.equal(stats.importing,false);assert.equal(stats.progress,'none');assert.ok(stats.uiTicks>3);assert.ok(stats.total>95&&stats.total<97);
  checks.push({name:'actual file input selects 12 videos, progress, bounded decoders',elapsedMs:Date.now()-start,...stats});console.log('12-file import passed',Date.now()-start,'ms');
  await p.evaluate(async()=>{await v038SaveProject(true);await v038SaveProject(true);});assert.equal(await p.evaluate(()=>mediaWrites),12);checks.push({name:'unchanged videos not rewritten by repeated autosave'});
  await p.locator('#playBtn').click();await delay(750);assert.ok(await p.evaluate(()=>state.currentTime>.3&&state.playing&&!activeInfo(state.currentTime).cur.rt.el.paused));
  await p.evaluate(()=>{pause();state.selectedClipId=state.clips[0].id;seek(2);$('splitBtn').click();seek(0);play();});await delay(750);assert.ok(await p.evaluate(()=>state.currentTime>.3&&state.playing));
  checks.push({name:'12-video playback and split playback'});
  await p.evaluate(async()=>{pause();clearTimeout(v038SaveTimer);await v038SaveProject(true);});await p.reload();await p.waitForFunction(()=>state.clips.length===13);
  assert.ok(await p.evaluate(()=>document.querySelectorAll('video[src]').length<=2));
  await p.evaluate(async()=>{window.writesAfterRestore=0;const put=IDBObjectStore.prototype.put;IDBObjectStore.prototype.put=function(...a){if(this.name==='media')writesAfterRestore++;return put.apply(this,a);};await v038SaveProject(true);});
  assert.equal(await p.evaluate(()=>writesAfterRestore),0);checks.push({name:'13 clips restore without loading every decoder or rewriting saved files'});
  // One bad file must be skipped, not stored as an invented ten-second clip.
  await p.locator('#mediaInput').setInputFiles([{name:'broken.mp4',mimeType:'video/mp4',buffer:Buffer.from('broken media')},files[0]]);
  await p.waitForFunction(()=>state.lastImportResult?.selected===2&&state.lastImportResult.saved);
  assert.equal(await p.evaluate(()=>state.lastImportResult.added),1);assert.equal(await p.evaluate(()=>state.lastImportResult.failed.length),1);assert.equal(await p.evaluate(()=>state.clips.length),14);
  await p.locator('#importCancelV066').click();checks.push({name:'corrupt video skipped with visible filename; next valid file imported'});
  // A probe that never signals ready remains cancellable and cannot lose old clips.
  await p.evaluate(()=>{const load=HTMLMediaElement.prototype.load;window.realLoad=load;HTMLMediaElement.prototype.load=function(){if(this.dataset.importProbe==='1')this.removeAttribute('src');return load.call(this);};});
  await p.locator('#mediaInput').setInputFiles(files);await p.waitForFunction(()=>state.importing);
  await p.locator('#importCancelV066').click();await p.waitForFunction(()=>state.lastImportResult?.cancelled===true&&state.lastImportResult.saved);
  assert.equal(await p.evaluate(()=>state.clips.length),14);await p.evaluate(()=>HTMLMediaElement.prototype.load=realLoad);checks.push({name:'cancel unresponsive import preserves existing 14 clips'});
  await p.evaluate(async()=>{openSheet('textSheet');await v063EnsureFonts();});await p.locator('#textInput').fill('결혼을 축하합니다');await p.locator('#textSizeNumber').fill('64');await p.locator('#textBgOpacity').evaluate(e=>{e.value='0';e.dispatchEvent(new Event('input',{bubbles:true}));});await p.locator('#addTextBtn').click();
  assert.equal(await p.evaluate(()=>state.texts[0].bgOpacity),0);assert.equal(await p.evaluate(()=>state.texts[0].size),64);assert.ok(await p.evaluate(()=>[...document.fonts].every(f=>f.status==='loaded')));checks.push({name:'font, numeric size and transparent captions preserved'});
  assert.deepEqual(errors,[]);fs.writeFileSync(path.join(root,'validation/editor-regression.json'),JSON.stringify({passed:true,browser:await b.version(),fixture:'VP8/Opus WebM, 12 files; physical Android not tested',checks},null,2));console.log(JSON.stringify({passed:true,checks:checks.map(x=>x.name)}));
 }finally{await b.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exit(1)});

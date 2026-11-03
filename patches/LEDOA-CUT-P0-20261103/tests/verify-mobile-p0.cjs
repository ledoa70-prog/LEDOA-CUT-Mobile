const fs=require('fs'),path=require('path'),http=require('http'),assert=require('assert/strict'),cp=require('child_process');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES?process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright':'playwright'));
const ROOT=path.resolve('.'),OUT=path.join(ROOT,'p0_evidence');
let appRoot=path.join(ROOT,'p0_mobile');
const results={passed:false,androidDevice:false,actualUserProject:false,checks:[],errors:[]};
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.ttf':'font/ttf','.png':'image/png','.jpg':'image/jpeg','.mp4':'video/mp4','.wav':'audio/wav'};
const server=http.createServer((req,res)=>{try{const f=path.resolve(appRoot,'.'+new URL(req.url,'http://localhost').pathname);if(!f.startsWith(appRoot+path.sep))throw Error();res.setHeader('Content-Type',mime[path.extname(f)]||'application/octet-stream');res.setHeader('Cache-Control','no-store');res.end(fs.readFileSync(f));}catch{res.writeHead(404);res.end();}});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function waitUntil(page,fn,timeout=12000){const until=Date.now()+timeout;while(Date.now()<until){if(await page.evaluate(fn))return;await delay(100);}throw Error('Timed out: '+fn.toString());}
async function addClips(page){const f=path.join(ROOT,'p0_tests/fixtures/portrait30.mp4');await page.locator('#mediaInput').setInputFiles([f,f]);await waitUntil(page,()=>state.clips.length===2&&!state.importing,25000);await delay(200);}
async function configure(page,at=1.1){await page.evaluate(t=>{pause();for(const c of state.clips){c.transition='cut';c.speed=1;}state.clips[0].trimStart=0;state.clips[0].trimEnd=1.5;state.clips[1].trimStart=1.1;state.clips[1].trimEnd=3.5;recalc();seek(t);},at);await delay(250);}
async function databaseSnapshot(page){return page.evaluate(async()=>{const db=await v038Db(),tx=db.transaction(['project',V038_MEDIA],'readonly'),ms=tx.objectStore(V038_MEDIA);const [project,keys,records]=await Promise.all([v038Req(tx.objectStore('project').get(V038_PROJECT)),v038Req(ms.getAllKeys()),v038Req(ms.getAll())]);db.close();const media=[];for(let i=0;i<records.length;i++){const r=records[i],digest=await crypto.subtle.digest('SHA-256',await r.blob.arrayBuffer());media.push({key:keys[i],bytes:r.blob.size,type:r.type,name:r.name,sha256:Array.from(new Uint8Array(digest),n=>n.toString(16).padStart(2,'0')).join('')});}delete project.savedAt;return{project,media};});}
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const url='http://127.0.0.1:'+server.address().port+'/assets/index.html';
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||path.join(ROOT,'p0_tools/chrome'),args:['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 results.environment={browser:browser.version(),platform:process.platform,video:'synthetic H.264/AAC 720x1280 30fps',hardwareDecoder:false};
 let context,page;
 async function fresh(root='p0_mobile'){
  if(context)await context.close();appRoot=path.join(ROOT,root);
  context=await browser.newContext({viewport:{width:690,height:760},serviceWorkers:'block',acceptDownloads:true});page=await context.newPage();page.on('pageerror',e=>results.errors.push(e.message));await page.goto(url);await delay(400);await addClips(page);return page;
 }
 try{
  await fresh();await configure(page);
  await page.evaluate(()=>{const v=state.clips[1].rt.el;window.deadElement=v;v.play=()=>Promise.resolve();play();});
  await waitUntil(page,()=>state.clips[1].rt.el!==window.deadElement,7500);
  await waitUntil(page,()=>!state.playing&&state.currentTime>=totalDuration()-.02,6500);
  const recovered=await page.evaluate(()=>LedoaPlaybackDiagnostics.snapshot());
  assert.deepEqual(recovered.events.map(e=>e.event),['retry-play','reload-decoder','replace-decoder','recovered']);
  results.checks.push({name:'dead incoming decoder is replaced once and playback finishes',diagnostics:recovered});console.log('PASS dead decoder replacement');

  await fresh();await configure(page);
  await page.evaluate(()=>{const create=createVideoRuntime;createVideoRuntime=function(c){const rt=create(c);if(c===state.clips[1])rt.el.play=()=>Promise.resolve();return rt;};play();});
  await waitUntil(page,()=>!state.playing&&LedoaPlaybackDiagnostics.snapshot().events.some(e=>e.event==='stopped-after-failed-recovery'),11000);
  const stopped=await page.evaluate(()=>LedoaPlaybackDiagnostics.snapshot());
  assert.equal(stopped.events.filter(e=>e.event==='reload-decoder').length,1);
  assert.equal(stopped.events.filter(e=>e.event==='replace-decoder').length,1);
  assert.equal(await page.evaluate(()=>state.clips.length),2);
  results.checks.push({name:'persistent decoder fault stops after bounded recovery; project remains',diagnostics:stopped});console.log('PASS persistent failure bound');

  await fresh();await configure(page);
  await page.evaluate(()=>{const v=state.clips[1].rt.el;window.oldDecoder=v;v.play=()=>Promise.resolve();const add=v.addEventListener;v.addEventListener=function(name,fn,...rest){if(name==='loadedmetadata'&&fn.name==='resume'){window.staleRecovery=fn;return;}return add.call(this,name,fn,...rest);};play();});
  await waitUntil(page,()=>typeof window.staleRecovery==='function',4500);
  await waitUntil(page,()=>state.clips[1].rt.el!==window.oldDecoder,4000);
  const stale=await page.evaluate(()=>{const old=window.oldDecoder;const before={ct:old.currentTime,paused:old.paused,src:old.getAttribute('src')};window.staleRecovery();return{before,after:{ct:old.currentTime,paused:old.paused,src:old.getAttribute('src')}};});
  assert.deepEqual(stale.after,stale.before);await page.evaluate(()=>pause());
  results.checks.push({name:'late callback cannot revive a retired decoder',...stale});console.log('PASS retired decoder callback');

  await fresh();await configure(page);
  await page.evaluate(()=>{const v=state.clips[1].rt.el;window.sessionDecoder=v;v.play=()=>Promise.resolve();const add=v.addEventListener;v.addEventListener=function(name,fn,...rest){if(name==='loadedmetadata'&&fn.name==='resume'){window.previousSessionResume=fn;return;}return add.call(this,name,fn,...rest);};play();});
  await waitUntil(page,()=>typeof window.previousSessionResume==='function',4500);
  await page.evaluate(()=>{pause();const v=window.sessionDecoder;delete v.play;seek(2.8);play();});
  await waitUntil(page,()=>state.playing&&state.clips[1].rt.el.currentTime>2.45,2500);
  const oldSession=await page.evaluate(()=>{const v=window.sessionDecoder,before=v.currentTime;window.previousSessionResume();return{before,after:v.currentTime};});
  assert.ok(oldSession.after>=oldSession.before-.005);await page.evaluate(()=>pause());
  results.checks.push({name:'late callback cannot seek a new playback session backwards',...oldSession});console.log('PASS playback session token');

  await fresh('p0_source_baseline/LEDOA_CUT_Mobile_v0.8.11_SOURCE');
  await page.evaluate(()=>{pause();state.clips[0].trimStart=.1;state.clips[0].trimEnd=1.5;state.clips[0].transition='dissolve';state.clips[0].transitionDuration=.25;state.clips[1].trimStart=1.1;state.clips[1].trimEnd=2.6;recalc();state.texts.push({id:'p0-subtitle',kind:'caption',text:'기존 자막 보존',font:'Gowun Dodum',size:48,position:'bottom',color:'#ffffff',bg:'#000000',bgOpacity:0,start:0,end:2.5});});
  await page.locator('#overlayInput').setInputFiles(path.join(ROOT,'p0_mobile/fixtures/blue.jpg'));
  await waitUntil(page,()=>state.overlays.some(o=>o.type==='image'));
  await page.evaluate(()=>{LedoaMotionEditor.add();LedoaMotionEditor.choose('MT-135');const o=selectedOverlay();o.text='타이포 검증';o.start=.5;o.end=2.3;o.scale=.7;o.font='Gowun Dodum';o.spacing=1.5;hideSheets();seek(0);renderAll();});
  await page.locator('#musicInput').setInputFiles(path.join(ROOT,'p0_tests/fixtures/portrait30.mp4'));
  await waitUntil(page,()=>state.musicTracks.length===1);
  await page.evaluate(()=>{pause();state.currentTime=0;state.musicTracks[0].start=0;state.musicTracks[0].end=2.5;clearTimeout(v038SaveTimer);});
  await delay(500);assert.equal(await page.evaluate(()=>v038SaveProject(true)),true);
  const original=await databaseSnapshot(page);
  appRoot=path.join(ROOT,'p0_mobile');await page.reload();await waitUntil(page,()=>state.clips.length===2&&!v038Restoring);await delay(600);
  assert.equal(await page.evaluate(()=>{clearTimeout(v038SaveTimer);return v038SaveProject(true);}),true);
  const upgraded=await databaseSnapshot(page);assert.deepEqual(upgraded,original);
  appRoot=path.join(ROOT,'p0_source_baseline/LEDOA_CUT_Mobile_v0.8.11_SOURCE');await page.reload();await waitUntil(page,()=>state.clips.length===2&&!v038Restoring);await delay(400);
  assert.equal(await page.evaluate(()=>{clearTimeout(v038SaveTimer);return v038SaveProject(true);}),true);
  const downgraded=await databaseSnapshot(page);assert.deepEqual(downgraded,original);
  results.checks.push({name:'v0.8.11 project and all media hashes survive upgrade/save/reload and rollback',media:original.media,clipCount:original.project.clips.length,textCount:original.project.texts.length,overlayCount:original.project.overlays.length,musicCount:original.project.musicTracks.length});console.log('PASS project upgrade and rollback');

  appRoot=path.join(ROOT,'p0_mobile');await page.reload();await waitUntil(page,()=>state.clips.length===2&&!v038Restoring);await page.evaluate(()=>{hideSheets();seek(0);play();});
  await waitUntil(page,()=>!state.playing&&state.currentTime>=totalDuration()-.02,7000);
  results.checks.push({name:'existing subtitles, image overlay, MT-135 typography and BGM play across dissolve'});console.log('PASS overlay playback');
  const downloadPromise=page.waitForEvent('download',{timeout:20000});await page.evaluate(()=>{pause();openSheet('exportSheet');});await page.locator('#startExportBtn').click();
  const download=await downloadPromise;const outputPath=path.join(OUT,'mobile-p0-output.mp4');assert.ok(download.suggestedFilename().endsWith('.mp4'));await download.saveAs(outputPath);
  await waitUntil(page,()=>!state.exporting,3000);assert.ok(fs.statSync(outputPath).size>10000);
  const mp4=JSON.parse(cp.execFileSync('ffprobe',['-v','error','-count_frames','-show_streams','-show_format','-of','json',outputPath]));
  const video=mp4.streams.find(s=>s.codec_type==='video'),audio=mp4.streams.find(s=>s.codec_type==='audio'),duration=Number(mp4.format.duration);
  const qualityPassed=!!video&&!!audio&&Number(video.nb_read_frames)>=duration*18&&Math.abs(Number(video.duration)-Number(audio.duration))<.15;
  results.checks.push({name:'real MediaRecorder MP4 output through existing save path',passed:qualityPassed,bytes:fs.statSync(outputPath).size,duration,video:{codec:video?.codec_name,duration:video?.duration,frames:video?.nb_read_frames},audio:{codec:audio?.codec_name,duration:audio?.duration},scope:'Linux Chromium software encoder; Android native encoder/MediaStore unverified'});console.log('MP4 quality gate: '+(qualityPassed?'PASS':'FAIL'));

  await fresh();await page.evaluate(()=>{pause();const seed=state.clips[0];for(const c of state.clips){const v=c.rt?.el;try{v?.pause();v?.removeAttribute('src');v?.load();v?.remove();c.rt?.source?.disconnect();c.rt?.gain?.disconnect();}catch{}}state.clips=Array.from({length:10},(_,i)=>({...seed,id:uid('test'),name:'synthetic-'+i,trimStart:.2,trimEnd:.8,speed:1,transition:i%3===1?'dissolve':'cut',transitionDuration:.15,rt:null}));recalc();seek(0);});
  let transitions=0,maxAttached=0;
  for(let round=0;round<12;round++){
   await page.evaluate(()=>{pause();seek(0);play();});let previous=0;
   const until=Date.now()+12000;
   while(Date.now()<until){await delay(100);const s=await page.evaluate(()=>({playing:state.playing,g:state.currentTime,total:totalDuration(),index:activeInfo(state.currentTime)?.idx,attached:state.clips.filter(c=>c.rt?.el.getAttribute('src')).length}));if(s.index>previous)transitions+=s.index-previous;previous=s.index;maxAttached=Math.max(maxAttached,s.attached);if(!s.playing&&s.g>=s.total-.02)break;}
   assert.equal(await page.evaluate(()=>!state.playing&&state.currentTime>=totalDuration()-.02),true,'continuous playback round '+round);
  }
  assert.ok(transitions>=100);assert.ok(maxAttached<=2);
  for(let i=0;i<100;i++){const target=await page.evaluate(i=>{const t=(i*1.731)%Math.max(.1,totalDuration()-.1);pause();seek(t);if(i%3===0){play();pause();}return t;},i);await delay(25);assert.equal(await page.evaluate(()=>state.playing),false);assert.ok(Math.abs(await page.evaluate(()=>state.currentTime)-target)<.01,'stale seek '+i);}
  results.checks.push({name:'ten video clips, at least 100 scene transitions and 100 seek/play/pause operations',transitions,maxAttached,seeks:100});console.log('PASS 100 transitions and 100 seeks');
  assert.deepEqual(results.errors,[]);results.previewPassed=true;results.releaseReady=false;results.passed=results.checks.every(c=>c.passed!==false);
  if(!results.passed)throw Error('MP4 output quality gate failed in the Linux software-encoder environment. Native-device playback/export validation is still required; do not release an installable APK.');
 }catch(e){results.failure=e.stack;throw e;}
 finally{if(context)await context.close();await browser.close();server.close();fs.writeFileSync(path.join(OUT,'mobile-p0-regression.json'),JSON.stringify(results,null,2));}
 console.log(JSON.stringify({passed:results.passed,checks:results.checks.length,errors:results.errors}));
})().catch(e=>{console.error(e);server.close();process.exit(1);});

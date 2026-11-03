const fs=require('fs'),path=require('path'),http=require('http');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES?process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright':'playwright'));
const root=path.resolve(process.env.APP_ROOT||'p0_source_baseline/LEDOA_CUT_Mobile_v0.8.11_SOURCE');
const out=path.resolve('p0_evidence');fs.mkdirSync(out,{recursive:true});
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.ttf':'font/ttf','.png':'image/png','.jpg':'image/jpeg'};
const server=http.createServer((req,res)=>{try{const p=path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname);if(!p.startsWith(root+path.sep))throw Error();res.setHeader('Content-Type',mime[path.extname(p)]||'application/octet-stream');res.setHeader('Cache-Control','no-store');res.end(fs.readFileSync(p));}catch{res.writeHead(404);res.end();}});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||path.resolve('p0_tools/chrome'),args:['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const report={environment:{browser:browser.version(),platform:process.platform,androidDevice:false},cases:[],errors:[]};
 try{
 const page=await browser.newPage({viewport:{width:690,height:760},serviceWorkers:'block'});
 page.on('pageerror',e=>report.errors.push(e.message));
 await page.addInitScript(()=>{
  const log=[],counts={},ids=new WeakMap();let n=0;
  const id=v=>{if(!ids.has(v))ids.set(v,++n);return ids.get(v);};
  const note=(kind,v,extra={})=>{log.push({at:performance.now(),kind,video:id(v),ct:v.currentTime,paused:v.paused,seeking:v.seeking,ready:v.readyState,ended:v.ended,...extra});if(log.length>2000)log.shift();};
  for(const name of ['play','pause','load']){const original=HTMLMediaElement.prototype[name];HTMLMediaElement.prototype[name]=function(...args){counts[name]=(counts[name]||0)+1;if(name!=='pause'||!this.paused)note(name,this);const result=original.apply(this,args);if(name==='play'&&result?.catch)result.catch(e=>note('play-rejected',this,{error:e.name}));return result;};}
  const current=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'currentTime');Object.defineProperty(HTMLMediaElement.prototype,'currentTime',{...current,set(value){note('seek-command',this,{target:value});return current.set.call(this,value);}});
  for(const name of ['loadedmetadata','loadeddata','waiting','seeking','seeked','playing','pause','error','ended','emptied','stalled'])document.addEventListener(name,e=>{if(e.target instanceof HTMLMediaElement)note('event:'+name,e.target);},true);
  window.transportTrace={log,counts,id};
 });
 await page.goto('http://127.0.0.1:'+server.address().port+'/assets/index.html');
 report.environment.media=await page.evaluate(()=>({h264:document.createElement('video').canPlayType('video/mp4; codecs="avc1.42E01E"'),mp4Recorder:MediaRecorder.isTypeSupported('video/mp4')}));
 await page.waitForTimeout(400);
 const fixture=path.resolve('p0_tests/fixtures/portrait30.mp4');
 await page.locator('#mediaInput').setInputFiles([fixture,fixture]);
 await page.waitForFunction(()=>state.clips.length===2&&!state.importing,{timeout:25000});
 await page.waitForTimeout(250);
 const cases=process.env.FAULT_ONLY?[{name:'dead-incoming-decoder',firstEnd:1.5,secondStart:1.1,secondEnd:3.5,at:1.1,wait:10500,faultInjected:true}]:[
  {name:'trimmed-cut',firstEnd:2.4,secondStart:1.1,secondEnd:3.5,at:.15,wait:5200},
  {name:'natural-eof',firstEnd:8,secondStart:0,secondEnd:8,at:7.5,wait:2200},
  {name:'split-nonzero-source-time',firstEnd:3.5,secondStart:3.5,secondEnd:6.5,at:3.1,wait:2500},
  {name:'overlap-dissolve',firstEnd:2.4,secondStart:1.1,secondEnd:3.5,at:1.65,wait:2200,transition:'dissolve'}
 ];
 for(const spec of cases){
  await page.evaluate(s=>{pause();state.clips[0].trimStart=0;state.clips[0].trimEnd=s.firstEnd;state.clips[0].transition=s.transition||'cut';state.clips[0].transitionDuration=.35;state.clips[1].trimStart=s.secondStart;state.clips[1].trimEnd=s.secondEnd;state.clips[1].transition='cut';recalc();seek(s.at);transportTrace.log.length=0;for(const k of Object.keys(transportTrace.counts))delete transportTrace.counts[k];},spec);
  await page.waitForTimeout(200);
  if(spec.faultInjected)await page.evaluate(()=>{
   const v=ensureRuntime(state.clips[1]).el;
   v.play=function(){return Promise.resolve();};
   transportTrace.log.push({kind:'test-only-decoder-fault',video:transportTrace.id(v),at:performance.now()});
  });
  await page.evaluate(()=>play());
  const samples=[];
  for(let t=0;t<spec.wait;t+=100){await page.waitForTimeout(100);samples.push(await page.evaluate(()=>({at:performance.now(),g:state.currentTime,playing:state.playing,active:activeInfo(state.currentTime)?.idx,videos:state.clips.map(c=>{const v=c.rt?.el;return v?{id:transportTrace.id(v),ct:v.currentTime,paused:v.paused,seeking:v.seeking,ended:v.ended,ready:v.readyState,frames:v088DecodedCount(v)}:null;})})));}
  const trace=await page.evaluate(()=>({log:transportTrace.log,counts:transportTrace.counts}));
  report.cases.push({spec,samples,...trace});
  console.log(JSON.stringify({case:spec.name,start:samples[0],end:samples.at(-1),counts:trace.counts}));
 }
 await page.evaluate(()=>pause());
 }finally{await browser.close();server.close();fs.writeFileSync(path.join(out,'diagnosis-'+(process.env.LABEL||'baseline')+'.json'),JSON.stringify(report,null,2));}
})().catch(e=>{console.error(e);server.close();process.exit(1);});

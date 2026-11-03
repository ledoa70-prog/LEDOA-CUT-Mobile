const fs=require('fs'),path=require('path'),http=require('http');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES?process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright':'playwright'));
const root=path.resolve(process.env.APP_ROOT||'p0_source_baseline/LEDOA_CUT_Mobile_v0.8.11_SOURCE');
const server=http.createServer((req,res)=>{try{const p=path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname);if(!p.startsWith(root+path.sep))throw Error();res.setHeader('Content-Type',p.endsWith('.js')?'text/javascript':p.endsWith('.html')?'text/html':p.endsWith('.css')?'text/css':p.endsWith('.ttf')?'font/ttf':'application/octet-stream');res.end(fs.readFileSync(p));}catch{res.writeHead(404);res.end();}});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||path.resolve('p0_tools/chrome'),args:['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const page=await browser.newPage({viewport:{width:690,height:760},serviceWorkers:'block'}),report={samples:[],loads:[],errors:[]};page.on('pageerror',e=>report.errors.push(e.message));
 try{
  await page.goto('http://127.0.0.1:'+server.address().port+'/assets/index.html');await page.waitForTimeout(400);await page.locator('#mediaInput').setInputFiles(path.resolve('p0_tests/fixtures/portrait30.mp4'));await page.waitForFunction(()=>state.clips.length===1&&!state.importing);await page.waitForTimeout(300);
  await page.evaluate(()=>{
   pause();const seed=state.clips[0],old=seed.rt?.el;
   if(old){old.pause();old.removeAttribute('src');old.load();old.remove();seed.rt?.source?.disconnect();seed.rt?.gain?.disconnect();}
   state.clips=Array.from({length:10},(_,i)=>({...seed,id:uid('probe'),trimStart:.2,trimEnd:.8,speed:1,transition:i%3===1?'dissolve':'cut',transitionDuration:.15,rt:null}));recalc();
   window.budgetLoads=[];const load=HTMLMediaElement.prototype.load;
   HTMLMediaElement.prototype.load=function(...args){const idx=state.clips.findIndex(c=>c.rt?.el===this);if(idx>=0&&this.getAttribute('src'))budgetLoads.push({at:performance.now(),index:idx,g:state.currentTime,stack:new Error().stack});return load.apply(this,args);};
   seek(0);play();
  });
  for(let i=0;i<95;i++){await page.waitForTimeout(80);report.samples.push(await page.evaluate(()=>({at:performance.now(),g:state.currentTime,playing:state.playing,active:activeInfo(state.currentTime)?.idx,attached:state.clips.map((c,i)=>c.rt?.el.getAttribute('src')?i:null).filter(i=>i!==null)})));}
  report.loads=await page.evaluate(()=>window.budgetLoads);report.maxAttached=Math.max(...report.samples.map(x=>x.attached.length));report.completed=await page.evaluate(()=>!state.playing&&state.currentTime>=totalDuration()-.02);
 }finally{await browser.close();server.close();fs.writeFileSync('p0_evidence/budget-'+(process.env.LABEL||'before')+'.json',JSON.stringify(report,null,2));}
 console.log(JSON.stringify({maxAttached:report.maxAttached,completed:report.completed,violations:report.samples.filter(x=>x.attached.length>2).slice(0,4),loads:report.loads.filter(x=>x.stack.includes('drawableVideo')).map(x=>({g:x.g,index:x.index,stack:x.stack})),errors:report.errors}));
})().catch(e=>{console.error(e);server.close();process.exit(1);});

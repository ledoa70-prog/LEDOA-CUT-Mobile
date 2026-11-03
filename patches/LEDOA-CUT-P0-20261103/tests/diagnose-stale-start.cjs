const fs=require('fs'),path=require('path'),http=require('http');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES?process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright':'playwright'));
const root=path.resolve(process.env.APP_ROOT||'p0_source_baseline/LEDOA_CUT_Mobile_v0.8.11_SOURCE');
const server=http.createServer((req,res)=>{try{const p=path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname);if(!p.startsWith(root+path.sep))throw Error();res.setHeader('Content-Type',p.endsWith('.js')?'text/javascript':p.endsWith('.html')?'text/html':p.endsWith('.css')?'text/css':'application/octet-stream');res.end(fs.readFileSync(p));}catch{res.writeHead(404);res.end();}});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||path.resolve('p0_tools/chrome'),args:['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 let result;
 try{const page=await browser.newPage({viewport:{width:690,height:760},serviceWorkers:'block'});await page.goto('http://127.0.0.1:'+server.address().port+'/assets/index.html');await page.waitForTimeout(400);await page.locator('#mediaInput').setInputFiles(path.resolve('p0_tests/fixtures/portrait30.mp4'));await page.waitForFunction(()=>state.clips.length===1&&!state.importing);await page.waitForTimeout(300);
 await page.evaluate(()=>{pause();const v=state.clips[0].rt.el;v.removeAttribute('src');v.load();const add=v.addEventListener;v.addEventListener=function(name,fn,...args){if(name==='loadedmetadata'&&fn.name===''&&!window.staleStartup){window.staleStartup=fn;return;}return add.call(this,name,fn,...args);};seek(0);play();if(!window.staleStartup)throw Error('Failed to capture delayed startup metadata callback');pause();seek(1);play();});
 await page.waitForFunction(()=>state.playing&&state.clips[0].rt.el.currentTime>1.2);
 result=await page.evaluate(()=>{const v=state.clips[0].rt.el,before=v.currentTime;window.staleStartup();return{before,after:v.currentTime,projectTime:state.currentTime,rewound:v.currentTime<before-.1};});
 }finally{await browser.close();server.close();}
 fs.writeFileSync('p0_evidence/stale-start-'+(process.env.LABEL||'before')+'.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);server.close();process.exit(1);});

'use strict';

const $ = id => document.getElementById(id);
const uid = p => `${p}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,7)}`;
const clamp = (v,a,b) => Math.max(a,Math.min(b,v));
const fmt = sec => { sec=Math.max(0,+sec||0); const m=Math.floor(sec/60), s=sec-m*60; return `${String(m).padStart(2,'0')}:${s.toFixed(1).padStart(4,'0')}`; };

const SHORTS_STYLE_PRESETS = {
  premium:{name:'프리미엄 제네시스형',top:{height:24,bg:'#000000',opacity:40,color:'#ffffff',font:'Nanum Pen Script',size:42,bold:false,align:'center',decor:'underline',decorColor:'#c47b32'},bottom:{height:24,bg:'#000000',opacity:55,color:'#ffffff',font:'Gowun Dodum',size:22,bold:false,align:'center',decor:'none',decorColor:'#c47b32'}},
  news:{name:'뉴스 헤드라인형',top:{height:24,bg:'#10203c',opacity:84,color:'#ffffff',font:'Gowun Dodum',size:38,bold:true,align:'center',decor:'leftbar',decorColor:'#d78a35'},bottom:{height:24,bg:'#000000',opacity:76,color:'#ffffff',font:'Gowun Dodum',size:22,bold:false,align:'left',decor:'outline',decorColor:'#2f5da8'}},
  soft:{name:'감성 릴스형',top:{height:24,bg:'#3a3127',opacity:34,color:'#fff7eb',font:'Nanum Pen Script',size:42,bold:false,align:'center',decor:'corners',decorColor:'#d0a262'},bottom:{height:23,bg:'#000000',opacity:28,color:'#ffffff',font:'Gowun Dodum',size:21,bold:false,align:'center',decor:'none',decorColor:'#d0a262'}},
  dialogue:{name:'대화 자막형',top:{height:21,bg:'#000000',opacity:18,color:'#ffffff',font:'Gowun Dodum',size:34,bold:true,align:'left',decor:'leftbar',decorColor:'#ffb020'},bottom:{height:27,bg:'#000000',opacity:62,color:'#ffffff',font:'Gowun Dodum',size:23,bold:false,align:'center',decor:'outline',decorColor:'#ffb020'}},
  minimal:{name:'미니멀형',top:{height:22,bg:'#000000',opacity:52,color:'#ffffff',font:'Gowun Dodum',size:32,bold:true,align:'center',decor:'underline',decorColor:'#ffffff'},bottom:{height:22,bg:'#000000',opacity:60,color:'#ffffff',font:'Gowun Dodum',size:20,bold:false,align:'center',decor:'none',decorColor:'#ffffff'}}
};

const state = {
  clips:[], selectedClipId:null, currentTime:0, playing:false, playStartPerf:0, playStartTime:0,
  zoom:1, ratio:'9:16', texts:[], selectedTextId:null, overlays:[], selectedOverlayId:null, shortsStyle:{enabled:false,presetId:'news'}, coverDataUrl:null, coverImage:null, coverHold:.2,
  music:null, musicUrl:null, musicAudio:null, musicVolume:.2, exporting:false, recorder:null, chunks:[],
  audioCtx:null, audioDest:null, musicNode:null, history:[]
};

const canvas=$('previewCanvas'), ctx=canvas.getContext('2d',{alpha:false});
let raf=0, toastTimer=0, resizeTimer=0;

function toast(msg){ const t=$('toast'); t.textContent=msg; t.classList.remove('hidden'); clearTimeout(toastTimer); toastTimer=setTimeout(()=>t.classList.add('hidden'),2200); }
function snapshot(){return JSON.stringify({clips:state.clips.map(c=>({...c,file:null,url:null,rt:null})),texts:state.texts,selectedTextId:state.selectedTextId,overlays:state.overlays.map(o=>({...o,file:null,url:null,rt:null})),selectedOverlayId:state.selectedOverlayId,shortsStyle:state.shortsStyle,coverDataUrl:state.coverDataUrl,coverHold:state.coverHold});}
function pushHistory(){ state.history.push(snapshot()); if(state.history.length>25)state.history.shift(); $('undoBtn').disabled=!state.history.length; }
function undo(){if(!state.history.length)return;const raw=JSON.parse(state.history.pop());const oldClips=new Map(state.clips.map(c=>[c.id,c]));const oldOverlays=new Map(state.overlays.map(o=>[o.id,o]));state.clips=(raw.clips||[]).map(c=>{const old=oldClips.get(c.id);return {...c,file:old?.file||null,url:old?.url||null,rt:old?.rt||null};});state.texts=raw.texts||[];state.selectedTextId=raw.selectedTextId||null;state.overlays=(raw.overlays||[]).map(o=>{const old=oldOverlays.get(o.id);return {...o,file:old?.file||null,url:old?.url||null,rt:old?.rt||null};});state.selectedOverlayId=raw.selectedOverlayId||null;state.shortsStyle=raw.shortsStyle||state.shortsStyle;state.coverDataUrl=raw.coverDataUrl||null;state.coverHold=+raw.coverHold||0;hydrateCover();recalc();renderAll();$('undoBtn').disabled=!state.history.length;}

function clipDuration(c){ if(c.placeholder)return Math.max(.1,+c.duration||3); if(c.type==='image')return Math.max(.1,+c.duration||3); return Math.max(.05,((+c.trimEnd||0)-(+c.trimStart||0))/Math.max(.1,+c.speed||1)); }
function overlapAfter(i){ const c=state.clips[i], n=state.clips[i+1]; if(!c||!n||c.transition==='cut')return 0; return Math.min(+c.transitionDuration||0, clipDuration(c)*.45, clipDuration(n)*.45, 1.2); }
function recalc(){ let t=0; state.clips.forEach((c,i)=>{c.start=t;c.effDuration=clipDuration(c);c.end=c.start+c.effDuration;t=c.end-overlapAfter(i);}); state.total=Math.max(0,state.clips.length?state.clips[state.clips.length-1].end:0); state.currentTime=clamp(state.currentTime,0,state.total||0); renderTimeline(); updateTime(); }
function totalDuration(){return state.total||0;}
function selectedClip(){return state.clips.find(c=>c.id===state.selectedClipId)||null;}
function selectedOverlay(){return state.overlays.find(o=>o.id===state.selectedOverlayId)||null;}
function selectedText(){return state.texts.find(t=>t.id===state.selectedTextId)||null;}
function ensureMediaPool(){let p=document.getElementById('runtimeMediaPool');if(p)return p;p=document.createElement('div');p.id='runtimeMediaPool';p.setAttribute('aria-hidden','true');p.style.cssText='position:fixed;left:-10000px;top:0;width:4px;height:4px;overflow:hidden;opacity:.01;pointer-events:none;z-index:-1';document.body.appendChild(p);return p;}

function createVideoRuntime(c){ if(c.rt?.el)return c.rt; const v=document.createElement('video'); v.src=c.url;v.preload='auto';v.playsInline=true;v.crossOrigin='anonymous';v.muted=false;v.setAttribute('playsinline','');v.setAttribute('webkit-playsinline','');v.style.cssText='width:2px;height:2px;position:absolute;left:0;top:0';ensureMediaPool().appendChild(v); const rt={el:v,gain:null,source:null,synced:false,preloaded:false}; c.rt=rt; v.addEventListener('seeked',()=>{if(!state.playing&&!state.exporting)renderFrame(state.currentTime);},{passive:true});v.addEventListener('loadeddata',()=>{rt.preloaded=true;},{passive:true}); return rt; }
function createImageRuntime(c){ if(c.rt?.img)return c.rt; const img=new Image();img.src=c.url;c.rt={img};img.onload=()=>renderFrame(state.currentTime);return c.rt;}
function ensureRuntime(c){ return c.type==='video'?createVideoRuntime(c):createImageRuntime(c); }


async function addFiles(files){const arr=[...files];if(!arr.length)return;pushHistory();let added=0;for(const file of arr){const isImage=file.type.startsWith('image/')||/\.(jpe?g|png|webp|bmp)$/i.test(file.name);const isVideo=file.type.startsWith('video/')||/\.(mp4|mov|m4v|webm|mkv)$/i.test(file.name);if(!isImage&&!isVideo)continue;const url=URL.createObjectURL(file);const c={id:uid('clip'),type:isImage?'image':'video',file,url,name:file.name,duration:3,sourceDuration:0,trimStart:0,trimEnd:0,speed:1,volume:1,transition:'cut',transitionDuration:.35,rt:null};if(isImage){await new Promise(resolve=>{const img=new Image();img.onload=()=>{c.rt={img};resolve();};img.onerror=resolve;img.src=url;});}else{const rt=createVideoRuntime(c);await new Promise(resolve=>{let doneOnce=false;const done=()=>{if(doneOnce)return;doneOnce=true;c.sourceDuration=Number.isFinite(rt.el.duration)?rt.el.duration:10;c.trimEnd=c.sourceDuration;resolve();};if(rt.el.readyState>=1)done();else{rt.el.addEventListener('loadedmetadata',done,{once:true});setTimeout(done,4000);}});}state.clips.push(c);state.selectedClipId=c.id;added++;}recalc();renderAll();$('emptyViewer').classList.toggle('hidden',!!state.clips.length);toast(`${added}개 미디어를 추가했습니다.`);}

function setCanvasRatio(r){ state.ratio=r; const viewer=$('viewer');viewer.classList.remove('ratio-9-16','ratio-1-1','ratio-16-9');viewer.classList.add(`ratio-${r.replace(':','-')}`); if(r==='9:16'){canvas.width=720;canvas.height=1280;} else if(r==='1:1'){canvas.width=720;canvas.height=720;} else {canvas.width=1280;canvas.height=720;} $('ratioBtn').textContent=r; renderFrame(state.currentTime); }
function cycleRatio(){const list=['9:16','1:1','16:9'];setCanvasRatio(list[(list.indexOf(state.ratio)+1)%list.length]);}

function drawContained(c2,source,alpha=1,dx=0,dy=0,scale=1){ if(!source)return; const sw=source.videoWidth||source.naturalWidth||source.width||0, sh=source.videoHeight||source.naturalHeight||source.height||0;if(!sw||!sh)return; const w=canvas.width,h=canvas.height;const s=Math.min(w/sw,h/sh)*scale;const dw=sw*s,dh=sh*s;const x=(w-dw)/2+dx,y=(h-dh)/2+dy;c2.save();c2.globalAlpha=alpha;c2.drawImage(source,x,y,dw,dh);c2.restore(); }
function drawClip(c,c2,alpha=1,dx=0,dy=0,scale=1){ if(!c||c.placeholder)return;const rt=ensureRuntime(c);const src=c.type==='video'?rt.el:rt.img;drawContained(c2,src,alpha,dx,dy,scale); }
function mediaLocalSourceTime(c,g){return clamp((+c.trimStart||0)+Math.max(0,g-c.start)*Math.max(.1,+c.speed||1),+c.trimStart||0,Math.max(+c.trimStart||0,(+c.trimEnd||c.sourceDuration||0)-.02));}

function activeInfo(g){ if(!state.clips.length)return null; let idx=-1; for(let i=0;i<state.clips.length;i++){const c=state.clips[i]; if(g>=c.start-.001&&g<=c.end+.001)idx=i;} if(idx<0){idx=g<=0?0:state.clips.length-1;} const cur=state.clips[idx], prev=idx>0?state.clips[idx-1]:null; let trans=null; if(prev&&prev.transition!=='cut'&&g>=cur.start&&g<=prev.end){const d=Math.max(.001,prev.end-cur.start);trans={prev,cur,p:clamp((g-cur.start)/d,0,1),type:prev.transition};} return {idx,cur,prev,trans}; }

function prepareMedia(g,forPlay=false){ const ai=activeInfo(g); const activeIds=new Set(); if(!ai)return; const next=state.clips[ai.idx+1];if(next?.type==='video'&&!next.placeholder){const nr=ensureRuntime(next);if(!nr.preloaded){try{nr.el.load();}catch{}nr.preloaded=true;}}
  const items=ai.trans?[ai.trans.prev,ai.trans.cur]:[ai.cur];
  items.forEach(c=>{if(c.type!=='video'||c.placeholder)return;activeIds.add(c.id);const rt=ensureRuntime(c),v=rt.el,target=mediaLocalSourceTime(c,g);v.playbackRate=Math.max(.5,Math.min(2,+c.speed||1));const drift=Math.abs((v.currentTime||0)-target);
    if(!forPlay){v.pause();if(drift>.045){try{v.currentTime=target}catch{}}rt.synced=true;}
    else{if(!rt.synced||drift>.42){try{v.currentTime=target}catch{}rt.synced=true;}if(v.paused){const p=v.play();if(p?.catch)p.catch(()=>{});}}
  });
  state.clips.forEach(c=>{if(c.type==='video'&&c.rt?.el&&!activeIds.has(c.id)){c.rt.el.pause();c.rt.synced=false;}});
  applyVideoAudioGains(ai);
}
function drawTransition(c2,tr){
  const {prev,cur,type}=tr,p=clamp(tr.p,0,1),w=canvas.width,h=canvas.height;
  if(type==='dissolve'||type==='fade'){
    // Source-over blending needs one opaque base, not two partially transparent layers.
    const a=type==='fade'?p*p*(3-2*p):p;
    drawClip(prev,c2,1);drawClip(cur,c2,a);return;
  }
  if(type==='push-left'){drawClip(prev,c2,1,-p*w,0);drawClip(cur,c2,1,(1-p)*w,0);return;}
  if(type==='push-up'){drawClip(prev,c2,1,0,-p*h);drawClip(cur,c2,1,0,(1-p)*h);return;}
  if(type==='split'){
    drawClip(cur,c2,1);
    c2.save();c2.beginPath();c2.rect(0,0,w/2,h);c2.clip();drawClip(prev,c2,1,-p*w*.55,0);c2.restore();
    c2.save();c2.beginPath();c2.rect(w/2,0,w/2,h);c2.clip();drawClip(prev,c2,1,p*w*.55,0);c2.restore();return;
  }
  drawClip(cur,c2,1);
}
function fontStack(name){return name==='Nanum Pen Script'?`'Nanum Pen Script','NanumPen',cursive`:`'Gowun Dodum','GowunDodum',system-ui,sans-serif`;}
function textSheetOpen(){return !$('textSheet').classList.contains('hidden');}
function draftTextFromUi(){if(!textSheetOpen())return null;const text=($('textInput')?.value||'').trim();if(!text)return null;return {id:'draft',text,kind:$('textKind')?.value||'caption',font:$('textFont')?.value||'Gowun Dodum',size:+$('textSize')?.value||52,position:$('textPosition')?.value||'bottom',color:$('textColor')?.value||'#ffffff',bg:$('textBg')?.value||'#000000',draft:true};}
function wrapLines(c2,text,maxW,maxLines=4){const src=String(text||'').split(/\n/),out=[];for(const raw of src){let line='';for(const ch of raw){const n=line+ch;if(line&&c2.measureText(n).width>maxW){out.push(line);line=ch;if(out.length>=maxLines)return out;}else line=n;}if(line||!raw)out.push(line);if(out.length>=maxLines)return out;}return out.slice(0,maxLines);}
function drawPanelDecor(c2,a,p,pos){c2.save();c2.strokeStyle=p.decorColor||'#fff';c2.fillStyle=p.decorColor||'#fff';c2.lineWidth=Math.max(2,canvas.width*.006);if(p.decor==='underline'){const y=pos==='top'?a.y+a.h-5:a.y+5;c2.beginPath();c2.moveTo(a.w*.27,y);c2.lineTo(a.w*.73,y);c2.stroke();}else if(p.decor==='leftbar'){c2.fillRect(a.x+10,a.y+a.h*.18,Math.max(4,canvas.width*.008),a.h*.64);}else if(p.decor==='outline'){c2.strokeRect(a.x+6,a.y+6,a.w-12,a.h-12);}else if(p.decor==='corners'){const d=Math.min(32,a.h*.28);[[a.x+10,a.y+10,1,1],[a.x+a.w-10,a.y+10,-1,1],[a.x+10,a.y+a.h-10,1,-1],[a.x+a.w-10,a.y+a.h-10,-1,-1]].forEach(([x,y,sx,sy])=>{c2.beginPath();c2.moveTo(x+sx*d,y);c2.lineTo(x,y);c2.lineTo(x,y+sy*d);c2.stroke();});}c2.restore();}
function paintStyleBand(c2,text,panel,pos){if(!text||!panel)return;const areaH=Math.max(50,canvas.height*(panel.height/100)),area={x:0,y:pos==='top'?0:canvas.height-areaH,w:canvas.width,h:areaH};c2.save();c2.fillStyle=hexAlpha(panel.bg,(panel.opacity||0)/100);if(panel.opacity>0)c2.fillRect(area.x,area.y,area.w,area.h);drawPanelDecor(c2,area,panel,pos);const scale=Math.max(.08,canvas.width/720),fontSize=Math.max(12,panel.size*scale);c2.font=`${panel.bold?800:500} ${fontSize}px ${fontStack(panel.font)}`;c2.textBaseline='middle';c2.textAlign=panel.align||'center';const px=Math.max(18,canvas.width*.055),lines=wrapLines(c2,text,area.w-px*2,4),lh=fontSize*1.22,start=area.y+area.h/2-((lines.length-1)*lh)/2;let x=area.x+area.w/2;if(panel.align==='left')x=area.x+px;if(panel.align==='right')x=area.x+area.w-px;lines.forEach((line,i)=>{const y=start+i*lh;if(panel.opacity<35){c2.lineWidth=Math.max(1.5,fontSize*.08);c2.strokeStyle='rgba(0,0,0,.70)';c2.strokeText(line,x,y);}c2.fillStyle=panel.color||'#fff';c2.fillText(line,x,y);});c2.restore();}
function drawPlainText(c2,t){const y=t.position==='top'?canvas.height*.18:t.position==='center'?canvas.height*.5:canvas.height*.82;c2.save();c2.font=`500 ${t.size}px ${fontStack(t.font)}`;c2.textAlign='center';c2.textBaseline='middle';const lines=String(t.text||'').split('\n').slice(0,3),pad=18,lineH=t.size*1.18,widths=lines.map(x=>c2.measureText(x).width),bw=Math.min(canvas.width*.92,Math.max(...widths,10)+pad*2),bh=lineH*lines.length+pad;c2.fillStyle=hexAlpha(t.bg,.62);roundRect(c2,(canvas.width-bw)/2,y-bh/2,bw,bh,14);c2.fill();c2.fillStyle=t.color;lines.forEach((line,i)=>c2.fillText(line,canvas.width/2,y+(i-(lines.length-1)/2)*lineH));if(t.draft){c2.strokeStyle='rgba(255,255,255,.55)';c2.lineWidth=2;roundRect(c2,(canvas.width-bw)/2-3,y-bh/2-3,bw+6,bh+6,16);c2.stroke();}c2.restore();}
function panelWithTextStyle(base,item){if(!item)return {...base};return {...base,font:item.font||base.font,size:+item.size||base.size,color:item.color||base.color};}
function drawTexts(c2,g){const draft=draftTextFromUi(),active=state.texts.filter(t=>t.id!==state.selectedTextId&&g>=t.start&&g<=t.end);if(state.shortsStyle.enabled){const pr=SHORTS_STYLE_PRESETS[state.shortsStyle.presetId]||SHORTS_STYLE_PRESETS.news;const titleItems=active.filter(t=>t.kind==='title'),captionItems=active.filter(t=>t.kind!=='title');let title=titleItems.map(t=>t.text).join('\n'),caption=captionItems.map(t=>t.text).join('\n');let titleStyle=titleItems.at(-1)||null,captionStyle=captionItems.at(-1)||null;if(draft){if(draft.kind==='title'){title=[title,draft.text].filter(Boolean).join('\n');titleStyle=draft;}else{caption=[caption,draft.text].filter(Boolean).join('\n');captionStyle=draft;}}paintStyleBand(c2,title,panelWithTextStyle(pr.top,titleStyle),'top');paintStyleBand(c2,caption,panelWithTextStyle(pr.bottom,captionStyle),'bottom');return;}active.forEach(t=>drawPlainText(c2,t));if(draft)drawPlainText(c2,draft);}
function overlayRuntime(o){if(o.rt)return o.rt;if(o.type==='image'){const img=new Image();img.src=o.url;o.rt={img};img.onload=()=>renderFrame(state.currentTime);return o.rt;}const v=document.createElement('video');v.src=o.url;v.preload='auto';v.playsInline=true;v.muted=true;v.setAttribute('playsinline','');v.setAttribute('webkit-playsinline','');v.style.cssText='width:2px;height:2px;position:absolute;left:0;top:0';ensureMediaPool().appendChild(v);o.rt={el:v};v.addEventListener('seeked',()=>{if(!state.playing&&!state.exporting)renderFrame(state.currentTime);});return o.rt;}
function overlayDefaultY(o){if(o.position==='top')return .22;if(o.position==='bottom')return .78;return .5;}
function overlayRect(o,src){const sw=src?.videoWidth||src?.naturalWidth||src?.width||0,sh=src?.videoHeight||src?.naturalHeight||src?.height||0;if(!sw||!sh)return null;const s=Math.min(canvas.width/sw,canvas.height/sh)*clamp(o.scale??.7,.12,1.8),dw=sw*s,dh=sh*s,cx=clamp(o.x??.5,.02,.98)*canvas.width,cy=clamp(o.y??overlayDefaultY(o),.02,.98)*canvas.height;return {x:cx-dw/2,y:cy-dh/2,w:dw,h:dh,cx,cy};}
function drawOverlayOne(c2,o,src){const r=overlayRect(o,src);if(!r)return;c2.save();c2.globalAlpha=clamp(o.opacity??1,0,1);c2.drawImage(src,r.x,r.y,r.w,r.h);c2.restore();o._drawRect=r;if(o.id===state.selectedOverlayId&&!state.exporting&&!$('overlaySheet').classList.contains('hidden')){c2.save();c2.strokeStyle='rgba(255,198,94,.95)';c2.lineWidth=Math.max(2,canvas.width*.004);c2.setLineDash([10,7]);c2.strokeRect(r.x,r.y,r.w,r.h);c2.setLineDash([]);const hs=Math.max(18,canvas.width*.035);c2.fillStyle='#f0b85e';c2.fillRect(r.x+r.w-hs/2,r.y+r.h-hs/2,hs,hs);c2.restore();}}
function drawOverlays(c2,g,forPlay=false){state.overlays.forEach(o=>{if(g<o.start||g>o.end)return;const rt=overlayRuntime(o);let src;if(o.type==='video'){const v=rt.el,dur=Number.isFinite(v.duration)&&v.duration>0?v.duration:Math.max(.1,o.end-o.start),target=(g-o.start)%dur;if(!forPlay&&Math.abs((v.currentTime||0)-target)>.05)try{v.currentTime=target}catch{}if(forPlay){if(Math.abs((v.currentTime||0)-target)>.4)try{v.currentTime=target}catch{}if(v.paused){const pp=v.play();if(pp?.catch)pp.catch(()=>{});}}else v.pause();src=v;}else src=rt.img;drawOverlayOne(c2,o,src);});}
function stopOverlayMedia(){state.overlays.forEach(o=>{if(o.rt?.el)o.rt.el.pause();});}
function hexAlpha(hex,a){hex=(hex||'#000000').replace('#','');if(hex.length===3)hex=hex.split('').map(x=>x+x).join('');const n=parseInt(hex,16)||0;return `rgba(${(n>>16)&255},${(n>>8)&255},${n&255},${a})`;}
function roundRect(c2,x,y,w,h,r){r=Math.min(r,w/2,h/2);c2.beginPath();c2.moveTo(x+r,y);c2.arcTo(x+w,y,x+w,y+h,r);c2.arcTo(x+w,y+h,x,y+h,r);c2.arcTo(x,y+h,x,y,r);c2.arcTo(x,y,x+w,y,r);c2.closePath();}
function drawCover(c2){c2.fillStyle='#000';c2.fillRect(0,0,canvas.width,canvas.height);if(state.coverImage)drawContained(c2,state.coverImage,1);else{prepareMedia(0,false);const ai=activeInfo(0);if(ai)drawClip(ai.cur,c2,1);drawTexts(c2,0);}}
function renderFrame(g,forPlay=false){g=clamp(g,0,totalDuration()||0);state.currentTime=g;ctx.fillStyle='#000';ctx.fillRect(0,0,canvas.width,canvas.height);if(state.clips.length){prepareMedia(g,forPlay);const ai=activeInfo(g);if(ai?.trans)drawTransition(ctx,ai.trans);else if(ai?.cur)drawClip(ai.cur,ctx,1);drawOverlays(ctx,g,forPlay);drawTexts(ctx,g);} updateTime();updatePlayhead();}

function stopMedia(){state.clips.forEach(c=>{if(c.rt?.el)c.rt.el.pause();});stopOverlayMedia();if(state.musicAudio)state.musicAudio.pause();}
function play(){ if(!state.clips.length)return toast('먼저 미디어를 넣어주세요.'); ensureAudioGraph().catch(()=>{}); if(state.currentTime>=totalDuration()-.03)state.currentTime=0; state.playing=true;state.playStartPerf=performance.now();state.playStartTime=state.currentTime;$('playBtn').textContent='Ⅱ';syncMusic(true);cancelAnimationFrame(raf);raf=requestAnimationFrame(playLoop);}
function pause(){state.playing=false;$('playBtn').textContent='▶';stopMedia();cancelAnimationFrame(raf);renderFrame(state.currentTime,false);}
function playLoop(now){if(!state.playing)return;const g=state.playStartTime+(now-state.playStartPerf)/1000;if(g>=totalDuration()){state.currentTime=totalDuration();pause();return;}renderFrame(g,true);syncMusic(true);raf=requestAnimationFrame(playLoop);}
function togglePlay(){state.playing?pause():play();}
function seek(t){const was=state.playing;if(was)pause();state.currentTime=clamp(t,0,totalDuration()||0);renderFrame(state.currentTime,false);if(was)play();}

async function ensureAudioGraph(){if(state.audioCtx){if(state.audioCtx.state==='suspended')await state.audioCtx.resume();return state.audioCtx;}const AC=window.AudioContext||window.webkitAudioContext;if(!AC)return null;const ac=new AC();const dest=ac.createMediaStreamDestination();state.audioCtx=ac;state.audioDest=dest;for(const c of state.clips)connectClipAudio(c);if(state.musicAudio)connectMusicAudio();if(ac.state==='suspended')await ac.resume();return ac;}
function connectClipAudio(c){if(c.type!=='video'||!c.rt?.el||!state.audioCtx||c.rt.source)return;try{const source=state.audioCtx.createMediaElementSource(c.rt.el),gain=state.audioCtx.createGain();source.connect(gain);gain.connect(state.audioDest);gain.connect(state.audioCtx.destination);c.rt.source=source;c.rt.gain=gain;}catch{}}
function connectMusicAudio(){if(!state.musicAudio||!state.audioCtx||state.musicNode)return;try{const source=state.audioCtx.createMediaElementSource(state.musicAudio),gain=state.audioCtx.createGain();source.connect(gain);gain.connect(state.audioDest);gain.connect(state.audioCtx.destination);state.musicNode={source,gain};}catch{}}
function applyVideoAudioGains(ai){state.clips.forEach(c=>{if(c.type!=='video')return;if(state.audioCtx)connectClipAudio(c);if(c.rt?.gain)c.rt.gain.gain.value=0;else if(c.rt?.el)c.rt.el.volume=0;});if(!ai)return; if(ai.trans){const p=ai.trans.p;setGain(ai.trans.prev,1-p);setGain(ai.trans.cur,p);}else setGain(ai.cur,1);}
function setGain(c,g){if(!c||c.type!=='video')return;const level=clamp(g*(c.volume??1),0,1);if(c.rt?.gain)c.rt.gain.gain.value=level;else if(c.rt?.el)c.rt.el.volume=level;}
function syncMusic(playing){const a=state.musicAudio;if(!a)return;if(state.audioCtx)connectMusicAudio();const t=state.currentTime; if(Number.isFinite(a.duration)&&a.duration>0){const target=t%a.duration;if(Math.abs(a.currentTime-target)>.35)try{a.currentTime=target}catch{}}a.loop=true;a.volume=state.musicNode?1:state.musicVolume;if(state.musicNode)state.musicNode.gain.gain.value=state.musicVolume;if(playing){const p=a.play();if(p?.catch)p.catch(()=>{});}else a.pause();}

function renderTimeline(){const track=$('clipTrack'),ruler=$('ruler'),pps=42*state.zoom,total=Math.max(totalDuration(),8),width=Math.max($('timelineScroll').clientWidth-2,total*pps+50);track.style.width=`${width}px`;ruler.style.width=`${width}px`;track.innerHTML='';state.clips.forEach((c,i)=>{const b=document.createElement('button');b.type='button';b.className=`clip-block ${c.type||''}${c.placeholder?' placeholder':''}${c.id===state.selectedClipId?' selected':''}`;b.style.left=`${c.start*pps}px`;b.style.width=`${Math.max(38,c.effDuration*pps)}px`;b.dataset.id=c.id;b.innerHTML=`<span class="clip-name">${escapeHtml(c.name||'클립')}</span><span class="clip-meta">${c.effDuration.toFixed(1)}초</span>`;b.addEventListener('click',e=>{e.stopPropagation();selectClip(c.id,true);}); if(i<state.clips.length-1&&c.transition!=='cut'){const chip=document.createElement('span');chip.className='transition-chip';chip.textContent=trLabel(c.transition);b.appendChild(chip);}track.appendChild(b);});ruler.innerHTML='';const step=state.zoom>=1.5?1:state.zoom<.8?5:2;for(let s=0;s<=Math.ceil(total);s+=step){const d=document.createElement('span');d.className='ruler-tick';d.style.left=`${s*pps}px`;d.textContent=`${s}s`;ruler.appendChild(d);} $('clipCount').textContent=`${state.clips.length}개`;$('zoomLabel').textContent=`${state.zoom.toFixed(1)}×`;updatePlayhead();}
function trLabel(t){return({dissolve:'겹침',fade:'페이드','push-left':'←','push-up':'↑',split:'분할'})[t]||'';}
function escapeHtml(s){return String(s).replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));}
function updatePlayhead(){const pps=42*state.zoom;$('playhead').style.left=`${state.currentTime*pps}px`;if(state.playing){const sc=$('timelineScroll'),x=state.currentTime*pps;if(x-sc.scrollLeft>sc.clientWidth*.78)sc.scrollLeft=x-sc.clientWidth*.35;}}
function updateTime(){$('timeLabel').textContent=`${fmt(state.currentTime)} / ${fmt(totalDuration())}`;}
function selectClip(id,open=false){state.selectedClipId=id;renderTimeline();updateClipSheet();const c=selectedClip();$('selectedInfo').textContent=c?`${c.name} · ${c.effDuration.toFixed(1)}초 · ${c.transition==='cut'?'전환 없음':trLabel(c.transition)}`:'클립을 누르면 편집 도구가 열립니다.';if(open)openSheet('clipSheet');}

function updateClipSheet(){const c=selectedClip();$('clipSheetName').textContent=c?c.name:'선택된 클립 없음';if(!c)return;$('videoTrimBox').classList.toggle('hidden',c.type!=='video');$('clipVolumeBox').classList.toggle('hidden',c.type!=='video');$('imageDurationBox').classList.toggle('hidden',c.type!=='image');if(c.type==='video'){$('trimStart').max=c.sourceDuration;$('trimEnd').max=c.sourceDuration;$('trimStart').value=c.trimStart;$('trimEnd').value=c.trimEnd;$('trimLabel').textContent=`${c.trimStart.toFixed(1)} ~ ${c.trimEnd.toFixed(1)}초`;$('clipVolume').value=Math.round((c.volume??1)*100);$('clipVolumeLabel').textContent=`${Math.round((c.volume??1)*100)}%`;}else{$('imageDuration').value=c.duration;$('imageDurationLabel').textContent=`${c.duration.toFixed(1)}초`;}$('transitionDuration').value=c.transitionDuration;$('transitionDurationLabel').textContent=`${c.transitionDuration.toFixed(2)}초`;$('speedRange').value=c.speed||1;$('speedLabel').textContent=`${(+c.speed||1).toFixed(1)}×`;$('transitionButtons').querySelectorAll('button').forEach(b=>b.classList.toggle('active',b.dataset.tr===c.transition));}
function changeClip(mut){const c=selectedClip();if(!c)return;pushHistory();mut(c);recalc();updateClipSheet();renderFrame(state.currentTime,false);}
function splitSelected(){const c=selectedClip();if(!c)return;const idx=state.clips.indexOf(c),local=state.currentTime-c.start;if(local<=.15||local>=c.effDuration-.15)return toast('클립 중간 위치에서 분할해 주세요.');pushHistory();if(c.type==='video'){const src=c.trimStart+local*c.speed;const a={...c,id:uid('clip'),name:`${c.name} A`,trimEnd:src,rt:c.rt},b={...c,id:uid('clip'),name:`${c.name} B`,trimStart:src,rt:c.rt};state.clips.splice(idx,1,a,b);state.selectedClipId=b.id;}else{const a={...c,id:uid('clip'),name:`${c.name} A`,duration:local},b={...c,id:uid('clip'),name:`${c.name} B`,duration:c.effDuration-local};state.clips.splice(idx,1,a,b);state.selectedClipId=b.id;}recalc();renderAll();}
function duplicateSelected(){const c=selectedClip();if(!c)return;pushHistory();const idx=state.clips.indexOf(c),copy={...c,id:uid('clip'),name:`${c.name} 복사`,rt:c.rt};state.clips.splice(idx+1,0,copy);state.selectedClipId=copy.id;recalc();renderAll();}
function deleteSelected(){const c=selectedClip();if(!c)return;pushHistory();const i=state.clips.indexOf(c);state.clips.splice(i,1);state.selectedClipId=state.clips[Math.min(i,state.clips.length-1)]?.id||null;recalc();renderAll();closeSheets();}
function moveSelected(dir){const c=selectedClip();if(!c)return;const i=state.clips.indexOf(c),j=i+dir;if(j<0||j>=state.clips.length)return;pushHistory();[state.clips[i],state.clips[j]]=[state.clips[j],state.clips[i]];recalc();renderAll();}

async function addOverlayFile(file){if(!file)return;const isImage=file.type.startsWith('image/')||/\.(jpe?g|png|webp|bmp)$/i.test(file.name);const isVideo=file.type.startsWith('video/')||/\.(mp4|mov|m4v|webm)$/i.test(file.name);if(!isImage&&!isVideo)return toast('이미지나 영상 파일을 선택해 주세요.');pushHistory();const url=URL.createObjectURL(file),start=clamp(state.currentTime,0,totalDuration()||999),end=Math.min(totalDuration()||start+3,start+3);const o={id:uid('ov'),type:isImage?'image':'video',file,url,name:file.name,start,end:Math.max(start+.1,end),scale:.7,opacity:1,position:'center',x:.5,y:.5,rt:null};state.overlays.push(o);state.selectedOverlayId=o.id;overlayRuntime(o);renderOverlayList();updateOverlayEditor();renderFrame(state.currentTime,false);toast('오버레이를 추가했습니다. 화면에서 끌어 위치를 바꿀 수 있습니다.');}
function renderOverlayList(){const g=$('overlayList');if(!g)return;g.innerHTML='';state.overlays.forEach(o=>{const d=document.createElement('div');d.className=`text-item${o.id===state.selectedOverlayId?' selected-item':''}`;d.innerHTML=`<b>${escapeHtml(o.name)}</b><span>${o.start.toFixed(1)}~${o.end.toFixed(1)}</span>`;d.onclick=()=>{state.selectedOverlayId=o.id;renderOverlayList();updateOverlayEditor();renderFrame(state.currentTime,false);};g.appendChild(d);});}
function updateOverlayEditor(){const o=selectedOverlay(),box=$('overlayEditor');box.classList.toggle('hidden',!o);if(!o)return;$('overlayName').textContent=o.name;$('overlayRangeLabel').textContent=`${o.start.toFixed(1)}~${o.end.toFixed(1)}초`;$('overlayStart').value=o.start.toFixed(1);$('overlayEnd').value=o.end.toFixed(1);$('overlayScale').value=Math.round((o.scale??.7)*100);$('overlayOpacity').value=Math.round((o.opacity??1)*100);if($('overlayScaleLabel'))$('overlayScaleLabel').textContent=`${Math.round((o.scale??.7)*100)}%`;if($('overlayOpacityLabel'))$('overlayOpacityLabel').textContent=`${Math.round((o.opacity??1)*100)}%`;$('overlayPosition').value=o.position||'custom';}
function syncOverlayFromUi(){const o=selectedOverlay();if(!o)return;o.start=clamp(+$('overlayStart').value||0,0,totalDuration()||999);o.end=Math.max(o.start+.1,Math.min(totalDuration()||999,+$('overlayEnd').value||o.start+3));o.scale=clamp((+$('overlayScale').value||70)/100,.12,1.8);o.opacity=clamp((+$('overlayOpacity').value||0)/100,0,1);const pos=$('overlayPosition').value;if(pos!=='custom'){o.position=pos;o.x=.5;o.y=pos==='top'?.22:pos==='bottom'?.78:.5;}else o.position='custom';if($('overlayScaleLabel'))$('overlayScaleLabel').textContent=`${Math.round(o.scale*100)}%`;if($('overlayOpacityLabel'))$('overlayOpacityLabel').textContent=`${Math.round(o.opacity*100)}%`;$('overlayRangeLabel').textContent=`${o.start.toFixed(1)}~${o.end.toFixed(1)}초`;renderOverlayList();renderFrame(state.currentTime,false);}
function deleteOverlay(){const o=selectedOverlay();if(!o)return;pushHistory();if(o.url)URL.revokeObjectURL(o.url);state.overlays=state.overlays.filter(x=>x.id!==o.id);state.selectedOverlayId=state.overlays.at(-1)?.id||null;renderOverlayList();updateOverlayEditor();renderFrame(state.currentTime,false);}
function syncShortsStyleUi(){$('shortsStyleEnabled').checked=!!state.shortsStyle.enabled;document.querySelectorAll('#shortsStylePresets [data-style]').forEach(b=>b.classList.toggle('active',b.dataset.style===state.shortsStyle.presetId));}
function applyShortsStyle(id){pushHistory();state.shortsStyle.presetId=id;state.shortsStyle.enabled=true;syncShortsStyleUi();renderFrame(state.currentTime,false);}
function refreshTextDraft(){renderFrame(state.currentTime,false);}
function resetTextEditor(){state.selectedTextId=null;$('textInput').value='';$('addTextBtn').textContent='문구 추가';renderTextList();renderFrame(state.currentTime,false);}
function loadTextToEditor(t){if(!t)return;state.selectedTextId=t.id;$('textInput').value=t.text;$('textKind').value=t.kind||'caption';$('textFont').value=t.font||'Gowun Dodum';$('textSize').value=+t.size||52;$('textPosition').value=t.position||'bottom';$('textColor').value=t.color||'#ffffff';$('textBg').value=t.bg||'#000000';$('textStart').value=(+t.start||0).toFixed(1);$('textEnd').value=(+t.end||(+t.start||0)+3).toFixed(1);$('addTextBtn').textContent='문구 수정 저장';renderTextList();renderFrame(state.currentTime,false);}
function addText(){const text=$('textInput').value.trim();if(!text)return toast('문구를 입력해 주세요.');const start=clamp(+$('textStart').value||0,0,totalDuration()||999),end=Math.max(start+.1,+$('textEnd').value||start+3),kind=$('textKind').value,font=$('textFont').value,data={text,start,end:clamp(end,0,totalDuration()||end),kind,font,size:+$('textSize').value||52,position:$('textPosition').value,color:$('textColor').value,bg:$('textBg').value};pushHistory();const old=selectedText();if(old)Object.assign(old,data);else state.texts.push({id:uid('text'),...data});resetTextEditor();toast(old?'문구를 수정했습니다.':'문구를 추가했습니다.');}
function renderTextList(){const g=$('textList');g.innerHTML='';state.texts.forEach(t=>{const d=document.createElement('div');d.className=`text-item${t.id===state.selectedTextId?' selected-item':''}`;d.innerHTML=`<b>${escapeHtml(t.text)}</b><span>${t.start.toFixed(1)}~${t.end.toFixed(1)}</span>`;d.onclick=()=>loadTextToEditor(t);const x=document.createElement('button');x.textContent='삭제';x.className='danger';x.onclick=e=>{e.stopPropagation();pushHistory();state.texts=state.texts.filter(v=>v.id!==t.id);if(state.selectedTextId===t.id)resetTextEditor();else{renderTextList();renderFrame(state.currentTime,false);}};d.appendChild(x);g.appendChild(d);});}

function clearMusicMedia(){
  if(state.musicAudio){try{state.musicAudio.pause();}catch{} if(state.musicAudio.tagName==='VIDEO'){try{state.musicAudio.remove();}catch{}}}
  if(state.musicNode){try{state.musicNode.source.disconnect();}catch{} try{state.musicNode.gain.disconnect();}catch{}}
  if(state.musicUrl){try{URL.revokeObjectURL(state.musicUrl);}catch{}}
  state.musicNode=null;state.musicAudio=null;state.musicUrl=null;
}
function loadMusic(file){
  clearMusicMedia();
  state.music=file;
  state.musicUrl=URL.createObjectURL(file);
  const isMp4=(file.type||'').toLowerCase()==='video/mp4'||/\.(mp4|m4v)$/i.test(file.name||'');
  const media=isMp4?document.createElement('video'):new Audio();
  media.src=state.musicUrl;media.preload='auto';media.loop=true;media.volume=state.musicVolume;
  if(isMp4){media.playsInline=true;media.setAttribute('playsinline','');media.style.display='none';media.setAttribute('aria-hidden','true');document.body.appendChild(media);}
  media.addEventListener('error',()=>toast(isMp4?'이 MP4의 오디오를 재생할 수 없습니다.':'이 음악 파일을 재생할 수 없습니다.'),{once:true});
  state.musicAudio=media;
  $('musicStatus').textContent=isMp4?`MP4 BGM · ${file.name}`:file.name;
  if(state.audioCtx)connectMusicAudio();
  toast(isMp4?'MP4 영상의 소리를 BGM으로 추가했습니다.':'BGM을 추가했습니다.');
}
function removeMusic(){clearMusicMedia();state.music=null;$('musicStatus').textContent='선택된 BGM 없음';}

function hydrateCover(){state.coverImage=null;if(!state.coverDataUrl){$('coverPreview').src='';$('coverPreview').classList.add('hidden');$('coverEmpty').classList.remove('hidden');return;}const img=new Image();img.onload=()=>{state.coverImage=img;$('coverPreview').src=state.coverDataUrl;$('coverPreview').classList.remove('hidden');$('coverEmpty').classList.add('hidden');};img.src=state.coverDataUrl;}
function captureCover(){if(!state.clips.length)return toast('먼저 미디어를 넣어주세요.');renderFrame(state.currentTime,false);state.coverDataUrl=canvas.toDataURL('image/jpeg',.92);hydrateCover();toast('현재 화면을 표지로 지정했습니다.');}
function importCover(file){const r=new FileReader();r.onload=()=>{state.coverDataUrl=r.result;hydrateCover();toast('표지 이미지를 지정했습니다.');};r.readAsDataURL(file);}
function dataUrlToBlob(dataUrl){const [head,data]=dataUrl.split(','),mime=(head.match(/:(.*?);/)||[])[1]||'image/jpeg',bin=atob(data),u8=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);return new Blob([u8],{type:mime});}
async function saveBlob(blob,name){if(window.AndroidBridge?.beginFile){try{await saveBlobNative(blob,name);toast('휴대폰에 저장했습니다.');return;}catch(e){console.error(e);}}const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=name;document.body.appendChild(a);a.click();setTimeout(()=>{URL.revokeObjectURL(a.href);a.remove();},3000);}
async function saveBlobNative(blob,name){const buf=await blob.arrayBuffer(),u8=new Uint8Array(buf),chunkSize=384*1024;window.AndroidBridge.beginFile(name,blob.type||'application/octet-stream',u8.length);for(let i=0;i<u8.length;i+=chunkSize){const ch=u8.subarray(i,Math.min(i+chunkSize,u8.length));let bin='';const step=0x8000;for(let j=0;j<ch.length;j+=step)bin+=String.fromCharCode(...ch.subarray(j,j+step));window.AndroidBridge.appendChunk(btoa(bin));await new Promise(r=>setTimeout(r,0));}window.AndroidBridge.finishFile();}
function saveCover(){if(!state.coverDataUrl)return toast('먼저 표지를 지정해 주세요.');saveBlob(dataUrlToBlob(state.coverDataUrl),`LEDOA-COVER-${Date.now()}.jpg`);}

function pickMime(){if(!window.MediaRecorder)return'';const list=['video/mp4;codecs=avc1.42E01E,mp4a.40.2','video/mp4','video/webm;codecs=vp9,opus','video/webm;codecs=vp8,opus','video/webm'];return list.find(x=>MediaRecorder.isTypeSupported(x))||'';}
async function exportVideo(){if(!state.clips.length)return toast('먼저 미디어를 넣어주세요.');if(!canvas.captureStream||!window.MediaRecorder)return toast('이 기기에서는 영상 저장 기능을 지원하지 않습니다.');state.exporting=true;$('startExportBtn').disabled=true;$('exportStatus').textContent='오디오 준비 중';$('exportProgress').style.width='0%';pause();const ac=await ensureAudioGraph().catch(()=>null);if(ac?.state==='suspended')await ac.resume().catch(()=>{});const stream=canvas.captureStream(30);if(state.audioDest){const at=state.audioDest.stream.getAudioTracks()[0];if(at)stream.addTrack(at);}const mime=pickMime();const rec=new MediaRecorder(stream,mime?{mimeType:mime,videoBitsPerSecond:5_000_000}:undefined);state.recorder=rec;state.chunks=[];rec.ondataavailable=e=>{if(e.data?.size)state.chunks.push(e.data);};rec.onstop=async()=>{stopMedia();const type=rec.mimeType||mime||'video/webm',blob=new Blob(state.chunks,{type});const ext=type.includes('mp4')?'mp4':'webm';$('exportStatus').textContent='파일 저장 중';await saveBlob(blob,`LEDOA-SHORTS-${Date.now()}.${ext}`);$('exportStatus').textContent=`완료 · ${ext.toUpperCase()}`;$('startExportBtn').disabled=false;state.exporting=false;toast('영상 저장이 완료되었습니다.');};
  drawCover(ctx);rec.start(700);const hold=state.coverDataUrl?state.coverHold:0,start=performance.now(),full=hold+totalDuration();
  const step=now=>{if(!state.exporting)return;const elapsed=(now-start)/1000;if(elapsed<hold){drawCover(ctx);muteAllForCover();$('exportStatus').textContent=`표지 ${Math.max(0,hold-elapsed).toFixed(1)}초`;}else{const g=elapsed-hold;if(g>=totalDuration()){state.exporting=false;rec.stop();return;}state.currentTime=g;renderFrame(g,true);syncMusic(true);$('exportStatus').textContent=`${fmt(g)} / ${fmt(totalDuration())}`;} $('exportProgress').style.width=`${clamp(elapsed/full*100,0,100)}%`;requestAnimationFrame(step);};requestAnimationFrame(step);
}
function muteAllForCover(){state.clips.forEach(c=>{if(c.rt?.gain)c.rt.gain.gain.value=0;else if(c.rt?.el)c.rt.el.volume=0;});if(state.musicNode)state.musicNode.gain.gain.value=0;if(state.musicAudio)state.musicAudio.pause();}

let sheetHistoryArmed=false;
function anySheetOpen(){return [...document.querySelectorAll('.bottom-sheet')].some(x=>!x.classList.contains('hidden'));}
function hideSheets(){document.body.classList.remove('text-editing');$('sheetBackdrop').classList.add('hidden');document.querySelectorAll('.bottom-sheet').forEach(x=>x.classList.add('hidden'));}
function openSheet(id){hideSheets();document.body.classList.toggle('text-editing',id==='textSheet');$('sheetBackdrop').classList.remove('hidden');$(id).classList.remove('hidden');if(!sheetHistoryArmed){history.pushState({ledoaSheet:true},'');sheetHistoryArmed=true;}if(id==='clipSheet')updateClipSheet();if(id==='overlaySheet'){renderOverlayList();updateOverlayEditor();}if(id==='textSheet'){if(!state.selectedTextId){$('textStart').value=state.currentTime.toFixed(1);$('textEnd').value=Math.min(totalDuration()||state.currentTime+3,state.currentTime+3).toFixed(1);}renderTextList();syncShortsStyleUi();refreshTextDraft();}if(id==='coverSheet')hydrateCover();}
function closeSheets(){const had=anySheetOpen();hideSheets();if(had&&sheetHistoryArmed&&history.state?.ledoaSheet){sheetHistoryArmed=false;history.back();}else sheetHistoryArmed=false;}
window.addEventListener('popstate',()=>{if(anySheetOpen()){sheetHistoryArmed=false;hideSheets();renderFrame(state.currentTime,false);}});
let overlayGesture=null;
function canvasPoint(e){const r=canvas.getBoundingClientRect();return {x:(e.clientX-r.left)*(canvas.width/r.width),y:(e.clientY-r.top)*(canvas.height/r.height)};}
function overlayGestureStart(e){if($('overlaySheet').classList.contains('hidden'))return;const o=selectedOverlay();if(!o||state.currentTime<o.start||state.currentTime>o.end)return;const r=o._drawRect;if(!r)return;const p=canvasPoint(e),hs=Math.max(34,canvas.width*.07),inside=p.x>=r.x-hs&&p.x<=r.x+r.w+hs&&p.y>=r.y-hs&&p.y<=r.y+r.h+hs;if(!inside)return;e.preventDefault();canvas.setPointerCapture?.(e.pointerId);const resize=Math.hypot(p.x-(r.x+r.w),p.y-(r.y+r.h))<=hs*1.35;overlayGesture={id:o.id,mode:resize?'resize':'move',start:p,startX:o.x??.5,startY:o.y??overlayDefaultY(o),startScale:o.scale??.7,baseDist:Math.max(20,Math.hypot(p.x-r.cx,p.y-r.cy))};}
function overlayGestureMove(e){if(!overlayGesture)return;const o=state.overlays.find(x=>x.id===overlayGesture.id);if(!o)return;const p=canvasPoint(e);e.preventDefault();if(overlayGesture.mode==='move'){o.x=clamp(overlayGesture.startX+(p.x-overlayGesture.start.x)/canvas.width,.02,.98);o.y=clamp(overlayGesture.startY+(p.y-overlayGesture.start.y)/canvas.height,.02,.98);o.position='custom';$('overlayPosition').value='custom';}else{const r=o._drawRect,dist=Math.max(12,Math.hypot(p.x-(r?.cx??canvas.width/2),p.y-(r?.cy??canvas.height/2)));o.scale=clamp(overlayGesture.startScale*(dist/overlayGesture.baseDist),.12,1.8);$('overlayScale').value=Math.round(o.scale*100);if($('overlayScaleLabel'))$('overlayScaleLabel').textContent=`${Math.round(o.scale*100)}%`;}renderFrame(state.currentTime,false);}
function overlayGestureEnd(){if(!overlayGesture)return;pushHistory();overlayGesture=null;updateOverlayEditor();}
canvas.addEventListener('pointerdown',overlayGestureStart);canvas.addEventListener('pointermove',overlayGestureMove);canvas.addEventListener('pointerup',overlayGestureEnd);canvas.addEventListener('pointercancel',overlayGestureEnd);
function renderAll(){recalc();renderTimeline();renderTextList();renderOverlayList();updateClipSheet();updateOverlayEditor();$('emptyViewer').classList.toggle('hidden',!!state.clips.length);renderFrame(state.currentTime,false);}

// Events
$('mediaInput').addEventListener('change',e=>{addFiles(e.target.files);e.target.value='';});
$('playBtn').onclick=togglePlay;$('backBtn').onclick=()=>seek(state.currentTime-5);$('forwardBtn').onclick=()=>seek(state.currentTime+5);$('ratioBtn').onclick=cycleRatio;$('undoBtn').onclick=()=>undo();
$('zoomInBtn').onclick=()=>{state.zoom=clamp(state.zoom+.25,.5,3);renderTimeline();};$('zoomOutBtn').onclick=()=>{state.zoom=clamp(state.zoom-.25,.5,3);renderTimeline();};
$('timelineScroll').addEventListener('pointerdown',e=>{if(e.target.closest('.clip-block'))return;const rect=$('timelineScroll').getBoundingClientRect(),x=e.clientX-rect.left+$('timelineScroll').scrollLeft;seek(x/(42*state.zoom));});
$('overlayBtn').onclick=()=>openSheet('overlaySheet');$('clipEditBtn').onclick=()=>selectedClip()?openSheet('clipSheet'):toast('타임라인에서 클립을 먼저 선택해 주세요.');$('textBtn').onclick=()=>openSheet('textSheet');$('musicBtn').onclick=()=>openSheet('musicSheet');$('coverBtn').onclick=()=>openSheet('coverSheet');$('exportBtn').onclick=()=>openSheet('exportSheet');
$('sheetBackdrop').onclick=closeSheets;document.querySelectorAll('.close-sheet').forEach(b=>b.onclick=closeSheets);
$('splitBtn').onclick=()=>splitSelected();$('duplicateBtn').onclick=()=>duplicateSelected();$('deleteBtn').onclick=()=>deleteSelected();$('moveLeftBtn').onclick=()=>moveSelected(-1);$('moveRightBtn').onclick=()=>moveSelected(1);
$('trimStart').oninput=e=>{const c=selectedClip();if(!c)return;c.trimStart=Math.min(+e.target.value,c.trimEnd-.1);recalc();updateClipSheet();renderFrame(state.currentTime,false);};$('trimStart').onchange=()=>pushHistory();
$('trimEnd').oninput=e=>{const c=selectedClip();if(!c)return;c.trimEnd=Math.max(+e.target.value,c.trimStart+.1);recalc();updateClipSheet();renderFrame(state.currentTime,false);};$('trimEnd').onchange=()=>pushHistory();
$('clipVolume').oninput=e=>{const c=selectedClip();if(!c||c.type!=='video')return;c.volume=(+e.target.value||0)/100;$('clipVolumeLabel').textContent=`${e.target.value}%`;applyVideoAudioGains(activeInfo(state.currentTime));};$('clipVolume').onchange=()=>pushHistory();
$('imageDuration').oninput=e=>{const c=selectedClip();if(!c)return;c.duration=+e.target.value;recalc();updateClipSheet();renderFrame(state.currentTime,false);};$('imageDuration').onchange=()=>pushHistory();document.querySelectorAll('[data-sec]').forEach(b=>b.onclick=()=>changeClip(c=>{c.duration=+b.dataset.sec;}));
$('transitionButtons').querySelectorAll('button').forEach(b=>b.onclick=()=>changeClip(c=>{c.transition=b.dataset.tr;}));$('transitionDuration').oninput=e=>{const c=selectedClip();if(!c)return;c.transitionDuration=+e.target.value;recalc();updateClipSheet();};$('transitionDuration').onchange=()=>pushHistory();
$('speedRange').oninput=e=>{const c=selectedClip();if(!c)return;c.speed=+e.target.value;recalc();updateClipSheet();renderFrame(state.currentTime,false);};$('speedRange').onchange=()=>pushHistory();
$('overlayInput').onchange=e=>{const f=e.target.files?.[0];if(f)addOverlayFile(f);e.target.value='';};
['overlayStart','overlayEnd','overlayScale','overlayOpacity','overlayPosition'].forEach(id=>{$(id).addEventListener(id==='overlayScale'||id==='overlayOpacity'?'input':'change',syncOverlayFromUi);});$('deleteOverlayBtn').onclick=deleteOverlay;
$('textKind').onchange=e=>{if(!state.selectedTextId)$('textPosition').value=e.target.value==='title'?'top':'bottom';refreshTextDraft();};
['textInput','textFont','textSize','textPosition','textColor','textBg','textStart','textEnd'].forEach(id=>{const el=$(id);if(!el)return;el.addEventListener(id==='textInput'||id==='textSize'||id==='textColor'||id==='textBg'?'input':'change',refreshTextDraft);});
$('addTextBtn').onclick=addText;if($('newTextBtn'))$('newTextBtn').onclick=()=>{resetTextEditor();$('textStart').value=state.currentTime.toFixed(1);$('textEnd').value=Math.min(totalDuration()||state.currentTime+3,state.currentTime+3).toFixed(1);refreshTextDraft();};$('shortsStyleEnabled').onchange=e=>{pushHistory();state.shortsStyle.enabled=e.target.checked;renderFrame(state.currentTime,false);};document.querySelectorAll('#shortsStylePresets [data-style]').forEach(b=>b.onclick=()=>applyShortsStyle(b.dataset.style));
$('musicInput').onchange=e=>{const f=e.target.files?.[0];if(f)loadMusic(f);e.target.value='';};$('musicVolume').oninput=e=>{state.musicVolume=(+e.target.value||0)/100;$('musicVolumeLabel').textContent=`${e.target.value}%`;if(state.musicNode)state.musicNode.gain.gain.value=state.musicVolume;else if(state.musicAudio)state.musicAudio.volume=state.musicVolume;};$('removeMusicBtn').onclick=removeMusic;
$('captureCoverBtn').onclick=captureCover;$('coverInput').onchange=e=>{const f=e.target.files?.[0];if(f)importCover(f);e.target.value='';};$('coverHold').oninput=e=>{state.coverHold=+e.target.value;$('coverHoldLabel').textContent=`${state.coverHold.toFixed(2)}초`;};$('saveCoverBtn').onclick=saveCover;$('startExportBtn').onclick=exportVideo;
window.addEventListener('resize',()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>renderTimeline(),120);});document.addEventListener('visibilitychange',()=>{if(document.hidden&&state.playing)pause();});
window.addEventListener('beforeunload',()=>{state.clips.forEach(c=>{if(c.url)URL.revokeObjectURL(c.url);});state.overlays.forEach(o=>{if(o.url)URL.revokeObjectURL(o.url);});if(state.musicUrl)URL.revokeObjectURL(state.musicUrl);});

setCanvasRatio('9:16');renderAll();
if(document.fonts?.ready)document.fonts.ready.then(()=>renderFrame(state.currentTime,false)).catch(()=>{});
if('serviceWorker' in navigator && location.protocol.startsWith('http')) navigator.serviceWorker.register('sw.js').catch(()=>{});

'use strict';
let v037TextPreset='news';
const v037OldDraft=draftTextFromUi;
draftTextFromUi=function(){const d=v037OldDraft();if(!d)return d;d.styleMode=$('shortsStyleEnabled')?.checked?'shorts':'caption';d.shortsPreset=v037TextPreset;return d;};
function v037DrawTextItem(c2,t){if(!t)return;if(t.styleMode==='shorts'){const pr=SHORTS_STYLE_PRESETS[t.shortsPreset]||SHORTS_STYLE_PRESETS.news;const pos=t.kind==='title'?'top':'bottom';paintStyleBand(c2,t.text,panelWithTextStyle(pos==='top'?pr.top:pr.bottom,t),pos);}else drawPlainText(c2,t);}
drawTexts=function(c2,g){const draft=draftTextFromUi();state.texts.filter(t=>t.id!==state.selectedTextId&&g>=t.start&&g<=t.end).forEach(t=>v037DrawTextItem(c2,t));if(draft)v037DrawTextItem(c2,draft);};
syncShortsStyleUi=function(){const t=selectedText();if(t){v037TextPreset=t.shortsPreset||'news';$('shortsStyleEnabled').checked=t.styleMode==='shorts';}document.querySelectorAll('#shortsStylePresets [data-style]').forEach(b=>b.classList.toggle('active',b.dataset.style===v037TextPreset));const row=$('shortsStylePresets');if(row)row.style.opacity=$('shortsStyleEnabled').checked?'1':'.45';};
applyShortsStyle=function(id){v037TextPreset=id;$('shortsStyleEnabled').checked=true;syncShortsStyleUi();renderFrame(state.currentTime,false);};
resetTextEditor=function(){state.selectedTextId=null;$('textInput').value='';$('shortsStyleEnabled').checked=false;v037TextPreset='premium';$('addTextBtn').textContent='문구 추가';renderTextList();syncShortsStyleUi();renderFrame(state.currentTime,false);};
loadTextToEditor=function(t){if(!t)return;state.selectedTextId=t.id;$('textInput').value=t.text;$('textKind').value=t.kind||'caption';$('textFont').value=t.font||'Gowun Dodum';$('textSize').value=+t.size||52;$('textPosition').value=t.position||'bottom';$('textColor').value=t.color||'#ffffff';$('textBg').value=t.bg||'#000000';$('textStart').value=(+t.start||0).toFixed(1);$('textEnd').value=(+t.end||(+t.start||0)+3).toFixed(1);$('shortsStyleEnabled').checked=t.styleMode==='shorts';v037TextPreset=t.shortsPreset||'news';$('addTextBtn').textContent='문구 수정 저장';renderTextList();syncShortsStyleUi();renderFrame(state.currentTime,false);};
addText=function(){const text=$('textInput').value.trim();if(!text)return toast('문구를 입력해 주세요.');const start=clamp(+$('textStart').value||0,0,totalDuration()||999),end=Math.max(start+.1,+$('textEnd').value||start+3),kind=$('textKind').value,font=$('textFont').value;const data={text,start,end:clamp(end,0,totalDuration()||end),kind,font,size:+$('textSize').value||52,position:$('textPosition').value,color:$('textColor').value,bg:$('textBg').value,styleMode:$('shortsStyleEnabled').checked?'shorts':'caption',shortsPreset:v037TextPreset};pushHistory();const old=selectedText();if(old)Object.assign(old,data);else state.texts.push({id:uid('text'),...data});resetTextEditor();toast(old?'문구를 수정했습니다.':'문구를 추가했습니다.');};
renderTextList=function(){const g=$('textList');g.innerHTML='';state.texts.forEach(t=>{const d=document.createElement('div');d.className=`text-item${t.id===state.selectedTextId?' selected-item':''}`;const st=t.styleMode==='shorts'?`쇼츠 · ${(SHORTS_STYLE_PRESETS[t.shortsPreset]||SHORTS_STYLE_PRESETS.news).name}`:'자막 스타일';d.innerHTML=`<b>${escapeHtml(t.text)}</b><span>${st} · ${t.start.toFixed(1)}~${t.end.toFixed(1)}</span>`;d.onclick=()=>loadTextToEditor(t);const x=document.createElement('button');x.textContent='삭제';x.className='danger';x.onclick=e=>{e.stopPropagation();pushHistory();state.texts=state.texts.filter(v=>v.id!==t.id);if(state.selectedTextId===t.id)resetTextEditor();else{renderTextList();renderFrame(state.currentTime,false);}};d.appendChild(x);g.appendChild(d);});};
const v037CreateVideoRuntime=createVideoRuntime;
createVideoRuntime=function(c){const rt=v037CreateVideoRuntime(c);if(rt?.el){rt.el.muted=false;rt.el.defaultMuted=false;if(rt.gain)rt.el.volume=1;}return rt;};
connectClipAudio=function(c){if(c.type!=='video'||!c.rt?.el||!state.audioCtx||c.rt.source)return;try{c.rt.el.muted=false;c.rt.el.defaultMuted=false;c.rt.el.volume=1;const source=state.audioCtx.createMediaElementSource(c.rt.el),gain=state.audioCtx.createGain();source.connect(gain);gain.connect(state.audioDest);gain.connect(state.audioCtx.destination);c.rt.source=source;c.rt.gain=gain;}catch(e){console.warn('clip audio connect failed',e);}};
setGain=function(c,g){if(!c||c.type!=='video')return;const level=clamp(g*(c.volume??1),0,1),v=c.rt?.el;if(v){v.muted=false;v.defaultMuted=false;}if(c.rt?.gain){if(v)v.volume=1;c.rt.gain.gain.value=level;}else if(v)v.volume=level;};
const v037AddFiles=addFiles;
addFiles=async function(files){await v037AddFiles(files);if(state.audioCtx)state.clips.forEach(connectClipAudio);state.clips.forEach(c=>{if(c.type==='video'&&c.volume==null)c.volume=1;});};

'use strict';
const v037OverlayRuntimeOld=overlayRuntime;
overlayRuntime=function(o){if(!['image','video'].includes(o.type)){if(!o.rt)o.rt={};return o.rt;}return v037OverlayRuntimeOld(o);};
function v037OverlayBaseSize(o,src,c2){if(o.type==='image'||o.type==='video'){const sw=src?.videoWidth||src?.naturalWidth||src?.width||0,sh=src?.videoHeight||src?.naturalHeight||src?.height||0;return sw&&sh?{sw,sh}:null;}const text=String(o.text||(o.type==='emoji'?'🙂':'★')),base=o.type==='text'?64:o.type==='emoji'?112:104;c2.save();c2.font=`700 ${base}px ${o.type==='text'?fontStack(o.font||'Gowun Dodum'):'system-ui,sans-serif'}`;const sw=Math.max(base*1.15,c2.measureText(text).width+(o.type==='text'?42:28));c2.restore();return{sw,sh:base*1.38};}
overlayRect=function(o,src,c2=ctx){const sz=v037OverlayBaseSize(o,src,c2);if(!sz)return null;let s;if(o.type==='image'||o.type==='video')s=Math.min(canvas.width/sz.sw,canvas.height/sz.sh)*clamp(o.scale??.7,.12,2.2);else s=Math.min(1,canvas.width/(sz.sw*1.15))*clamp(o.scale??.7,.18,2.2);const w=sz.sw*s,h=sz.sh*s,cx=clamp(o.x??.5,.02,.98)*canvas.width,cy=clamp(o.y??overlayDefaultY(o),.02,.98)*canvas.height;return{x:cx-w/2,y:cy-h/2,w,h,cx,cy};};
function v037OverlayAlpha(o,g){let a=clamp(o.opacity??1,0,1),dur=Math.max(.05,o.end-o.start),fi=clamp(+o.fadeIn||0,0,dur),fo=clamp(+o.fadeOut||0,0,dur);if(fi)a*=clamp((g-o.start)/fi,0,1);if(fo)a*=clamp((o.end-g)/fo,0,1);return a;}
function v037OverlayMotion(o,g){const dur=Math.max(.05,o.end-o.start),p=clamp((g-o.start)/dur,0,1),enter=clamp((g-o.start)/Math.min(.45,dur*.35),0,1);let dx=0,dy=0,scale=1,rot=0;switch(o.action||'none'){case'pop':scale=.55+.45*enter;break;case'slide-left':dx=(1-enter)*-canvas.width*.28;break;case'slide-up':dy=(1-enter)*canvas.height*.18;break;case'pulse':scale=1+.07*Math.sin(p*Math.PI*6);break;case'shake':dx=Math.sin(p*Math.PI*12)*canvas.width*.012;rot=Math.sin(p*Math.PI*10)*.025;break;}return{dx,dy,scale,rot};}
function v037DrawOverlayContent(c2,o,src,r){if(o.type==='image'||o.type==='video'){c2.drawImage(src,r.x,r.y,r.w,r.h);return;}const text=String(o.text||'');if(o.type==='text'){const fs=Math.max(18,r.h*.54);c2.font=`700 ${fs}px ${fontStack(o.font||'Gowun Dodum')}`;c2.textAlign='center';c2.textBaseline='middle';if(o.bg&&o.bg!=='transparent'){c2.fillStyle=hexAlpha(o.bg,.68);roundRect(c2,r.x,r.y,r.w,r.h,Math.max(12,r.h*.16));c2.fill();}c2.lineWidth=Math.max(2,fs*.07);c2.strokeStyle='rgba(0,0,0,.62)';c2.strokeText(text,r.cx,r.cy);c2.fillStyle=o.color||'#fff';c2.fillText(text,r.cx,r.cy);return;}const fs=Math.max(24,r.h*.72);c2.font=`700 ${fs}px system-ui,sans-serif`;c2.textAlign='center';c2.textBaseline='middle';if(o.type==='sticker'){c2.fillStyle=hexAlpha(o.bg||'#ffffff',.9);roundRect(c2,r.x,r.y,r.w,r.h,Math.max(14,r.h*.28));c2.fill();c2.strokeStyle='rgba(0,0,0,.22)';c2.lineWidth=Math.max(2,r.h*.025);c2.stroke();}c2.fillStyle=o.color||'#fff';c2.fillText(text||'★',r.cx,r.cy);}
drawOverlayOne=function(c2,o,src,g=state.currentTime){const base=overlayRect(o,src,c2);if(!base)return;const m=v037OverlayMotion(o,g),w=base.w*m.scale,h=base.h*m.scale,cx=base.cx+m.dx,cy=base.cy+m.dy,r={x:cx-w/2,y:cy-h/2,w,h,cx,cy};c2.save();c2.globalAlpha=v037OverlayAlpha(o,g);if(m.rot){c2.translate(cx,cy);c2.rotate(m.rot);v037DrawOverlayContent(c2,o,src,{x:-w/2,y:-h/2,w,h,cx:0,cy:0});}else v037DrawOverlayContent(c2,o,src,r);c2.restore();o._drawRect=r;if(o.id===state.selectedOverlayId&&!state.exporting&&!$('overlaySheet').classList.contains('hidden')){c2.save();c2.strokeStyle='rgba(255,198,94,.98)';c2.lineWidth=Math.max(3,canvas.width*.005);c2.setLineDash([10,7]);c2.strokeRect(r.x,r.y,r.w,r.h);c2.setLineDash([]);const hs=Math.max(24,canvas.width*.045);c2.fillStyle='#f0b85e';c2.fillRect(r.x+r.w-hs/2,r.y+r.h-hs/2,hs,hs);c2.restore();}};
drawOverlays=function(c2,g,forPlay=false){state.overlays.forEach(o=>{if(g<o.start||g>o.end)return;let src=null;if(o.type==='video'){const rt=overlayRuntime(o),v=rt.el,dur=Number.isFinite(v.duration)&&v.duration>0?v.duration:Math.max(.1,o.end-o.start),target=(g-o.start)%dur;if(!forPlay&&Math.abs((v.currentTime||0)-target)>.05)try{v.currentTime=target}catch{}if(forPlay){if(Math.abs((v.currentTime||0)-target)>.4)try{v.currentTime=target}catch{}if(v.paused){const q=v.play();q?.catch?.(()=>{});}}else v.pause();src=v;}else if(o.type==='image')src=overlayRuntime(o).img;drawOverlayOne(c2,o,src,g);});};
function v037OverlayBase(type,name,start,end){return{id:uid('ov'),type,file:null,url:null,name,start,end:Math.max(start+.1,end),scale:type==='text'?.8:.7,opacity:1,position:'center',x:.5,y:.5,fadeIn:.2,fadeOut:.2,action:'none',font:'Gowun Dodum',color:'#ffffff',bg:type==='sticker'?'#ffffff':'#000000',text:'',rt:null};}
const v037AddOverlayFileOld=addOverlayFile;
addOverlayFile=async function(file){if(!file)return;const before=state.overlays.length;await v037AddOverlayFileOld(file);const o=state.overlays.at(-1);if(state.overlays.length>before&&o){o.fadeIn=.2;o.fadeOut=.2;o.action='none';o.x=.5;o.y=.5;v037SelectOverlay(o.id);}};
function v037AddTextOverlay(type,text){text=String(text||'').trim();if(!text)return toast(type==='emoji'?'이모티콘을 입력해 주세요.':'내용을 입력해 주세요.');pushHistory();const start=clamp(state.currentTime,0,totalDuration()||999),end=Math.min(totalDuration()||start+3,start+3),label={text:'문구',emoji:'이모티콘',sticker:'스티커'}[type],o=v037OverlayBase(type,`${label} · ${text.slice(0,12)}`,start,end);o.text=text;if(type==='emoji'){o.scale=.6;o.bg='transparent';}if(type==='sticker'){o.scale=.65;o.bg='#ffffff';}state.overlays.push(o);state.selectedOverlayId=o.id;v037SelectOverlay(o.id);toast(`${label} 오버레이를 추가했습니다.`);}
function v037SelectOverlay(id){state.selectedOverlayId=id;const o=selectedOverlay();if(o&&(state.currentTime<o.start||state.currentTime>o.end))state.currentTime=Math.min(o.end-.01,o.start+.01);renderOverlayList();updateOverlayEditor();renderFrame(state.currentTime,false);}
renderOverlayList=function(){const g=$('overlayList');if(!g)return;g.innerHTML='';state.overlays.forEach(o=>{const d=document.createElement('div');d.className=`text-item${o.id===state.selectedOverlayId?' selected-item':''}`;const lab={image:'이미지',video:'영상',text:'문구',emoji:'이모티콘',sticker:'스티커'}[o.type]||'오버레이';d.innerHTML=`<b>${lab} · ${escapeHtml(o.text||o.name)}</b><span>${o.start.toFixed(1)}~${o.end.toFixed(1)}</span>`;d.onclick=()=>v037SelectOverlay(o.id);g.appendChild(d);});};

'use strict';
const v037UpdateOverlayEditorOld=updateOverlayEditor;
updateOverlayEditor=function(){v037UpdateOverlayEditorOld();const o=selectedOverlay();if(!o)return;$('overlayFadeIn').value=+o.fadeIn||0;$('overlayFadeOut').value=+o.fadeOut||0;$('overlayAction').value=o.action||'none';$('overlayFadeInLabel').textContent=`${(+o.fadeIn||0).toFixed(1)}초`;$('overlayFadeOutLabel').textContent=`${(+o.fadeOut||0).toFixed(1)}초`;const box=$('overlayTextEditBox'),isText=['text','emoji','sticker'].includes(o.type);box.classList.toggle('hidden',!isText);if(isText){$('overlayTextValue').value=o.text||'';$('overlayTextFont').value=o.font||'Gowun Dodum';$('overlayTextColor').value=o.color||'#ffffff';$('overlayTextBg').value=o.bg&&o.bg!=='transparent'?o.bg:'#000000';$('overlayTextFontWrap').classList.toggle('hidden',o.type!=='text');$('overlayTextColorWrap').classList.toggle('hidden',o.type!=='text');$('overlayTextBgWrap').classList.toggle('hidden',o.type==='emoji');}};
const v037SyncOverlayOld=syncOverlayFromUi;
syncOverlayFromUi=function(){v037SyncOverlayOld();const o=selectedOverlay();if(!o)return;o.fadeIn=clamp(+$('overlayFadeIn').value||0,0,2);o.fadeOut=clamp(+$('overlayFadeOut').value||0,0,2);o.action=$('overlayAction').value||'none';if(['text','emoji','sticker'].includes(o.type)){o.text=$('overlayTextValue').value;o.font=$('overlayTextFont').value||o.font;o.color=$('overlayTextColor').value||o.color;if(o.type!=='emoji')o.bg=$('overlayTextBg').value||o.bg;}$('overlayFadeInLabel').textContent=`${o.fadeIn.toFixed(1)}초`;$('overlayFadeOutLabel').textContent=`${o.fadeOut.toFixed(1)}초`;renderOverlayList();renderFrame(state.currentTime,false);};
const v037OldGestureStart=overlayGestureStart,v037OldGestureMove=overlayGestureMove,v037OldGestureEnd=overlayGestureEnd;
canvas.removeEventListener('pointerdown',v037OldGestureStart);canvas.removeEventListener('pointermove',v037OldGestureMove);canvas.removeEventListener('pointerup',v037OldGestureEnd);canvas.removeEventListener('pointercancel',v037OldGestureEnd);
overlayGestureStart=function(e){if($('overlaySheet').classList.contains('hidden'))return;const p=canvasPoint(e),hs=Math.max(40,canvas.width*.075),active=state.overlays.filter(o=>state.currentTime>=o.start&&state.currentTime<=o.end&&o._drawRect);let o=selectedOverlay();const hit=[...active].reverse().find(x=>{const r=x._drawRect;return p.x>=r.x-hs&&p.x<=r.x+r.w+hs&&p.y>=r.y-hs&&p.y<=r.y+r.h+hs;});if(hit&&hit.id!==state.selectedOverlayId){o=hit;state.selectedOverlayId=o.id;renderOverlayList();updateOverlayEditor();}if(!o||state.currentTime<o.start||state.currentTime>o.end||!o._drawRect)return;const r=o._drawRect,inside=p.x>=r.x-hs&&p.x<=r.x+r.w+hs&&p.y>=r.y-hs&&p.y<=r.y+r.h+hs;if(!inside)return;e.preventDefault();e.stopPropagation();canvas.setPointerCapture?.(e.pointerId);const resize=Math.hypot(p.x-(r.x+r.w),p.y-(r.y+r.h))<=hs*1.5;overlayGesture={id:o.id,mode:resize?'resize':'move',start:p,startX:o.x??.5,startY:o.y??overlayDefaultY(o),startScale:o.scale??.7,baseDist:Math.max(20,Math.hypot(p.x-r.cx,p.y-r.cy))};};
overlayGestureMove=function(e){if(!overlayGesture)return;const o=state.overlays.find(x=>x.id===overlayGesture.id);if(!o)return;const p=canvasPoint(e);e.preventDefault();e.stopPropagation();if(overlayGesture.mode==='move'){o.x=clamp(overlayGesture.startX+(p.x-overlayGesture.start.x)/canvas.width,.02,.98);o.y=clamp(overlayGesture.startY+(p.y-overlayGesture.start.y)/canvas.height,.02,.98);o.position='custom';$('overlayPosition').value='custom';}else{const r=o._drawRect,dist=Math.max(12,Math.hypot(p.x-(r?.cx??canvas.width/2),p.y-(r?.cy??canvas.height/2)));o.scale=clamp(overlayGesture.startScale*(dist/overlayGesture.baseDist),.12,2.2);$('overlayScale').value=Math.round(o.scale*100);$('overlayScaleLabel').textContent=`${Math.round(o.scale*100)}%`;}renderFrame(state.currentTime,false);};
overlayGestureEnd=function(e){if(!overlayGesture)return;try{canvas.releasePointerCapture?.(e?.pointerId);}catch{}pushHistory();overlayGesture=null;updateOverlayEditor();};
canvas.addEventListener('pointerdown',overlayGestureStart);canvas.addEventListener('pointermove',overlayGestureMove);canvas.addEventListener('pointerup',overlayGestureEnd);canvas.addEventListener('pointercancel',overlayGestureEnd);
(function(){const style=document.createElement('style');style.textContent=`#previewCanvas{touch-action:none!important;user-select:none;-webkit-user-select:none;-webkit-touch-callout:none}.v037-overlay-add{display:grid;grid-template-columns:1fr 1fr;gap:7px;margin:8px 0}.v037-overlay-add input{grid-column:1/-1;width:100%;border:1px solid #354154;background:#0a1018;color:#eef1f5;border-radius:10px;padding:10px}.v037-stickers{display:flex;gap:6px;overflow-x:auto;margin:6px 0 10px}.v037-stickers button{min-width:42px;height:40px;font-size:20px;padding:0}.v037-text-edit{border:1px solid #273244;border-radius:10px;padding:8px;margin-bottom:8px;background:#0b1119}.v037-text-edit input[type=text],.v037-text-edit select{width:100%;border:1px solid #354154;background:#0a1018;color:#eef1f5;border-radius:9px;padding:8px}`;document.head.appendChild(style);
const sheet=$('overlaySheet'),sub=sheet?.querySelector('.sheet-sub');if(sheet&&sub){const add=document.createElement('div');add.innerHTML=`<div class="v037-overlay-add"><input id="overlayQuickText" type="text" maxlength="60" placeholder="문구 또는 이모티콘 입력"><button id="addOverlayTextBtn">문구 추가</button><button id="addOverlayEmojiBtn">이모티콘 추가</button></div><div class="v037-stickers"><button data-sticker="⭐">⭐</button><button data-sticker="❤️">❤️</button><button data-sticker="👍">👍</button><button data-sticker="😂">😂</button><button data-sticker="🔥">🔥</button><button data-sticker="✓">✓</button></div>`;sub.parentNode.insertBefore(add,sub.nextSibling);sub.textContent='이미지·영상·문구·이모티콘·스티커를 동시에 겹쳐 쓸 수 있습니다. 미리보기에서 직접 끌어 이동하고 오른쪽 아래 금색 손잡이를 끌어 크기를 조절하세요.';}
const ed=$('overlayEditor');if(ed){const title=ed.querySelector('.control-title');const box=document.createElement('div');box.id='overlayTextEditBox';box.className='v037-text-edit hidden';box.innerHTML=`<label>내용 <input id="overlayTextValue" type="text" maxlength="60"></label><div class="two-col"><label id="overlayTextFontWrap">글꼴<select id="overlayTextFont"><option value="Gowun Dodum">고운돋음</option><option value="Nanum Pen Script">편지지손글씨체</option></select></label><label id="overlayTextColorWrap">글자색<input id="overlayTextColor" type="color" value="#ffffff"></label></div><label id="overlayTextBgWrap">배경 <input id="overlayTextBg" type="color" value="#000000"></label>`;title.insertAdjacentElement('afterend',box);const pos=$('overlayPosition')?.closest('label');const fx=document.createElement('div');fx.className='two-col';fx.innerHTML=`<label>페이드 인 <span id="overlayFadeInLabel">0.2초</span><input id="overlayFadeIn" type="range" min="0" max="2" step="0.1" value="0.2"></label><label>페이드 아웃 <span id="overlayFadeOutLabel">0.2초</span><input id="overlayFadeOut" type="range" min="0" max="2" step="0.1" value="0.2"></label>`;pos?.insertAdjacentElement('beforebegin',fx);const action=document.createElement('label');action.innerHTML=`액션 <select id="overlayAction"><option value="none">없음</option><option value="pop">팝업</option><option value="slide-left">왼쪽에서</option><option value="slide-up">아래에서</option><option value="pulse">펄스</option><option value="shake">흔들기</option></select>`;pos?.insertAdjacentElement('afterend',action);$('overlayScale').max='220';}
const card=$('shortsStyleEnabled')?.closest('.control-card');if(card){card.querySelector('.control-title b').textContent='이 문구의 스타일';const note=card.querySelector('.sheet-sub');if(note)note.textContent='문구마다 따로 저장됩니다. 예: 제목은 뉴스+편지지손글씨, 자막은 일반 자막 스타일.';}
$('addOverlayTextBtn').onclick=()=>v037AddTextOverlay('text',$('overlayQuickText').value);$('addOverlayEmojiBtn').onclick=()=>v037AddTextOverlay('emoji',$('overlayQuickText').value);document.querySelectorAll('[data-sticker]').forEach(b=>b.onclick=()=>v037AddTextOverlay('sticker',b.dataset.sticker));['overlayFadeIn','overlayFadeOut','overlayAction','overlayTextValue','overlayTextFont','overlayTextColor','overlayTextBg'].forEach(id=>{const e=$(id);if(e)e.addEventListener(['overlayFadeIn','overlayFadeOut','overlayTextValue','overlayTextColor','overlayTextBg'].includes(id)?'input':'change',syncOverlayFromUi);});syncShortsStyleUi();renderAll();})();

'use strict';
// v0.3.8: per-text background opacity + durable autosave/restore.

// ---------- Text background opacity ----------
const v038DrawPlainTextOld=drawPlainText;
drawPlainText=function(c2,t){
  if(!t)return;
  const y=t.position==='top'?canvas.height*.18:t.position==='center'?canvas.height*.5:canvas.height*.82;
  c2.save();
  c2.font=`500 ${t.size}px ${fontStack(t.font)}`;
  c2.textAlign='center';c2.textBaseline='middle';
  const lines=String(t.text||'').split('\n').slice(0,3),pad=18,lineH=t.size*1.18,
        widths=lines.map(x=>c2.measureText(x).width),bw=Math.min(canvas.width*.92,Math.max(...widths,10)+pad*2),bh=lineH*lines.length+pad;
  const bgA=clamp(t.bgOpacity==null?1:+t.bgOpacity,0,1);
  if(bgA>0){c2.fillStyle=hexAlpha(t.bg||'#000000',.62*bgA);roundRect(c2,(canvas.width-bw)/2,y-bh/2,bw,bh,14);c2.fill();}
  c2.fillStyle=t.color||'#fff';
  lines.forEach((line,i)=>c2.fillText(line,canvas.width/2,y+(i-(lines.length-1)/2)*lineH));
  if(t.draft){c2.strokeStyle='rgba(255,255,255,.55)';c2.lineWidth=2;roundRect(c2,(canvas.width-bw)/2-3,y-bh/2-3,bw+6,bh+6,16);c2.stroke();}
  c2.restore();
};
const v038PanelWithTextStyleOld=panelWithTextStyle;
panelWithTextStyle=function(base,item){
  const p=v038PanelWithTextStyleOld(base,item);
  if(item){
    if(item.bg)p.bg=item.bg;
    const a=clamp(item.bgOpacity==null?1:+item.bgOpacity,0,1);
    p.opacity=Math.round((base.opacity??p.opacity??100)*a);
  }
  return p;
};
const v038DraftOld=draftTextFromUi;
draftTextFromUi=function(){const d=v038DraftOld();if(d)d.bgOpacity=clamp(Number($('textBgOpacity')?.value ?? 0)/100,0,1);return d;};
const v038LoadTextOld=loadTextToEditor;
loadTextToEditor=function(t){v038LoadTextOld(t);if($('textBgOpacity')){$('textBgOpacity').value=Math.round(clamp(t?.bgOpacity==null?1:+t.bgOpacity,0,1)*100);$('textBgOpacityLabel').textContent=`${$('textBgOpacity').value}%`;}};
const v038ResetTextOld=resetTextEditor;
resetTextEditor=function(){v038ResetTextOld();if($('textBgOpacity')){$('textBgOpacity').value=100;$('textBgOpacityLabel').textContent='100%';}};
addText=function(){
  const text=$('textInput').value.trim();if(!text)return toast('문구를 입력해 주세요.');
  const start=clamp(+$('textStart').value||0,0,totalDuration()||999),end=Math.max(start+.1,+$('textEnd').value||start+3),kind=$('textKind').value,font=$('textFont').value;
  const data={text,start,end:clamp(end,0,totalDuration()||end),kind,font,size:+$('textSize').value||52,position:$('textPosition').value,color:$('textColor').value,bg:$('textBg').value,bgOpacity:clamp(Number($('textBgOpacity')?.value ?? 0)/100,0,1),styleMode:$('shortsStyleEnabled').checked?'shorts':'caption',shortsPreset:v037TextPreset};
  pushHistory();const old=selectedText();if(old)Object.assign(old,data);else state.texts.push({id:uid('text'),...data});resetTextEditor();v038ScheduleAutosave();toast(old?'문구를 수정했습니다.':'문구를 추가했습니다.');
};
(function v038InstallTextOpacityUi(){
  const bg=$('textBg');if(!bg||$('textBgOpacity'))return;
  const host=bg.closest('label')||bg.parentElement;const row=document.createElement('label');row.className='v038-bg-opacity';row.innerHTML=`글자 배경 농도 <span id="textBgOpacityLabel">100%</span><input id="textBgOpacity" type="range" min="0" max="100" step="1" value="100"><small>0%로 설정하면 배경 없이 글자만 표시됩니다.</small>`;
  host.insertAdjacentElement('afterend',row);
  $('textBgOpacity').addEventListener('input',e=>{$('textBgOpacityLabel').textContent=`${e.target.value}%`;refreshTextDraft();v038ScheduleAutosave();});
  $('textBgOpacity').addEventListener('change',()=>{pushHistory();v038ScheduleAutosave();});
  const s=document.createElement('style');s.textContent='.v038-bg-opacity{display:block;margin-top:8px}.v038-bg-opacity span{float:right;color:#f0b85e}.v038-bg-opacity input{width:100%;margin-top:7px}.v038-bg-opacity small{display:block;margin-top:5px;color:#aeb8c5;font-size:10px}';document.head.appendChild(s);
})();

// ---------- Durable project autosave ----------
const V038_DB='ledoa-cut-mobile-v1',V038_PROJECT='latest',V038_MEDIA='media';
let v038SaveTimer=0,v038Saving=false,v038Restoring=false,v038LastSave=0;
function v038Db(){return new Promise((resolve,reject)=>{const q=indexedDB.open(V038_DB,1);q.onupgradeneeded=()=>{const db=q.result;if(!db.objectStoreNames.contains('project'))db.createObjectStore('project');if(!db.objectStoreNames.contains(V038_MEDIA))db.createObjectStore(V038_MEDIA);};q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error);});}
function v038Req(r){return new Promise((resolve,reject)=>{r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});}
function v038CloneMeta(o){const x={...o};delete x.file;delete x.url;delete x.rt;delete x._drawRect;return x;}
function v038MediaKey(kind,id){return `${kind}:${id}`;}
async function v038SaveProject(silent=true){
  if(v038Saving||v038Restoring||state.exporting)return false;v038Saving=true;
  try{
    const db=await v038Db();const tx=db.transaction(['project',V038_MEDIA],'readwrite'),ps=tx.objectStore('project'),ms=tx.objectStore(V038_MEDIA),keep=new Set();
    const clips=[];for(const c of state.clips){const m=v038CloneMeta(c),key=v038MediaKey('clip',c.id);m.mediaKey=key;clips.push(m);if(c.file){keep.add(key);ms.put({blob:c.file,name:c.name||c.file.name||'media',type:c.file.type||''},key);}}
    const overlays=[];for(const o of state.overlays){const m=v038CloneMeta(o);if(['image','video'].includes(o.type)){const key=v038MediaKey('overlay',o.id);m.mediaKey=key;if(o.file){keep.add(key);ms.put({blob:o.file,name:o.name||o.file.name||'overlay',type:o.file.type||''},key);}}overlays.push(m);}
    let musicKey=null;if(state.music){musicKey='music:main';keep.add(musicKey);ms.put({blob:state.music,name:state.music.name||'BGM',type:state.music.type||''},musicKey);}
    const project={version:38,savedAt:Date.now(),ratio:state.ratio,currentTime:state.currentTime,zoom:state.zoom,clips,texts:state.texts.map(v=>({...v})),selectedClipId:state.selectedClipId,selectedTextId:state.selectedTextId,overlays,selectedOverlayId:state.selectedOverlayId,shortsStyle:{...state.shortsStyle},coverDataUrl:state.coverDataUrl||null,coverHold:state.coverHold,musicKey,musicVolume:state.musicVolume};
    ps.put(project,V038_PROJECT);
    const keys=await v038Req(ms.getAllKeys());keys.forEach(k=>{if(!keep.has(k))ms.delete(k);});
    await new Promise((resolve,reject)=>{tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error);});
    v038LastSave=Date.now();if(!silent)toast('현재 작업을 자동 저장했습니다.');return true;
  }catch(e){console.warn('LEDOA autosave failed',e);if(!silent)toast('자동 저장에 실패했습니다.');return false;}finally{v038Saving=false;}
}
function v038ScheduleAutosave(delay=650){if(v038Restoring)return;clearTimeout(v038SaveTimer);v038SaveTimer=setTimeout(()=>v038SaveProject(true),delay);}
async function v038RestoreProject(){
  if(v038Restoring)return;v038Restoring=true;
  try{
    const db=await v038Db(),tx=db.transaction(['project',V038_MEDIA],'readonly'),p=await v038Req(tx.objectStore('project').get(V038_PROJECT));if(!p)return;
    pause();stopMedia();state.clips.forEach(c=>{if(c.url)try{URL.revokeObjectURL(c.url)}catch{}});state.overlays.forEach(o=>{if(o.url)try{URL.revokeObjectURL(o.url)}catch{}});clearMusicMedia();
    const ms=tx.objectStore(V038_MEDIA),clips=[];for(const m of p.clips||[]){const c={...m,file:null,url:null,rt:null};delete c.mediaKey;const rec=m.mediaKey?await v038Req(ms.get(m.mediaKey)):null;if(rec?.blob){const f=new File([rec.blob],rec.name||c.name||'media',{type:rec.type||rec.blob.type||''});c.file=f;c.url=URL.createObjectURL(f);}clips.push(c);}
    const overlays=[];for(const m of p.overlays||[]){const o={...m,file:null,url:null,rt:null};delete o.mediaKey;const rec=m.mediaKey?await v038Req(ms.get(m.mediaKey)):null;if(rec?.blob){const f=new File([rec.blob],rec.name||o.name||'overlay',{type:rec.type||rec.blob.type||''});o.file=f;o.url=URL.createObjectURL(f);if(['image','video'].includes(o.type))overlayRuntime(o);}overlays.push(o);}
    state.clips=clips;state.texts=(p.texts||[]).map(t=>({bgOpacity:t.bgOpacity==null?1:t.bgOpacity,...t}));state.overlays=overlays;state.selectedClipId=p.selectedClipId||clips[0]?.id||null;state.selectedTextId=p.selectedTextId||null;state.selectedOverlayId=p.selectedOverlayId||null;state.shortsStyle=p.shortsStyle||state.shortsStyle;state.coverDataUrl=p.coverDataUrl||null;state.coverHold=+p.coverHold||.2;state.musicVolume=p.musicVolume==null?.2:+p.musicVolume;state.zoom=+p.zoom||1;state.currentTime=+p.currentTime||0;hydrateCover();
    if(p.musicKey){const rec=await v038Req(ms.get(p.musicKey));if(rec?.blob){const f=new File([rec.blob],rec.name||'BGM',{type:rec.type||rec.blob.type||''});loadMusic(f);}}
    setCanvasRatio(p.ratio||'9:16');recalc();renderAll();seek(clamp(state.currentTime,0,totalDuration()||0));$('emptyViewer').classList.toggle('hidden',!!state.clips.length);toast('이전 작업을 이어서 불러왔습니다.');
  }catch(e){console.warn('LEDOA restore failed',e);}finally{v038Restoring=false;}
}
const v038PushHistoryOld=pushHistory;pushHistory=function(){v038PushHistoryOld();v038ScheduleAutosave();};
const v038AddFilesOld=addFiles;addFiles=async function(files){await v038AddFilesOld(files);v038ScheduleAutosave(100);};
const v038AddOverlayOld=addOverlayFile;addOverlayFile=async function(file){await v038AddOverlayOld(file);v038ScheduleAutosave(100);};
const v038LoadMusicOld=loadMusic;loadMusic=function(file){v038LoadMusicOld(file);v038ScheduleAutosave(100);};
const v038RemoveMusicOld=removeMusic;removeMusic=function(){v038RemoveMusicOld();v038ScheduleAutosave(100);};
const v038CaptureCoverOld=captureCover;captureCover=function(){v038CaptureCoverOld();v038ScheduleAutosave(100);};
const v038ImportCoverOld=importCover;importCover=function(file){v038ImportCoverOld(file);setTimeout(()=>v038ScheduleAutosave(100),150);};
const v038SyncOverlayOld=syncOverlayFromUi;syncOverlayFromUi=function(){v038SyncOverlayOld();v038ScheduleAutosave();};
const v038OverlayEndOld=overlayGestureEnd;overlayGestureEnd=function(e){v038OverlayEndOld(e);v038ScheduleAutosave(100);};
const v038ExportOld=exportVideo;exportVideo=async function(){if(!state.clips.length)return v038ExportOld();const st=$('exportStatus');if(st)st.textContent='현재 작업 자동 저장 중';await v038SaveProject(false);return v038ExportOld();};
if($('startExportBtn'))$('startExportBtn').onclick=exportVideo;
window.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden')v038SaveProject(true);});
window.addEventListener('pagehide',()=>v038SaveProject(true));
setTimeout(()=>v038RestoreProject(),250);

'use strict';
// v0.3.9: reliable per-text styles + free overlay positioning.
function v039CurrentTextDraft(){if(!textSheetOpen())return null;const text=($('textInput')?.value||'').trim();if(!text)return null;return{id:'draft',draft:true,text,kind:$('textKind')?.value||'caption',font:$('textFont')?.value||'Gowun Dodum',size:+$('textSize')?.value||52,position:$('textPosition')?.value||'bottom',color:$('textColor')?.value||'#ffffff',bg:$('textBg')?.value||'#000000',bgOpacity:clamp(Number($('textBgOpacity')?.value ?? 100)/100,0,1),start:clamp(+$('textStart')?.value||0,0,totalDuration()||999),end:Math.max(.1,+$('textEnd')?.value||3),styleMode:$('shortsStyleEnabled')?.checked?'shorts':'caption',shortsPreset:v037TextPreset||'news'};}
draftTextFromUi=v039CurrentTextDraft;
function v039ShortsPanel(base,item){const p={...base};if(!item)return p;p.font=item.font||p.font;p.size=+item.size||p.size;p.color=item.color||p.color;const a=clamp(item.bgOpacity==null?1:+item.bgOpacity,0,1);p.opacity=Math.round((base.opacity??100)*a);return p;}
panelWithTextStyle=v039ShortsPanel;
function v039DrawTextItem(c2,t){if(!t)return;if(t.styleMode==='shorts'){const pr=SHORTS_STYLE_PRESETS[t.shortsPreset]||SHORTS_STYLE_PRESETS.news;const top=t.kind==='title';paintStyleBand(c2,t.text,v039ShortsPanel(top?pr.top:pr.bottom,t),top?'top':'bottom');}else drawPlainText(c2,t);}
drawTexts=function(c2,g){const editing=textSheetOpen(),draft=editing?v039CurrentTextDraft():null;state.texts.filter(t=>g>=t.start&&g<=t.end&&!(editing&&draft&&t.id===state.selectedTextId)).forEach(t=>v039DrawTextItem(c2,t));if(draft)v039DrawTextItem(c2,draft);};
function v039SyncSelectedTextLive(){const t=selectedText();if(!t)return;const d=v039CurrentTextDraft();if(!d)return;Object.assign(t,{text:d.text,kind:d.kind,font:d.font,size:d.size,position:d.position,color:d.color,bg:d.bg,bgOpacity:d.bgOpacity,start:d.start,end:Math.max(d.start+.1,Math.min(totalDuration()||d.end,d.end)),styleMode:d.styleMode,shortsPreset:d.shortsPreset});renderTextList();v038ScheduleAutosave?.(350);}
syncShortsStyleUi=function(){const t=selectedText();if(t){v037TextPreset=t.shortsPreset||v037TextPreset||'news';$('shortsStyleEnabled').checked=t.styleMode==='shorts';}document.querySelectorAll('#shortsStylePresets [data-style]').forEach(b=>b.classList.toggle('active',b.dataset.style===v037TextPreset));const row=$('shortsStylePresets');if(row)row.style.opacity=$('shortsStyleEnabled').checked?'1':'.45';};
applyShortsStyle=function(id){v037TextPreset=id||'news';$('shortsStyleEnabled').checked=true;const t=selectedText();if(t){t.styleMode='shorts';t.shortsPreset=v037TextPreset;v038ScheduleAutosave?.(150);}syncShortsStyleUi();renderTextList();renderFrame(state.currentTime,false);};
if($('shortsStyleEnabled')){$('shortsStyleEnabled').onchange=e=>{const t=selectedText();if(t){t.styleMode=e.target.checked?'shorts':'caption';t.shortsPreset=v037TextPreset||t.shortsPreset||'news';v038ScheduleAutosave?.(150);}syncShortsStyleUi();renderTextList();renderFrame(state.currentTime,false);};}
document.querySelectorAll('#shortsStylePresets [data-style]').forEach(b=>{b.onclick=()=>applyShortsStyle(b.dataset.style);});
['textInput','textKind','textFont','textSize','textPosition','textColor','textBg','textBgOpacity','textStart','textEnd'].forEach(id=>{const el=$(id);if(!el)return;const evt=['textInput','textSize','textColor','textBg','textBgOpacity'].includes(id)?'input':'change';el.addEventListener(evt,()=>{v039SyncSelectedTextLive();renderFrame(state.currentTime,false);},{passive:true});});
let v039Drag=null;
function v039OverlayAtPoint(p){return[...state.overlays].reverse().find(o=>{if(state.currentTime<o.start||state.currentTime>o.end||!o._drawRect)return false;const r=o._drawRect,pad=Math.max(24,canvas.width*.025);return p.x>=r.x-pad&&p.x<=r.x+r.w+pad&&p.y>=r.y-pad&&p.y<=r.y+r.h+pad;})||null;}
function v039SetXYUi(o){if(!o)return;if($('overlayX')){$('overlayX').value=Math.round(clamp(o.x??.5,0,1)*100);$('overlayXLabel').textContent=`${$('overlayX').value}%`;}if($('overlayY')){$('overlayY').value=Math.round(clamp(o.y??.5,0,1)*100);$('overlayYLabel').textContent=`${$('overlayY').value}%`;}}
function v039SelectOverlayHit(o){if(!o)return;state.selectedOverlayId=o.id;if(state.currentTime<o.start||state.currentTime>o.end)state.currentTime=Math.min(o.end-.01,o.start+.01);renderOverlayList();updateOverlayEditor();v039SetXYUi(o);renderFrame(state.currentTime,false);}
function v039PointerDown(e){if($('overlaySheet')?.classList.contains('hidden'))return;const p=canvasPoint(e),hit=v039OverlayAtPoint(p);if(!hit)return;e.preventDefault();e.stopImmediatePropagation();v039SelectOverlayHit(hit);const r=hit._drawRect,hs=Math.max(34,canvas.width*.055),onHandle=Math.hypot(p.x-(r.x+r.w),p.y-(r.y+r.h))<=hs*1.25;v039Drag={id:hit.id,pointerId:e.pointerId,mode:onHandle?'resize':'move',start:p,startX:hit.x??.5,startY:hit.y??.5,startScale:hit.scale??.7,baseDist:Math.max(24,Math.hypot(p.x-r.cx,p.y-r.cy))};try{canvas.setPointerCapture(e.pointerId);}catch{}}
function v039PointerMove(e){if(!v039Drag||e.pointerId!==v039Drag.pointerId)return;const o=state.overlays.find(x=>x.id===v039Drag.id);if(!o)return;e.preventDefault();e.stopImmediatePropagation();const p=canvasPoint(e);if(v039Drag.mode==='move'){o.x=clamp(v039Drag.startX+(p.x-v039Drag.start.x)/canvas.width,0,1);o.y=clamp(v039Drag.startY+(p.y-v039Drag.start.y)/canvas.height,0,1);o.position='custom';if($('overlayPosition'))$('overlayPosition').value='custom';v039SetXYUi(o);}else{const r=o._drawRect||{cx:canvas.width*.5,cy:canvas.height*.5},dist=Math.max(16,Math.hypot(p.x-r.cx,p.y-r.cy));o.scale=clamp(v039Drag.startScale*(dist/v039Drag.baseDist),.08,2.5);if($('overlayScale'))$('overlayScale').value=Math.round(o.scale*100);if($('overlayScaleLabel'))$('overlayScaleLabel').textContent=`${Math.round(o.scale*100)}%`;}renderFrame(state.currentTime,false);}
function v039PointerUp(e){if(!v039Drag||e.pointerId!==v039Drag.pointerId)return;e.preventDefault();e.stopImmediatePropagation();try{canvas.releasePointerCapture(e.pointerId);}catch{}v039Drag=null;v038ScheduleAutosave?.(100);updateOverlayEditor();}
canvas.addEventListener('pointerdown',v039PointerDown,true);canvas.addEventListener('pointermove',v039PointerMove,true);canvas.addEventListener('pointerup',v039PointerUp,true);canvas.addEventListener('pointercancel',v039PointerUp,true);
(function v039InstallOverlayXY(){const ed=$('overlayEditor');if(!ed||$('overlayX'))return;const pos=$('overlayPosition')?.closest('label');if(!pos)return;const xy=document.createElement('div');xy.className='two-col v039-xy';xy.innerHTML=`<label>가로 위치 <span id="overlayXLabel">50%</span><input id="overlayX" type="range" min="0" max="100" step="1" value="50"></label><label>세로 위치 <span id="overlayYLabel">50%</span><input id="overlayY" type="range" min="0" max="100" step="1" value="50"></label>`;pos.insertAdjacentElement('afterend',xy);const style=document.createElement('style');style.textContent='.v039-xy span{float:right;color:#f0b85e}.v039-xy input{width:100%;margin-top:7px}#previewCanvas{touch-action:none!important;overscroll-behavior:none!important}';document.head.appendChild(style);const change=()=>{const o=selectedOverlay();if(!o)return;o.x=(+$('overlayX').value||0)/100;o.y=(+$('overlayY').value||0)/100;o.position='custom';$('overlayPosition').value='custom';v039SetXYUi(o);renderFrame(state.currentTime,false);v038ScheduleAutosave?.(250);};$('overlayX').addEventListener('input',change,{passive:true});$('overlayY').addEventListener('input',change,{passive:true});})();
const v039UpdateOverlayEditorOld=updateOverlayEditor;updateOverlayEditor=function(){v039UpdateOverlayEditorOld();v039SetXYUi(selectedOverlay());};
document.querySelectorAll('#textSheet .close-sheet').forEach(b=>b.addEventListener('click',()=>setTimeout(()=>renderFrame(state.currentTime,false),0)));renderTextList();renderFrame(state.currentTime,false);

'use strict';
// v0.4.1: explicit New Project button that clears autosaved project/media and resets editor state.
(function installNewProject(){
  const exportBtn=$('exportBtn');
  if(!exportBtn || $('newProjectBtn')) return;

  const btn=document.createElement('button');
  btn.id='newProjectBtn';
  btn.className='ghost';
  btn.textContent='새로';
  btn.title='새로 만들기';
  btn.setAttribute('aria-label','새로 만들기');
  exportBtn.parentNode.insertBefore(btn,exportBtn);

  const style=document.createElement('style');
  style.textContent=`#newProjectBtn{min-width:42px;padding-left:8px;padding-right:8px}`;
  document.head.appendChild(style);

  const waitSaving=()=>new Promise(resolve=>{
    if(!v038Saving){resolve();return;}
    const started=Date.now();
    const t=setInterval(()=>{
      if(!v038Saving || Date.now()-started>3000){clearInterval(t);resolve();}
    },60);
  });

  async function clearAutosaveStorage(){
    const db=await v038Db();
    await new Promise((resolve,reject)=>{
      const tx=db.transaction(['project',V038_MEDIA],'readwrite');
      tx.objectStore('project').clear();
      tx.objectStore(V038_MEDIA).clear();
      tx.oncomplete=()=>resolve();
      tx.onerror=()=>reject(tx.error);
      tx.onabort=()=>reject(tx.error);
    });
  }

  function releaseCurrentMedia(){
    pause();
    stopMedia();
    for(const c of state.clips){
      if(c.url) try{URL.revokeObjectURL(c.url)}catch{}
      const el=c.rt?.el;
      if(el){try{el.pause();el.removeAttribute('src');el.load();el.remove();}catch{}}
    }
    for(const o of state.overlays){
      if(o.url) try{URL.revokeObjectURL(o.url)}catch{}
      const el=o.rt?.el;
      if(el){try{el.pause();el.removeAttribute('src');el.load();el.remove();}catch{}}
    }
    clearMusicMedia();
  }

  function resetEditorState(){
    releaseCurrentMedia();
    state.clips=[];
    state.selectedClipId=null;
    state.currentTime=0;
    state.playing=false;
    state.playStartPerf=0;
    state.playStartTime=0;
    state.total=0;
    state.zoom=1;
    state.ratio='9:16';
    state.texts=[];
    state.selectedTextId=null;
    state.overlays=[];
    state.selectedOverlayId=null;
    state.shortsStyle={enabled:false,presetId:'premium'};
    state.coverDataUrl=null;
    state.coverImage=null;
    state.coverHold=.2;
    state.music=null;
    state.musicUrl=null;
    state.musicAudio=null;
    state.musicNode=null;
    state.musicVolume=.2;
    state.history=[];
    state.exporting=false;

    if($('undoBtn')) $('undoBtn').disabled=true;
    if($('musicStatus')) $('musicStatus').textContent='선택된 BGM 없음';
    if($('musicVolume')) $('musicVolume').value='20';
    if($('musicVolumeLabel')) $('musicVolumeLabel').textContent='20%';
    if($('mediaInput')) $('mediaInput').value='';
    if($('musicInput')) $('musicInput').value='';
    if($('overlayInput')) $('overlayInput').value='';
    if($('coverInput')) $('coverInput').value='';

    resetTextEditor?.();
    hydrateCover();
    hideSheets();
    setCanvasRatio('9:16');
    recalc();
    renderAll();
    seek(0);
    $('emptyViewer')?.classList.remove('hidden');
  }

  async function newProject(){
    const hasWork=state.clips.length || state.texts.length || state.overlays.length || state.music || state.coverDataUrl;
    if(hasWork){
      const ok=window.confirm('현재 작업을 지우고 새 프로젝트를 시작할까요?\n\n자동 저장된 영상·문구·오버레이·배경음악도 함께 초기화됩니다.');
      if(!ok) return;
    }

    btn.disabled=true;
    const oldLabel=btn.textContent;
    btn.textContent='초기화';
    clearTimeout(v038SaveTimer);
    await waitSaving();
    v038Restoring=true;
    try{
      await clearAutosaveStorage();
      resetEditorState();
    }catch(e){
      console.warn('LEDOA new project reset failed',e);
      toast('새 프로젝트 초기화에 실패했습니다.');
      return;
    }finally{
      v038Restoring=false;
      btn.disabled=false;
      btn.textContent=oldLabel;
    }

    // Save an intentionally blank project so accidental app restarts remain blank.
    await v038SaveProject(true);
    toast('새 프로젝트를 시작했습니다.');
  }

  btn.addEventListener('click',newProject);
})();

'use strict';
// v0.4.3: ensure the Add/Save Text button commits the CURRENT per-text Shorts style.
(function installTextCommitFix(){
  function currentTextData(){
    const text=($('textInput')?.value||'').trim();
    if(!text) return null;
    const start=clamp(+$('textStart')?.value||0,0,totalDuration()||999);
    const requestedEnd=+$('textEnd')?.value||start+3;
    const end=Math.max(start+.1,requestedEnd);
    return {
      text,
      start,
      end:clamp(end,0,totalDuration()||end),
      kind:$('textKind')?.value||'caption',
      font:$('textFont')?.value||'Gowun Dodum',
      size:+$('textSize')?.value||52,
      position:$('textPosition')?.value||'bottom',
      color:$('textColor')?.value||'#ffffff',
      bg:$('textBg')?.value||'#000000',
      bgOpacity:clamp(Number($('textBgOpacity')?.value ?? 100)/100,0,1),
      styleMode:$('shortsStyleEnabled')?.checked?'shorts':'caption',
      shortsPreset:v037TextPreset||'news'
    };
  }

  function commitText(){
    const data=currentTextData();
    if(!data){toast('문구를 입력해 주세요.');return;}

    const old=selectedText();
    pushHistory();
    if(old){
      Object.assign(old,data);
    }else{
      state.texts.push({id:uid('text'),...data});
    }

    // Save first, then clear editor. The stored item keeps its own styleMode/preset/font/size.
    v038ScheduleAutosave?.(100);
    resetTextEditor();
    renderTextList();
    renderFrame(state.currentTime,false);
    toast(old?'문구를 수정했습니다.':'문구를 추가했습니다.');
  }

  // The original page bound the button to an older addText function before later patches
  // replaced addText. Replace the DOM button once so no stale click handler can fire.
  const oldBtn=$('addTextBtn');
  if(oldBtn){
    const newBtn=oldBtn.cloneNode(true);
    oldBtn.replaceWith(newBtn);
    newBtn.addEventListener('click',e=>{
      e.preventDefault();
      e.stopImmediatePropagation();
      commitText();
    },true);
  }

  // Keep the global function aligned for any keyboard/other UI caller.
  addText=commitText;

  // Repaint immediately when a Shorts preset is selected so preview and saved result match.
  document.querySelectorAll('#shortsStylePresets [data-style]').forEach(btn=>{
    btn.addEventListener('click',()=>{
      const id=btn.dataset.style||'news';
      v037TextPreset=id;
      if($('shortsStyleEnabled')) $('shortsStyleEnabled').checked=true;
      const t=selectedText();
      if(t){t.styleMode='shorts';t.shortsPreset=id;}
      syncShortsStyleUi();
      renderFrame(state.currentTime,false);
    },true);
  });

  if($('shortsStyleEnabled')){
    $('shortsStyleEnabled').addEventListener('change',()=>{
      const t=selectedText();
      if(t){
        t.styleMode=$('shortsStyleEnabled').checked?'shorts':'caption';
        t.shortsPreset=v037TextPreset||t.shortsPreset||'news';
      }
      renderFrame(state.currentTime,false);
    },true);
  }
})();


'use strict';
// v0.4.4: keep video overlays synchronized when the main timeline is rewound/replayed.
(function installOverlayReplaySyncFix(){
  function overlayTargetTime(o,g,v){
    const dur=(Number.isFinite(v?.duration)&&v.duration>0)?v.duration:Math.max(.1,(+o.end||0)-(+o.start||0));
    const raw=Math.max(0,g-(+o.start||0));
    return dur>0 ? Math.max(0,Math.min(Math.max(0,dur-.03),raw%dur)) : 0;
  }

  function syncAllOverlayVideos(g,shouldPlay=false,forceSeek=false){
    for(const o of state.overlays){
      if(o.type!=='video') continue;
      const rt=overlayRuntime(o),v=rt?.el;
      if(!v) continue;
      const active=g>=o.start-.001 && g<=o.end+.001;
      if(!active){
        try{v.pause();}catch{}
        // When rewinding to before an overlay, reset it now so replay never starts from its old end frame.
        if(forceSeek && g<o.start+.001){
          const reset=0;
          const apply=()=>{try{v.currentTime=reset;}catch{}};
          if(v.readyState>=1) apply(); else v.addEventListener('loadedmetadata',apply,{once:true});
        }
        continue;
      }

      const target=overlayTargetTime(o,g,v);
      const needSeek=forceSeek || v.ended || !Number.isFinite(v.currentTime) || Math.abs((v.currentTime||0)-target)>.10;
      const startPlay=()=>{
        if(!shouldPlay) { try{v.pause();}catch{} return; }
        v.muted=true;
        v.playsInline=true;
        const q=v.play();
        q?.catch?.(()=>{});
      };
      const apply=()=>{
        if(needSeek){
          try{v.currentTime=target;}catch{}
        }
        startPlay();
      };
      if(v.readyState>=1) apply();
      else{
        try{v.load();}catch{}
        v.addEventListener('loadedmetadata',apply,{once:true});
      }
    }
  }

  // Replace final overlay renderer so every preview frame obeys the global timeline time.
  drawOverlays=function(c2,g,forPlay=false){
    state.overlays.forEach(o=>{
      if(g<o.start||g>o.end) return;
      let src=null;
      if(o.type==='video'){
        const rt=overlayRuntime(o),v=rt.el;
        const target=overlayTargetTime(o,g,v);
        const needSeek=v.ended || !Number.isFinite(v.currentTime) || Math.abs((v.currentTime||0)-target)>(forPlay?.18:.05);
        if(needSeek){
          const apply=()=>{try{v.currentTime=target;}catch{}};
          if(v.readyState>=1) apply(); else v.addEventListener('loadedmetadata',apply,{once:true});
        }
        if(forPlay){
          v.muted=true;v.playsInline=true;
          const q=v.play();q?.catch?.(()=>{});
        }else{
          try{v.pause();}catch{}
        }
        src=v;
      }else if(o.type==='image') src=overlayRuntime(o).img;
      drawOverlayOne(c2,o,src,g);
    });
  };

  // Force a clean overlay seek whenever user moves the timeline.
  seek=function(t){
    const was=state.playing;
    if(was) pause();
    state.currentTime=clamp(t,0,totalDuration()||0);
    syncAllOverlayVideos(state.currentTime,false,true);
    renderFrame(state.currentTime,false);
    if(was) play();
  };

  // Force-reset overlays before replaying from the project start or any paused position.
  play=function(){
    if(!state.clips.length) return toast('먼저 미디어를 넣어주세요.');
    ensureAudioGraph().catch(()=>{});
    if(state.currentTime>=totalDuration()-.03) state.currentTime=0;
    syncAllOverlayVideos(state.currentTime,false,true);
    renderFrame(state.currentTime,false);
    state.playing=true;
    state.playStartPerf=performance.now();
    state.playStartTime=state.currentTime;
    $('playBtn').textContent='Ⅱ';
    syncMusic(true);
    syncAllOverlayVideos(state.currentTime,true,true);
    cancelAnimationFrame(raf);
    raf=requestAnimationFrame(playLoop);
  };

  // If an overlay video naturally reaches ended while the main timeline is still playing,
  // immediately re-sync it to the current project time instead of leaving a frozen frame.
  const oldOverlayRuntime=overlayRuntime;
  overlayRuntime=function(o){
    const rt=oldOverlayRuntime(o);
    if(o?.type==='video'&&rt?.el&&!rt.el.dataset.v044ReplaySync){
      rt.el.dataset.v044ReplaySync='1';
      rt.el.addEventListener('ended',()=>{
        if(!state.playing) return;
        const g=state.currentTime;
        if(g>=o.start-.001&&g<=o.end+.001) syncAllOverlayVideos(g,true,true);
      });
    }
    return rt;
  };
})();


'use strict';
// v0.4.9: LEDOA TV Shorts title layout refinement.
(function installLedoaTvTitleV049(){
  if(typeof SHORTS_STYLE_PRESETS!=='object') return;

  // Unused presets removed from the UI and runtime preset collection.
  try{ delete SHORTS_STYLE_PRESETS.premium; }catch{}
  try{ delete SHORTS_STYLE_PRESETS.dialogue; }catch{}
  if(state?.shortsStyle && !SHORTS_STYLE_PRESETS[state.shortsStyle.presetId]) state.shortsStyle.presetId='news';
  if(typeof v037TextPreset!=='undefined' && !SHORTS_STYLE_PRESETS[v037TextPreset] && v037TextPreset!=='ledoatv') v037TextPreset='news';

  SHORTS_STYLE_PRESETS.ledoatv={
    name:'Ledoa TV',
    top:{height:20,bg:'#ffffff',opacity:100,color:'#000000',font:'Gowun Dodum',size:88,bold:true,align:'center',decor:'none',decorColor:'#000000'},
    bottom:{height:20,bg:'#000000',opacity:55,color:'#ffffff',font:'Gowun Dodum',size:20,bold:false,align:'center',decor:'none',decorColor:'#ffffff'}
  };

  // Remove any old UI buttons in case a cached HTML shell is restored.
  const row=$('shortsStylePresets');
  row?.querySelector('[data-style="premium"]')?.remove();
  row?.querySelector('[data-style="dialogue"]')?.remove();

  // Allow larger title sizes from the editor slider.
  const sizeCtl=$('textSize');
  if(sizeCtl){ sizeCtl.max='160'; }

  const brandImg=new Image();
  brandImg.decoding='async';
  brandImg.src='data:image/png;base64,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';
  brandImg.onload=()=>{try{renderFrame(state.currentTime,false);}catch{}};

  function splitTitle(ctx,text,maxWidth,maxLines=2){
    const src=String(text||'').split(/\n/).map(v=>v.trim()).filter(Boolean);
    const lines=[];
    for(const raw of (src.length?src:[''])){
      let cur='';
      for(const ch of [...raw]){
        const test=cur+ch;
        if(!cur || ctx.measureText(test).width<=maxWidth){ cur=test; }
        else{
          lines.push(cur.trim()); cur=ch;
          if(lines.length>=maxLines-1) break;
        }
      }
      if(cur && lines.length<maxLines) lines.push(cur.trim());
      if(lines.length>=maxLines) break;
    }
    return lines.slice(0,maxLines);
  }

  function drawLedoaTvTitle(c2,text,item){
    if(!text) return;
    const W=canvas.width,H=canvas.height,bandH=Math.round(H*.20);
    c2.save();

    // White header strip.
    c2.fillStyle='#ffffff';
    c2.fillRect(0,0,W,bandH);
    c2.fillStyle='rgba(0,0,0,.08)';
    c2.fillRect(0,bandH-1,W,1);

    // Compact branding block at left.
    const pad=Math.max(12,Math.round(W*.022));
    const brandMaxH=Math.round(bandH*.84);
    const brandMaxW=Math.round(W*.235);
    let bw=Math.round(W*.18),bh=brandMaxH;
    if(brandImg.complete&&brandImg.naturalWidth){
      const r=brandImg.naturalWidth/brandImg.naturalHeight;
      bw=Math.min(brandMaxW,brandMaxH*r);
      bh=bw/r;
      c2.drawImage(brandImg,pad,Math.round((bandH-bh)/2),bw,bh);
    }

    // Title zone deliberately starts farther right and is centered in its own zone.
    const titleLeft=Math.max(Math.round(W*.315), pad+bw+Math.round(W*.035));
    const titleRight=Math.round(W*.97);
    const titleW=Math.max(160,titleRight-titleLeft);
    const titleCX=(titleLeft+titleRight)/2;

    // Subtle separator improves visual balance without adding clutter.
    const dividerX=titleLeft-Math.round(W*.018);
    c2.strokeStyle='rgba(0,0,0,.12)';
    c2.lineWidth=Math.max(1,W/720);
    c2.beginPath();
    c2.moveTo(dividerX,Math.round(bandH*.22));
    c2.lineTo(dividerX,Math.round(bandH*.78));
    c2.stroke();

    const family=(typeof fontStack==='function')?fontStack(item?.font||'Gowun Dodum'):'sans-serif';
    const requested=Math.max(28,Math.min(160,(+item?.size||88)));
    let fs=requested*(W/720);
    let lines=[];

    // Use the user's requested size up to 160. Only shrink when it physically cannot fit.
    for(let i=0;i<24;i++){
      c2.font=`800 ${fs}px ${family}`;
      lines=splitTitle(c2,text,titleW,2);
      const lh=fs*1.08;
      const fitsWidth=lines.every(v=>c2.measureText(v).width<=titleW);
      const fitsHeight=lines.length*lh<=bandH*.76;
      if(fitsWidth&&fitsHeight) break;
      fs*=.94;
    }

    c2.fillStyle='#000000';
    c2.textAlign='center';
    c2.textBaseline='middle';
    c2.font=`800 ${fs}px ${family}`;
    const lh=fs*1.08;
    const y0=bandH/2-((lines.length-1)*lh)/2;
    lines.forEach((line,i)=>c2.fillText(line,titleCX,y0+i*lh));
    c2.restore();
  }

  const previousDraw=v039DrawTextItem;
  v039DrawTextItem=function(c2,t){
    if(t&&t.styleMode==='shorts'&&t.shortsPreset==='ledoatv'&&t.kind==='title'){
      drawLedoaTvTitle(c2,t.text,t); return;
    }
    previousDraw(c2,t);
  };

  // Add/replace LEDOA TV preset button.
  let btn=row?.querySelector('[data-style="ledoatv"]');
  if(!btn&&row){
    btn=document.createElement('button');
    btn.type='button'; btn.dataset.style='ledoatv'; btn.textContent='Ledoa TV'; row.appendChild(btn);
  }
  if(btn){
    const clean=btn.cloneNode(true); btn.replaceWith(clean); btn=clean;
    btn.title='상단 20% 흰색 · 우측 중앙 제목 · 최대 160 크기';
    btn.addEventListener('click',e=>{
      e.preventDefault(); e.stopImmediatePropagation();
      v037TextPreset='ledoatv';
      if($('shortsStyleEnabled')) $('shortsStyleEnabled').checked=true;
      if($('textKind')) $('textKind').value='title';
      if($('textPosition')) $('textPosition').value='top';
      if($('textColor')) $('textColor').value='#000000';
      if($('textBg')) $('textBg').value='#ffffff';
      if($('textSize')) $('textSize').value='88';
      const t=selectedText();
      if(t){
        Object.assign(t,{kind:'title',position:'top',color:'#000000',bg:'#ffffff',size:88,styleMode:'shorts',shortsPreset:'ledoatv'});
        try{v038ScheduleAutosave?.(150);}catch{}
      }
      syncShortsStyleUi(); renderTextList(); renderFrame(state.currentTime,false);
      try{toast('Ledoa TV 제목 스타일을 적용했습니다.');}catch{}
    },true);
  }

  // Migrate selected old unused presets if encountered in saved items.
  try{
    state.texts?.forEach(t=>{ if(t.shortsPreset==='premium'||t.shortsPreset==='dialogue') t.shortsPreset='news'; });
  }catch{}

  try{syncShortsStyleUi();renderFrame(state.currentTime,false);}catch{}
})();


'use strict';
// v0.5.0: LEDOA TV title horizontal-position slider.
(function installLedoaTvTitleXSliderV050(){
  const DEFAULT_X=-6; // slightly left of v0.4.9 balance, per user feedback.

  // Add a dedicated horizontal title-position control to the text editor.
  const sizeCtl=$('textSize');
  let xRow=$('ledoaTitleXRow');
  if(!xRow && sizeCtl){
    const host=sizeCtl.closest('.two-col') || sizeCtl.closest('label') || sizeCtl.parentElement;
    xRow=document.createElement('label');
    xRow.id='ledoaTitleXRow';
    xRow.className='v050-title-x';
    xRow.innerHTML=`<div class="v050-title-x-head"><span>Ledoa TV 제목 좌우 위치</span><strong id="ledoaTitleXLabel">${DEFAULT_X}%</strong></div><input id="ledoaTitleX" type="range" min="-20" max="20" step="1" value="${DEFAULT_X}"><div class="v050-title-x-scale"><span>← 왼쪽</span><span>가운데 0</span><span>오른쪽 →</span></div>`;
    host.insertAdjacentElement('afterend',xRow);
    const style=document.createElement('style');
    style.textContent=`
      .v050-title-x{display:block;margin:10px 0 4px;padding:10px 12px;border:1px solid rgba(255,255,255,.12);border-radius:12px;background:rgba(255,255,255,.025)}
      .v050-title-x-head{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:13px;font-weight:700}
      .v050-title-x-head strong{color:#f0b85e;font-size:13px}
      .v050-title-x input{width:100%;margin:9px 0 2px}
      .v050-title-x-scale{display:flex;justify-content:space-between;color:#87909d;font-size:10px}
    `;
    document.head.appendChild(style);
  }

  const slider=$('ledoaTitleX');
  const label=$('ledoaTitleXLabel');
  function sliderValue(){return clamp((+slider?.value||0),-20,20);}
  function setSlider(v){
    if(!slider)return;
    const n=Math.round(clamp(v==null?DEFAULT_X:+v,-20,20));
    slider.value=String(n);
    if(label)label.textContent=`${n>0?'+':''}${n}%`;
  }
  function isLedoa(){
    const t=selectedText?.();
    return (t?.shortsPreset||v037TextPreset)==='ledoatv' && ($('shortsStyleEnabled')?.checked!==false);
  }
  function updateVisibility(){ if(xRow) xRow.style.display=isLedoa()?'block':'none'; }
  function applyXToSelected(){
    const t=selectedText?.();
    if(t && (t.shortsPreset==='ledoatv' || v037TextPreset==='ledoatv')){
      t.titleOffsetX=sliderValue()/100;
      v038ScheduleAutosave?.(180);
      renderTextList?.();
    }
    renderFrame(state.currentTime,false);
  }

  if(slider){
    slider.addEventListener('input',()=>{setSlider(sliderValue());applyXToSelected();},{passive:true});
    slider.addEventListener('change',()=>{applyXToSelected();});
  }

  // Keep the slider in sync when existing text is loaded.
  const oldLoad=loadTextToEditor;
  loadTextToEditor=function(t){
    oldLoad(t);
    setSlider((t?.titleOffsetX==null?DEFAULT_X*0.01:+t.titleOffsetX)*100);
    updateVisibility();
  };
  const oldReset=resetTextEditor;
  resetTextEditor=function(){oldReset();setSlider(DEFAULT_X);updateVisibility();};

  // Include the X offset in live drafts so the user sees changes instantly before saving.
  const oldDraft=v039CurrentTextDraft;
  v039CurrentTextDraft=function(){
    const d=oldDraft();
    if(d && (d.shortsPreset==='ledoatv'||v037TextPreset==='ledoatv')) d.titleOffsetX=sliderValue()/100;
    return d;
  };
  draftTextFromUi=v039CurrentTextDraft;

  // Re-wrap style UI sync so the slider only appears for LEDOA TV.
  const oldSync=syncShortsStyleUi;
  syncShortsStyleUi=function(){
    oldSync();
    const t=selectedText?.();
    if(t?.shortsPreset==='ledoatv') setSlider((t.titleOffsetX==null?DEFAULT_X*.01:+t.titleOffsetX)*100);
    updateVisibility();
  };

  // New renderer: same v0.4.9 layout, but title center is user-adjustable.
  const brandImgV050=new Image();
  brandImgV050.decoding='async';
  brandImgV050.src='data:image/png;base64,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';
  brandImgV050.onload=()=>{try{renderFrame(state.currentTime,false);}catch{}};

  function splitTitleV050(ctx,text,maxWidth,maxLines=2){
    const src=String(text||'').split(/\n/).map(v=>v.trim()).filter(Boolean),lines=[];
    for(const raw of (src.length?src:[''])){
      let cur='';
      for(const ch of [...raw]){
        const test=cur+ch;
        if(!cur || ctx.measureText(test).width<=maxWidth) cur=test;
        else{lines.push(cur.trim());cur=ch;if(lines.length>=maxLines-1)break;}
      }
      if(cur && lines.length<maxLines)lines.push(cur.trim());
      if(lines.length>=maxLines)break;
    }
    return lines.slice(0,maxLines);
  }

  function drawLedoaTvTitleV050(c2,text,item){
    if(!text)return;
    const W=canvas.width,H=canvas.height,bandH=Math.round(H*.20);
    c2.save();
    c2.fillStyle='#ffffff';c2.fillRect(0,0,W,bandH);
    c2.fillStyle='rgba(0,0,0,.08)';c2.fillRect(0,bandH-1,W,1);

    const pad=Math.max(12,Math.round(W*.022));
    const brandMaxH=Math.round(bandH*.84),brandMaxW=Math.round(W*.235);
    let bw=Math.round(W*.18),bh=brandMaxH;
    if(brandImgV050.complete&&brandImgV050.naturalWidth){
      const r=brandImgV050.naturalWidth/brandImgV050.naturalHeight;
      bw=Math.min(brandMaxW,brandMaxH*r);bh=bw/r;
      c2.drawImage(brandImgV050,pad,Math.round((bandH-bh)/2),bw,bh);
    }

    // Slightly more left-balanced baseline than v0.4.9; slider then moves -20%..+20% of canvas.
    const titleLeft=Math.max(Math.round(W*.285),pad+bw+Math.round(W*.026));
    const titleRight=Math.round(W*.97);
    const baseCX=(titleLeft+titleRight)/2;
    const off=clamp(item?.titleOffsetX==null?DEFAULT_X/100:+item.titleOffsetX,-.20,.20);
    let titleCX=baseCX+off*W;
    titleCX=clamp(titleCX,titleLeft+W*.08,titleRight-W*.08);

    const dividerX=titleLeft-Math.round(W*.016);
    c2.strokeStyle='rgba(0,0,0,.10)';c2.lineWidth=Math.max(1,W/720);
    c2.beginPath();c2.moveTo(dividerX,Math.round(bandH*.22));c2.lineTo(dividerX,Math.round(bandH*.78));c2.stroke();

    const family=(typeof fontStack==='function')?fontStack(item?.font||'Gowun Dodum'):'sans-serif';
    const requested=Math.max(28,Math.min(160,(+item?.size||88)));
    let fs=requested*(W/720),lines=[];
    // Available width is symmetric around the moved center, avoiding clipping at either edge.
    const titleW=Math.max(W*.26,2*Math.min(titleCX-titleLeft,titleRight-titleCX));
    for(let i=0;i<24;i++){
      c2.font=`800 ${fs}px ${family}`;
      lines=splitTitleV050(c2,text,titleW,2);
      const lh=fs*1.08;
      if(lines.every(v=>c2.measureText(v).width<=titleW)&&lines.length*lh<=bandH*.76)break;
      fs*=.94;
    }
    c2.fillStyle='#000000';c2.textAlign='center';c2.textBaseline='middle';c2.font=`800 ${fs}px ${family}`;
    const lh=fs*1.08,y0=bandH/2-((lines.length-1)*lh)/2;
    lines.forEach((line,i)=>c2.fillText(line,titleCX,y0+i*lh));
    c2.restore();
  }

  const previousDrawV050=v039DrawTextItem;
  v039DrawTextItem=function(c2,t){
    if(t&&t.styleMode==='shorts'&&t.shortsPreset==='ledoatv'&&t.kind==='title'){
      drawLedoaTvTitleV050(c2,t.text,t);return;
    }
    previousDrawV050(c2,t);
  };

  // Replace the text commit button one final time so titleOffsetX is actually saved for new and edited text.
  const oldBtn=$('addTextBtn');
  if(oldBtn){
    const btn=oldBtn.cloneNode(true);oldBtn.replaceWith(btn);
    btn.addEventListener('click',e=>{
      e.preventDefault();e.stopImmediatePropagation();
      const text=($('textInput')?.value||'').trim();if(!text){toast('문구를 입력해 주세요.');return;}
      const start=clamp(+$('textStart')?.value||0,0,totalDuration()||999);
      const requestedEnd=+$('textEnd')?.value||start+3,end=Math.max(start+.1,requestedEnd);
      const data={
        text,start,end:clamp(end,0,totalDuration()||end),kind:$('textKind')?.value||'caption',font:$('textFont')?.value||'Gowun Dodum',
        size:+$('textSize')?.value||52,position:$('textPosition')?.value||'bottom',color:$('textColor')?.value||'#ffffff',bg:$('textBg')?.value||'#000000',
        bgOpacity:clamp(Number($('textBgOpacity')?.value ?? 100)/100,0,1),styleMode:$('shortsStyleEnabled')?.checked?'shorts':'caption',shortsPreset:v037TextPreset||'news',
        titleOffsetX:(v037TextPreset==='ledoatv'?sliderValue()/100:0)
      };
      const old=selectedText();pushHistory();
      if(old)Object.assign(old,data);else state.texts.push({id:uid('text'),...data});
      v038ScheduleAutosave?.(100);resetTextEditor();renderTextList();renderFrame(state.currentTime,false);toast(old?'문구를 수정했습니다.':'문구를 추가했습니다.');
    },true);
  }

  // Improve LEDOA TV preset selection: slightly left-balanced default and show slider immediately.
  const ledBtn=document.querySelector('#shortsStylePresets [data-style="ledoatv"]');
  if(ledBtn){
    const btn=ledBtn.cloneNode(true);ledBtn.replaceWith(btn);
    btn.addEventListener('click',e=>{
      e.preventDefault();e.stopImmediatePropagation();
      v037TextPreset='ledoatv';
      if($('shortsStyleEnabled'))$('shortsStyleEnabled').checked=true;
      if($('textKind'))$('textKind').value='title';
      if($('textPosition'))$('textPosition').value='top';
      if($('textColor'))$('textColor').value='#000000';
      if($('textBg'))$('textBg').value='#ffffff';
      if($('textSize') && +$('textSize').value<88)$('textSize').value='88';
      const t=selectedText();
      if(t){Object.assign(t,{kind:'title',position:'top',color:'#000000',bg:'#ffffff',size:Math.max(88,+t.size||88),styleMode:'shorts',shortsPreset:'ledoatv'});if(t.titleOffsetX==null)t.titleOffsetX=DEFAULT_X/100;}
      setSlider(t?.titleOffsetX==null?DEFAULT_X:(+t.titleOffsetX*100));
      syncShortsStyleUi();renderTextList();renderFrame(state.currentTime,false);v038ScheduleAutosave?.(120);
      try{toast('Ledoa TV 스타일 · 제목 좌우 위치를 직접 조절할 수 있습니다.');}catch{}
    },true);
  }

  setSlider(DEFAULT_X);updateVisibility();
  try{syncShortsStyleUi();renderFrame(state.currentTime,false);}catch{}
})();


'use strict';
// v0.5.1: export hang fix — never wait indefinitely at "오디오 준비 중".
(function installExportAudioPrepFixV051(){
  const wait=(ms)=>new Promise(r=>setTimeout(r,ms));
  async function settleWithin(p,ms){
    try{
      return await Promise.race([
        Promise.resolve(p).then(v=>({ok:true,value:v})).catch(error=>({ok:false,error})),
        wait(ms).then(()=>({ok:false,timeout:true}))
      ]);
    }catch(error){return {ok:false,error};}
  }

  // Build the audio graph without allowing AudioContext.resume() to block the export UI forever.
  async function prepareExportAudioV051(){
    let ac=state.audioCtx||null;
    try{
      if(!ac){
        const AC=window.AudioContext||window.webkitAudioContext;
        if(AC){
          ac=new AC();
          state.audioCtx=ac;
          state.audioDest=ac.createMediaStreamDestination();
          for(const c of state.clips) connectClipAudio(c);
          if(state.musicAudio) connectMusicAudio();
        }
      }else{
        if(!state.audioDest) state.audioDest=ac.createMediaStreamDestination();
        for(const c of state.clips) connectClipAudio(c);
        if(state.musicAudio) connectMusicAudio();
      }
      if(ac && ac.state==='suspended'){
        const r=await settleWithin(ac.resume(),1200);
        // Some Android WebViews keep resume() pending. Export must still continue.
        if(!r.ok) console.warn('AudioContext resume did not finish in time; exporting with best available audio.',r);
      }
    }catch(e){console.warn('Audio preparation failed; continuing without blocking.',e);}
    return ac;
  }

  async function exportVideoV051(){
    if(!state.clips.length)return toast('먼저 미디어를 넣어주세요.');
    if(!canvas.captureStream||!window.MediaRecorder)return toast('이 기기에서는 영상 저장 기능을 지원하지 않습니다.');
    if(state.exporting)return;

    // Preserve v0.3.8 autosave behavior, but don't let autosave failure prevent export.
    try{
      const st=$('exportStatus');
      if(st)st.textContent='현재 작업 자동 저장 중';
      if(typeof v038SaveProject==='function') await settleWithin(v038SaveProject(false),1500);
    }catch{}

    state.exporting=true;
    const btn=$('startExportBtn'); if(btn)btn.disabled=true;
    const status=$('exportStatus'); if(status)status.textContent='오디오 준비 중';
    const progress=$('exportProgress'); if(progress)progress.style.width='0%';
    pause();

    // Critical fix: audio prep is bounded and cannot freeze this screen.
    const ac=await prepareExportAudioV051();
    if(status)status.textContent='영상 준비 중';

    let stream;
    try{stream=canvas.captureStream(30);}catch(e){
      state.exporting=false;if(btn)btn.disabled=false;
      if(status)status.textContent='영상 준비 실패';
      return toast('영상 저장 준비에 실패했습니다.');
    }

    // Attach audio only if a valid destination track exists. Otherwise export video instead of hanging.
    try{
      if(state.audioDest){
        const at=state.audioDest.stream?.getAudioTracks?.()[0];
        if(at)stream.addTrack(at);
      }
    }catch(e){console.warn('Audio track attach skipped',e);}

    const mime=pickMime();
    let rec;
    try{
      rec=new MediaRecorder(stream,mime?{mimeType:mime,videoBitsPerSecond:5_000_000}:undefined);
    }catch(e){
      try{rec=new MediaRecorder(stream);}catch(e2){
        state.exporting=false;if(btn)btn.disabled=false;
        if(status)status.textContent='인코더 시작 실패';
        return toast('이 휴대폰에서 영상 인코더를 시작하지 못했습니다.');
      }
    }

    state.recorder=rec;state.chunks=[];
    let finished=false;
    const cleanupFail=(msg)=>{
      if(finished)return;finished=true;
      try{stopMedia();}catch{}
      state.exporting=false;if(btn)btn.disabled=false;
      if(status)status.textContent=msg||'저장 실패';
    };
    rec.onerror=e=>{console.error('MediaRecorder error',e);cleanupFail('영상 저장 오류');toast('영상 저장 중 오류가 발생했습니다.');};
    rec.ondataavailable=e=>{if(e.data?.size)state.chunks.push(e.data);};
    rec.onstop=async()=>{
      if(finished)return;finished=true;
      try{
        stopMedia();
        const type=rec.mimeType||mime||'video/webm';
        const blob=new Blob(state.chunks,{type});
        if(!blob.size)throw new Error('empty recording');
        const ext=type.includes('mp4')?'mp4':'webm';
        if(status)status.textContent='파일 저장 중';
        await saveBlob(blob,`LEDOA-SHORTS-${Date.now()}.${ext}`);
        if(status)status.textContent=`완료 · ${ext.toUpperCase()}`;
        toast('영상 저장이 완료되었습니다.');
      }catch(e){
        console.error(e);if(status)status.textContent='파일 저장 실패';toast('영상 파일 저장에 실패했습니다.');
      }finally{
        if(btn)btn.disabled=false;state.exporting=false;
      }
    };

    try{rec.start(700);}catch(e){cleanupFail('인코더 시작 실패');return toast('영상 인코더를 시작하지 못했습니다.');}
    const hold=state.coverDataUrl?state.coverHold:0,start=performance.now(),full=Math.max(.1,hold+totalDuration());
    try{drawCover(ctx);}catch{}

    const step=now=>{
      if(!state.exporting)return;
      const elapsed=(now-start)/1000;
      if(elapsed<hold){
        try{drawCover(ctx);muteAllForCover();}catch{}
        if(status)status.textContent=`표지 ${Math.max(0,hold-elapsed).toFixed(1)}초`;
      }else{
        const g=elapsed-hold;
        if(g>=totalDuration()){
          // Don't set exporting false before recorder stop; the stop handler owns final state.
          try{rec.stop();}catch(e){cleanupFail('영상 종료 실패');}
          return;
        }
        state.currentTime=g;
        renderFrame(g,true);
        try{syncMusic(true);}catch{}
        if(status)status.textContent=`${fmt(g)} / ${fmt(totalDuration())}`;
      }
      if(progress)progress.style.width=`${clamp(elapsed/full*100,0,100)}%`;
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  exportVideo=exportVideoV051;
  const btn=$('startExportBtn');
  if(btn)btn.onclick=exportVideoV051;
})();


'use strict';
// v0.5.2: export-canvas compatibility fix for Android WebView.
(function installExportCanvasFixV052(){
  const wait=ms=>new Promise(r=>setTimeout(r,ms));
  async function settleWithin(p,ms){
    try{return await Promise.race([
      Promise.resolve(p).then(v=>({ok:true,value:v})).catch(error=>({ok:false,error})),
      wait(ms).then(()=>({ok:false,timeout:true}))
    ]);}catch(error){return {ok:false,error};}
  }

  async function prepAudio(){
    let ac=state.audioCtx||null;
    try{
      if(!ac){
        const AC=window.AudioContext||window.webkitAudioContext;
        if(AC){ac=new AC();state.audioCtx=ac;state.audioDest=ac.createMediaStreamDestination();for(const c of state.clips)connectClipAudio(c);if(state.musicAudio)connectMusicAudio();}
      }else{
        if(!state.audioDest)state.audioDest=ac.createMediaStreamDestination();
        for(const c of state.clips)connectClipAudio(c);if(state.musicAudio)connectMusicAudio();
      }
      if(ac?.state==='suspended') await settleWithin(ac.resume(),900);
    }catch(e){console.warn('v052 audio prep skipped',e);}
    return ac;
  }

  function makeExportCanvas(){
    let c=document.getElementById('ledoaExportCanvasV052');
    if(!c){
      c=document.createElement('canvas');c.id='ledoaExportCanvasV052';
      c.style.cssText='position:fixed;left:-9999px;top:0;width:2px;height:2px;opacity:.001;pointer-events:none;z-index:-9999';
      document.body.appendChild(c);
    }
    c.width=canvas.width;c.height=canvas.height;
    const x=c.getContext('2d',{alpha:false});
    x.fillStyle='#000';x.fillRect(0,0,c.width,c.height);
    try{x.drawImage(canvas,0,0,c.width,c.height);}catch{}
    return {canvas:c,ctx:x};
  }

  function tryCapture(c){
    const errs=[];
    for(const arg of [30, undefined, 0]){
      try{
        const s=(arg===undefined)?c.captureStream():c.captureStream(arg);
        if(s && s.getVideoTracks && s.getVideoTracks().length) return {stream:s,manual:arg===0,error:null};
      }catch(e){errs.push(`${e?.name||'Error'}:${e?.message||e}`);}
    }
    return {stream:null,manual:false,error:errs.join(' | ')};
  }

  async function exportVideoV052(){
    if(!state.clips.length)return toast('먼저 미디어를 넣어주세요.');
    if(!window.MediaRecorder)return toast('이 기기에서는 영상 저장 기능을 지원하지 않습니다.');
    if(state.exporting)return;

    try{const st=$('exportStatus');if(st)st.textContent='현재 작업 자동 저장 중';if(typeof v038SaveProject==='function')await settleWithin(v038SaveProject(false),1200);}catch{}

    state.exporting=true;
    const btn=$('startExportBtn');if(btn)btn.disabled=true;
    const status=$('exportStatus');if(status)status.textContent='오디오 준비 중';
    const progress=$('exportProgress');if(progress)progress.style.width='0%';
    pause();await prepAudio();

    if(status)status.textContent='영상 준비 중';
    // Draw one fresh frame before checking canvas origin cleanliness.
    try{renderFrame(state.currentTime,false);}catch{}
    await new Promise(r=>requestAnimationFrame(()=>r()));

    // If this throws, report it explicitly. v0.5.2 embeds the LEDOA brand to prevent file:// taint.
    try{canvas.toDataURL('image/png',.1);}catch(e){
      console.error('Canvas origin check failed',e);
      state.exporting=false;if(btn)btn.disabled=false;if(status)status.textContent='영상 캔버스 보안 오류';
      return toast('영상 화면을 저장 가능한 상태로 만들지 못했습니다.');
    }

    const ex=makeExportCanvas();
    let cap=tryCapture(ex.canvas);
    // Fallback to the visible canvas if the offscreen canvas is unsupported.
    if(!cap.stream)cap=tryCapture(canvas);
    if(!cap.stream){
      console.error('captureStream failed',cap.error);
      state.exporting=false;if(btn)btn.disabled=false;if(status)status.textContent='영상 준비 실패';
      return toast('영상 캡처 시작 실패 · WebView 호환성 문제');
    }
    const stream=cap.stream;
    try{if(state.audioDest){const at=state.audioDest.stream?.getAudioTracks?.()[0];if(at)stream.addTrack(at);}}catch(e){console.warn('audio attach skipped',e);}

    const mime=pickMime();let rec;
    try{rec=new MediaRecorder(stream,mime?{mimeType:mime,videoBitsPerSecond:5_000_000}:undefined);}catch(e){
      try{rec=new MediaRecorder(stream);}catch(e2){
        state.exporting=false;if(btn)btn.disabled=false;if(status)status.textContent='인코더 시작 실패';
        return toast('이 휴대폰에서 영상 인코더를 시작하지 못했습니다.');
      }
    }
    state.recorder=rec;state.chunks=[];let ended=false;
    const stopTracks=()=>{try{stream.getTracks().forEach(t=>t.stop?.());}catch{}};
    const fail=msg=>{if(ended)return;ended=true;stopTracks();state.exporting=false;if(btn)btn.disabled=false;if(status)status.textContent=msg;};
    rec.onerror=e=>{console.error('MediaRecorder v052',e);fail('영상 저장 오류');toast('영상 저장 중 오류가 발생했습니다.');};
    rec.ondataavailable=e=>{if(e.data?.size)state.chunks.push(e.data);};
    rec.onstop=async()=>{
      if(ended)return;ended=true;stopTracks();
      try{
        stopMedia();const type=rec.mimeType||mime||'video/webm',blob=new Blob(state.chunks,{type});
        if(!blob.size)throw new Error('empty recording');
        const ext=type.includes('mp4')?'mp4':'webm';if(status)status.textContent='파일 저장 중';
        await saveBlob(blob,`LEDOA-SHORTS-${Date.now()}.${ext}`);if(status)status.textContent=`완료 · ${ext.toUpperCase()}`;toast('영상 저장이 완료되었습니다.');
      }catch(e){console.error(e);if(status)status.textContent='파일 저장 실패';toast('영상 파일 저장에 실패했습니다.');}
      finally{state.exporting=false;if(btn)btn.disabled=false;}
    };

    try{rec.start(500);}catch(e){fail('인코더 시작 실패');return toast('영상 인코더를 시작하지 못했습니다.');}
    const videoTrack=stream.getVideoTracks?.()[0]||null;
    const hold=state.coverDataUrl?state.coverHold:0,start=performance.now(),full=Math.max(.1,hold+totalDuration());
    try{drawCover(ctx);ex.ctx.drawImage(canvas,0,0,ex.canvas.width,ex.canvas.height);videoTrack?.requestFrame?.();}catch{}

    const step=now=>{
      if(!state.exporting)return;
      const elapsed=(now-start)/1000;
      if(elapsed<hold){
        try{drawCover(ctx);muteAllForCover();}catch{}
        if(status)status.textContent=`표지 ${Math.max(0,hold-elapsed).toFixed(1)}초`;
      }else{
        const g=elapsed-hold;
        if(g>=totalDuration()){
          try{rec.stop();}catch(e){fail('영상 종료 실패');}
          return;
        }
        state.currentTime=g;
        try{renderFrame(g,true);syncMusic(true);}catch(e){console.warn('frame render',e);}
        if(status)status.textContent=`${fmt(g)} / ${fmt(totalDuration())}`;
      }
      // Copy the fully composed preview into a dedicated export canvas every frame.
      try{ex.ctx.drawImage(canvas,0,0,ex.canvas.width,ex.canvas.height);videoTrack?.requestFrame?.();}catch(e){console.warn('export canvas copy',e);}
      if(progress)progress.style.width=`${clamp(elapsed/full*100,0,100)}%`;
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  exportVideo=exportVideoV052;
  const btn=$('startExportBtn');if(btn)btn.onclick=exportVideoV052;
})();


'use strict';
// v0.5.3: repair fragmented MP4 duration metadata before saving.
// Android WebView MediaRecorder writes correct fragment timestamps but can leave moov/mvhd/tkhd/mdhd
// durations near 0~1 second. Gallery, Kakao and some uploaders trust those header durations.
(function installMp4DurationRepairV053(){
  const originalSaveBlobV053=saveBlob;

  function type4(u8,o){return String.fromCharCode(u8[o+4],u8[o+5],u8[o+6],u8[o+7]);}
  function u32(d,o){return d.getUint32(o,false);}
  function i32(d,o){return d.getInt32(o,false);}
  function u64(d,o){return Number(d.getBigUint64(o,false));}
  function set64(d,o,v){d.setBigUint64(o,BigInt(Math.max(0,Math.round(v))),false);}
  function boxHeader(u8,d,o,end){
    if(o+8>end)return null;
    let size=u32(d,o),hdr=8;
    if(size===1){if(o+16>end)return null;size=u64(d,o+8);hdr=16;}
    else if(size===0)size=end-o;
    if(size<hdr||o+size>end)return null;
    return {o,size,hdr,end:o+size,type:type4(u8,o)};
  }
  function childBoxes(u8,d,start,end){
    const a=[];let o=start;
    while(o+8<=end){const b=boxHeader(u8,d,o,end);if(!b)break;a.push(b);o=b.end;}
    return a;
  }
  function fullVersion(u8,b){return u8[b.o+b.hdr];}
  function fullFlags(u8,b){const p=b.o+b.hdr;return (u8[p+1]<<16)|(u8[p+2]<<8)|u8[p+3];}
  function trackIdFromTkhd(u8,d,b){const p=b.o+b.hdr,v=fullVersion(u8,b);return u32(d,p+(v===1?20:12));}
  function timescaleFromMdhd(u8,d,b){const p=b.o+b.hdr,v=fullVersion(u8,b);return u32(d,p+(v===1?20:12));}
  function patchDurationBox(u8,d,b,seconds,timescale){
    const p=b.o+b.hdr,v=fullVersion(u8,b),ticks=Math.max(1,Math.round(seconds*timescale));
    if(b.type==='mvhd'||b.type==='mdhd'){
      const off=p+(v===1?24:16); if(v===1)set64(d,off,ticks);else d.setUint32(off,ticks>>>0,false);
    }else if(b.type==='tkhd'){
      const off=p+(v===1?28:20); if(v===1)set64(d,off,ticks);else d.setUint32(off,ticks>>>0,false);
    }
  }

  async function repairMp4DurationV053(blob){
    const ab=await blob.arrayBuffer(),u8=new Uint8Array(ab),d=new DataView(ab);
    const top=childBoxes(u8,d,0,u8.length);
    const moov=top.find(b=>b.type==='moov');
    if(!moov)return blob;
    const moovKids=childBoxes(u8,d,moov.o+moov.hdr,moov.end);
    const mvhd=moovKids.find(b=>b.type==='mvhd');
    if(!mvhd)return blob;
    const mvp=mvhd.o+mvhd.hdr,mvv=fullVersion(u8,mvhd);
    const movieTimescale=u32(d,mvp+(mvv===1?20:12));
    if(!movieTimescale)return blob;

    const tracks=new Map();
    for(const tr of moovKids.filter(b=>b.type==='trak')){
      const ks=childBoxes(u8,d,tr.o+tr.hdr,tr.end),tkhd=ks.find(b=>b.type==='tkhd'),mdia=ks.find(b=>b.type==='mdia');
      if(!tkhd||!mdia)continue;
      const midKids=childBoxes(u8,d,mdia.o+mdia.hdr,mdia.end),mdhd=midKids.find(b=>b.type==='mdhd');
      if(!mdhd)continue;
      const id=trackIdFromTkhd(u8,d,tkhd),timescale=timescaleFromMdhd(u8,d,mdhd);
      if(id&&timescale)tracks.set(id,{id,timescale,tkhd,mdhd,endTicks:0});
    }
    if(!tracks.size)return blob;

    for(const moof of top.filter(b=>b.type==='moof')){
      for(const traf of childBoxes(u8,d,moof.o+moof.hdr,moof.end).filter(b=>b.type==='traf')){
        const ks=childBoxes(u8,d,traf.o+traf.hdr,traf.end),tfhd=ks.find(b=>b.type==='tfhd'),tfdt=ks.find(b=>b.type==='tfdt');
        if(!tfhd)continue;
        const fp=tfhd.o+tfhd.hdr,ff=fullFlags(u8,tfhd);let pos=fp+4;
        const trackId=u32(d,pos);pos+=4;const t=tracks.get(trackId);if(!t)continue;
        if(ff&0x000001)pos+=8; // base_data_offset
        if(ff&0x000002)pos+=4; // sample_description_index
        let defaultDur=0;
        if(ff&0x000008){defaultDur=u32(d,pos);pos+=4;}
        if(ff&0x000010)pos+=4;
        if(ff&0x000020)pos+=4;
        let base=0;
        if(tfdt){const tp=tfdt.o+tfdt.hdr,tv=fullVersion(u8,tfdt);base=tv===1?u64(d,tp+4):u32(d,tp+4);}
        let sum=0;
        for(const trun of ks.filter(b=>b.type==='trun')){
          const rp=trun.o+trun.hdr,rv=fullVersion(u8,trun),rf=fullFlags(u8,trun),count=u32(d,rp+4);let q=rp+8;
          if(rf&0x000001)q+=4;
          if(rf&0x000004)q+=4;
          for(let i=0;i<count;i++){
            let dur=defaultDur;
            if(rf&0x000100){dur=u32(d,q);q+=4;}
            if(rf&0x000200)q+=4;
            if(rf&0x000400)q+=4;
            if(rf&0x000800)q+=4;
            sum+=dur||0;
          }
        }
        t.endTicks=Math.max(t.endTicks,base+sum);
      }
    }

    let maxSec=0;
    for(const t of tracks.values())if(t.endTicks>0)maxSec=Math.max(maxSec,t.endTicks/t.timescale);
    if(!(maxSec>0.2))return blob;

    patchDurationBox(u8,d,mvhd,maxSec,movieTimescale);
    for(const t of tracks.values()){
      const sec=t.endTicks>0?t.endTicks/t.timescale:maxSec;
      patchDurationBox(u8,d,t.tkhd,sec,movieTimescale);
      patchDurationBox(u8,d,t.mdhd,sec,t.timescale);
    }
    console.log('v0.5.3 MP4 duration repaired',maxSec,Array.from(tracks.values()).map(t=>({id:t.id,sec:t.endTicks/t.timescale})));
    return new Blob([u8],{type:blob.type||'video/mp4'});
  }

  saveBlob=async function(blob,name){
    const isMp4=(blob?.type||'').toLowerCase().includes('mp4')||String(name||'').toLowerCase().endsWith('.mp4');
    if(isMp4){
      const st=$('exportStatus');if(st)st.textContent='MP4 시간정보 정리 중';
      try{blob=await repairMp4DurationV053(blob);}catch(e){console.error('v053 duration repair failed',e);toast('시간정보 보정 실패 · 원본으로 저장합니다.');}
    }
    return originalSaveBlobV053(blob,name);
  };
})();


'use strict';
// v0.5.4: two-line impact Shorts title template.
// Top line explains the setup; lower line is larger and uses a selectable accent color.
(function installTwoLineImpactV054(){
  if(typeof SHORTS_STYLE_PRESETS!=='object') return;

  const PRESET='impact2';
  SHORTS_STYLE_PRESETS[PRESET]={
    name:'2줄 강조',
    top:{height:22,bg:'#000000',opacity:92,color:'#ff5ba8',font:'Gowun Dodum',size:76,bold:true,align:'center',decor:'none',decorColor:'#ff5ba8'},
    bottom:{height:22,bg:'#000000',opacity:55,color:'#ffffff',font:'Gowun Dodum',size:20,bold:false,align:'center',decor:'none',decorColor:'#ffffff'}
  };

  const row=$('shortsStylePresets');
  let presetBtn=row?.querySelector(`[data-style="${PRESET}"]`);
  if(!presetBtn&&row){
    presetBtn=document.createElement('button');
    presetBtn.type='button';presetBtn.dataset.style=PRESET;presetBtn.textContent='2줄 강조';
    row.appendChild(presetBtn);
  }

  const textInput=$('textInput');
  const note=document.querySelector('#textSheet .live-preview-note');
  const baseNote=note?.textContent||'';
  let panel=$('impact2Panel');
  if(!panel&&textInput){
    panel=document.createElement('div');
    panel.id='impact2Panel';
    panel.className='control-card impact2-panel';
    panel.innerHTML=`
      <div class="control-title"><b>2줄 강조 제목</b><span>아랫줄을 더 크게 강조</span></div>
      <label class="impact2-field">윗줄<input id="impactLine1" type="text" maxlength="48" placeholder="예: SUV 뒤에만 붙어 있는"></label>
      <label class="impact2-field">강조줄<input id="impactLine2" type="text" maxlength="48" placeholder="예: 이 작은 와이퍼의 비밀"></label>
      <div class="impact2-color-row"><span>강조색</span><input id="impactAccent" type="color" value="#ff5ba8"><div class="impact2-swatches" aria-label="강조색 빠른 선택">
        <button type="button" data-c="#ff5ba8" style="--sw:#ff5ba8" title="핑크"></button>
        <button type="button" data-c="#ffd43b" style="--sw:#ffd43b" title="노랑"></button>
        <button type="button" data-c="#38e6df" style="--sw:#38e6df" title="민트"></button>
        <button type="button" data-c="#63b8ff" style="--sw:#63b8ff" title="하늘"></button>
        <button type="button" data-c="#ff914d" style="--sw:#ff914d" title="주황"></button>
        <button type="button" data-c="#b59cff" style="--sw:#b59cff" title="보라"></button>
      </div></div>`;
    textInput.insertAdjacentElement('beforebegin',panel);
    const style=document.createElement('style');
    style.textContent=`
      .impact2-panel{display:none;border-color:#443143;background:linear-gradient(180deg,#15101a,#0d141d)}
      .impact2-panel .control-title span{color:#ff85bd}
      .impact2-field{display:grid!important;grid-template-columns:52px 1fr!important;align-items:center!important;gap:8px!important;margin:8px 0!important}
      .impact2-field input[type=text]{width:100%;border:1px solid #3c475a;background:#090f16;color:#f5f7fa;border-radius:10px;padding:10px 11px;font-size:13px;outline:none}
      .impact2-field input[type=text]:focus{border-color:#ff6faf;box-shadow:0 0 0 2px rgba(255,91,168,.12)}
      .impact2-color-row{display:grid;grid-template-columns:52px 42px 1fr;gap:8px;align-items:center;margin-top:9px;color:#aeb8c5;font-size:10px}
      #impactAccent{width:38px;height:34px;border:0;background:transparent;padding:0}
      .impact2-swatches{display:flex;gap:6px;overflow:auto;padding:1px}
      .impact2-swatches button{width:26px;height:26px;min-width:26px;border-radius:50%;padding:0;border:2px solid rgba(255,255,255,.22);background:var(--sw)}
      .impact2-swatches button.active{outline:2px solid #fff;outline-offset:1px}
      #shortsStylePresets [data-style="impact2"].active{border-color:#ff6faf;background:#351a29;color:#ffd6e8}
    `;
    document.head.appendChild(style);
  }

  const line1=$('impactLine1'), line2=$('impactLine2'), accent=$('impactAccent');
  let internalSync=false;

  function presetIsActive(){
    const t=selectedText?.();
    return ($('shortsStyleEnabled')?.checked!==false) && ((t?.shortsPreset||v037TextPreset)===PRESET);
  }

  function splitInput(text){
    const s=String(text||'').replace(/\r/g,'').trim();
    if(!s)return['',''];
    const explicit=s.split('\n').map(v=>v.trim()).filter(Boolean);
    if(explicit.length>=2)return[explicit[0],explicit.slice(1).join(' ')];
    if(explicit.length===1){
      const raw=explicit[0], words=raw.split(/\s+/).filter(Boolean);
      if(words.length>=2){
        let best=1,bestDiff=Infinity;
        for(let i=1;i<words.length;i++){
          const a=words.slice(0,i).join(' '),b=words.slice(i).join(' '),d=Math.abs(a.length-b.length);
          if(d<bestDiff){best=i;bestDiff=d;}
        }
        return[words.slice(0,best).join(' '),words.slice(best).join(' ')];
      }
      if([...raw].length>=10){const ch=[...raw],m=Math.ceil(ch.length*.48);return[ch.slice(0,m).join(''),ch.slice(m).join('')];}
      return[raw,''];
    }
    return['',''];
  }

  function setTextFromTwoLines(){
    if(internalSync||!textInput)return;
    internalSync=true;
    const a=(line1?.value||'').trim(),b=(line2?.value||'').trim();
    textInput.value=[a,b].filter(Boolean).join('\n');
    textInput.dispatchEvent(new Event('input',{bubbles:true}));
    internalSync=false;
    renderFrame(state.currentTime,false);
  }

  function syncTwoLinesFromText(text){
    if(internalSync)return;
    internalSync=true;
    const raw=String(text??textInput?.value??'').replace(/\r/g,'');
    const p=raw.split('\n');
    if(line1)line1.value=(p[0]||'').trim();
    if(line2)line2.value=(p.slice(1).join(' ')||'').trim();
    internalSync=false;
  }

  function setAccent(c,dispatch=true){
    const col=c||'#ff5ba8';
    if(accent)accent.value=col;
    if($('textColor')){
      $('textColor').value=col;
      if(dispatch)$('textColor').dispatchEvent(new Event('input',{bubbles:true}));
    }
    document.querySelectorAll('.impact2-swatches [data-c]').forEach(b=>b.classList.toggle('active',b.dataset.c.toLowerCase()===col.toLowerCase()));
    renderFrame(state.currentTime,false);
  }

  function updateImpactUi(){
    const active=presetIsActive();
    if(panel)panel.style.display=active?'block':'none';
    if(textInput)textInput.style.display=active?'none':'';
    if(note)note.textContent=active?'윗줄은 설명, 아랫줄은 강조 제목입니다. 입력 즉시 미리보기에 반영됩니다.':baseNote;
    if(active){
      syncTwoLinesFromText(textInput?.value||selectedText?.()?.text||'');
      const c=selectedText?.()?.color||$('textColor')?.value||'#ff5ba8';setAccent(c,false);
    }
  }

  line1?.addEventListener('input',setTextFromTwoLines,{passive:true});
  line2?.addEventListener('input',setTextFromTwoLines,{passive:true});
  accent?.addEventListener('input',e=>setAccent(e.target.value),{passive:true});
  document.querySelectorAll('.impact2-swatches [data-c]').forEach(b=>b.addEventListener('click',()=>setAccent(b.dataset.c)));
  textInput?.addEventListener('input',()=>{if(!internalSync&&presetIsActive())syncTwoLinesFromText(textInput.value);},{passive:true});

  function applyPreset(){
    v037TextPreset=PRESET;
    if($('shortsStyleEnabled'))$('shortsStyleEnabled').checked=true;
    if($('textKind'))$('textKind').value='title';
    if($('textPosition'))$('textPosition').value='top';
    if($('textSize'))$('textSize').value='76';
    if($('textColor'))$('textColor').value='#ff5ba8';
    if($('textBg'))$('textBg').value='#000000';
    if($('textBgOpacity')){$('textBgOpacity').value='92';if($('textBgOpacityLabel'))$('textBgOpacityLabel').textContent='92%';}
    const t=selectedText?.();
    if(t){
      Object.assign(t,{kind:'title',position:'top',size:Math.max(64,+t.size||76),color:t.color||'#ff5ba8',bg:'#000000',bgOpacity:t.bgOpacity==null?.92:t.bgOpacity,styleMode:'shorts',shortsPreset:PRESET});
      if(textInput)textInput.value=t.text||'';
    }
    syncTwoLinesFromText(textInput?.value||'');
    setAccent(t?.color||'#ff5ba8',false);
    syncShortsStyleUi();updateImpactUi();renderTextList();renderFrame(state.currentTime,false);v038ScheduleAutosave?.(120);
    try{toast('2줄 강조 제목 · 아랫줄 색상을 선택할 수 있습니다.');}catch{}
  }

  if(presetBtn){
    const clean=presetBtn.cloneNode(true);presetBtn.replaceWith(clean);presetBtn=clean;
    presetBtn.title='윗줄 설명 + 더 크고 컬러가 들어간 아랫줄 강조';
    presetBtn.addEventListener('click',e=>{e.preventDefault();e.stopImmediatePropagation();applyPreset();},true);
  }

  const oldLoad054=loadTextToEditor;
  loadTextToEditor=function(t){oldLoad054(t);if(t?.shortsPreset===PRESET){v037TextPreset=PRESET;syncTwoLinesFromText(t.text);setAccent(t.color||'#ff5ba8',false);}updateImpactUi();};
  const oldReset054=resetTextEditor;
  resetTextEditor=function(){oldReset054();syncTwoLinesFromText('');updateImpactUi();};
  const oldSync054=syncShortsStyleUi;
  syncShortsStyleUi=function(){oldSync054();updateImpactUi();};

  function hexRgb(hex){
    let s=String(hex||'#000000').replace('#','');if(s.length===3)s=s.split('').map(c=>c+c).join('');
    const n=parseInt(s,16);return Number.isFinite(n)?[(n>>16)&255,(n>>8)&255,n&255]:[0,0,0];
  }
  function isLight(hex){const [r,g,b]=hexRgb(hex).map(v=>v/255);const f=v=>v<=.03928?v/12.92:Math.pow((v+.055)/1.055,2.4);return .2126*f(r)+.7152*f(g)+.0722*f(b)>.46;}
  function fitFont(c2,text,maxW,startPx,family,weight){
    let fs=Math.max(18,startPx);for(let i=0;i<34;i++){c2.font=`${weight} ${fs}px ${family}`;if(c2.measureText(text).width<=maxW)break;fs*=.94;}return fs;
  }

  function drawImpactTitle(c2,text,item){
    if(!text)return;
    const W=canvas.width,H=canvas.height,bandH=Math.round(H*.22),bg=item?.bg||'#000000',alpha=clamp(item?.bgOpacity==null?.92:+item.bgOpacity,0,1);
    const [a0,b0]=splitInput(text);let a=a0,b=b0;
    if(!b&&a){b=a;a='';}
    c2.save();
    c2.fillStyle=hexAlpha(bg,alpha);c2.fillRect(0,0,W,bandH);
    // soft bottom separator keeps the title block distinct without looking like a hard card.
    c2.fillStyle=isLight(bg)?'rgba(0,0,0,.10)':'rgba(255,255,255,.08)';c2.fillRect(0,bandH-1,W,1);
    const light=isLight(bg),normal=light?'#101114':'#ffffff',accentCol=item?.color||'#ff5ba8';
    const family='system-ui, -apple-system, "Noto Sans KR", "Gowun Dodum", sans-serif';
    const maxW=W*.88,scale=W/720,requested=clamp(+item?.size||76,42,140)*scale;
    let fs2=fitFont(c2,b,maxW,requested,family,900),fs1=a?fitFont(c2,a,maxW,fs2*.72,family,800):0;
    const gap=Math.max(8,fs2*.10),total=(a?fs1*.98+gap:0)+fs2*1.02;
    let y=bandH/2-total/2;
    c2.textAlign='center';c2.textBaseline='middle';c2.lineJoin='round';
    const stroke=alpha<.58?(light?'rgba(255,255,255,.78)':'rgba(0,0,0,.78)'):null;
    if(a){
      const y1=y+fs1*.49;c2.font=`800 ${fs1}px ${family}`;
      if(stroke){c2.lineWidth=Math.max(2,fs1*.075);c2.strokeStyle=stroke;c2.strokeText(a,W/2,y1);}
      c2.fillStyle=normal;c2.fillText(a,W/2,y1);y+=fs1*.98+gap;
    }
    const y2=y+fs2*.51;c2.font=`900 ${fs2}px ${family}`;
    c2.shadowColor=light?'rgba(255,255,255,.16)':'rgba(0,0,0,.28)';c2.shadowBlur=Math.max(0,fs2*.05);c2.shadowOffsetY=Math.max(0,fs2*.025);
    if(stroke){c2.lineWidth=Math.max(2.5,fs2*.07);c2.strokeStyle=stroke;c2.strokeText(b,W/2,y2);}
    c2.fillStyle=accentCol;c2.fillText(b,W/2,y2);
    c2.restore();
  }

  const previousDraw054=v039DrawTextItem;
  v039DrawTextItem=function(c2,t){
    if(t&&t.styleMode==='shorts'&&t.shortsPreset===PRESET&&t.kind==='title'){drawImpactTitle(c2,t.text,t);return;}
    previousDraw054(c2,t);
  };

  // If a title draft is active, keep the dedicated fields and accent control synchronized.
  const oldDraft054=v039CurrentTextDraft;
  v039CurrentTextDraft=function(){const d=oldDraft054();if(d&&v037TextPreset===PRESET){d.kind='title';d.position='top';d.color=$('textColor')?.value||'#ff5ba8';d.bg=$('textBg')?.value||'#000000';d.bgOpacity=clamp(Number($('textBgOpacity')?.value ?? 92)/100,0,1);d.shortsPreset=PRESET;d.styleMode='shorts';}return d;};
  draftTextFromUi=v039CurrentTextDraft;

  updateImpactUi();
  try{syncShortsStyleUi();renderFrame(state.currentTime,false);}catch{}
})();


'use strict';
// v0.5.9: Shorts-safe subtitle positioning + caption-only style-all + non-persistent guide overlay.
(function installCaptionSafeToolsV055(){
  const SAFE_Y=.70, BOTTOM_UI_START=.76;
  const pos=$('textPosition');
  if(!pos) return;

  // Add the two new position modes without disturbing existing choices.
  const existing=[...pos.options].map(o=>o.value);
  if(!existing.includes('shorts-safe')){
    const o=document.createElement('option');o.value='shorts-safe';o.textContent='쇼츠 안전영역';
    const bottom=[...pos.options].find(x=>x.value==='bottom');
    bottom?pos.insertBefore(o,bottom):pos.appendChild(o);
  }
  if(!existing.includes('custom')){
    const o=document.createElement('option');o.value='custom';o.textContent='사용자 지정';pos.appendChild(o);
  }

  const row=pos.closest('.two-col')||pos.parentElement;
  const box=document.createElement('div');
  box.id='captionPositionTools';box.className='control-card v055-caption-tools';
  box.innerHTML=`
    <div class="control-title"><b>자막 위치</b><span id="captionYLabel">70%</span></div>
    <input id="captionY" type="range" min="10" max="90" step="1" value="70">
    <div class="v055-quick"><button type="button" data-y="64">높게</button><button type="button" data-y="70" class="active">추천 70%</button><button type="button" data-y="76">낮게</button></div>
    <label class="v055-guide-switch"><span>쇼츠 안전영역 표시</span><input id="shortsSafeGuideToggle" type="checkbox"></label>
    <div class="v055-hint">미리보기의 자막을 손가락으로 위·아래 끌어도 위치가 바뀝니다.</div>
    <button id="applyCaptionStyleAll" type="button" class="wide ghost">이 스타일로 전체입력</button>
    <div class="v055-hint v055-only-caption">자막에만 적용됩니다. 제목·2줄 강조·Ledoa TV 제목 설정은 변경하지 않습니다.</div>`;
  row.insertAdjacentElement('afterend',box);

  const style=document.createElement('style');
  style.textContent=`
    .v055-caption-tools{margin-top:8px}.v055-caption-tools>input[type=range]{width:100%;accent-color:#e2ad67}
    .v055-quick{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;margin-top:7px}.v055-quick button{padding:7px 5px;font-size:10px}
    .v055-quick button.active{border-color:#d8a45e;background:#302416;color:#f5d49c}
    .v055-guide-switch{display:flex!important;grid-template-columns:none!important;flex-direction:row!important;justify-content:space-between!important;align-items:center!important;margin-top:10px!important}
    .v055-guide-switch input{width:18px;height:18px;accent-color:#e2ad67}.v055-hint{font-size:10px;color:#aeb8c5;line-height:1.45;margin-top:7px}
    #shortsSafeGuide{position:absolute;inset:0;z-index:12;pointer-events:none;display:none}
    #shortsSafeGuide .v055-bottom-risk{position:absolute;left:0;right:0;top:76%;bottom:0;background:rgba(255,91,91,.13);border-top:1px dashed rgba(255,170,170,.75)}
    #shortsSafeGuide .v055-right-risk{position:absolute;right:0;top:30%;width:17%;height:42%;background:rgba(255,91,91,.10);border-left:1px dashed rgba(255,170,170,.60)}
    #shortsSafeGuide .v055-safe-line{position:absolute;left:7%;right:18%;top:70%;border-top:2px dashed rgba(255,210,112,.95)}
    #shortsSafeGuide .v055-safe-line span{position:absolute;right:0;bottom:5px;padding:3px 5px;border-radius:6px;background:rgba(0,0,0,.72);color:#ffe0a3;font-size:8px}
    #shortsSafeGuide .v055-risk-label{position:absolute;left:8px;bottom:8px;padding:3px 5px;border-radius:6px;background:rgba(0,0,0,.58);color:#ffc4c4;font-size:8px}
    #previewCanvas{touch-action:none!important}
  `;
  document.head.appendChild(style);

  const viewer=$('viewer');
  const guide=document.createElement('div');guide.id='shortsSafeGuide';
  guide.innerHTML=`<div class="v055-bottom-risk"><span class="v055-risk-label">쇼츠 제목·채널·음원 UI 영역</span></div><div class="v055-right-risk"></div><div class="v055-safe-line"><span>추천 자막선 70%</span></div>`;
  viewer?.appendChild(guide);

  const slider=$('captionY'),label=$('captionYLabel'),guideToggle=$('shortsSafeGuideToggle'),applyAll=$('applyCaptionStyleAll');
  function yForPosition(p,t){
    if(p==='top')return .18;if(p==='center')return .50;if(p==='shorts-safe')return SAFE_Y;if(p==='bottom')return .82;
    return clamp(+t?.customY||SAFE_Y,.10,.90);
  }
  function currentY(){
    const t=selectedText();
    return yForPosition(pos.value,t);
  }
  function setSlider(y){
    const n=Math.round(clamp(+y||SAFE_Y,.10,.90)*100);slider.value=n;label.textContent=`${n}%`;
    document.querySelectorAll('.v055-quick [data-y]').forEach(b=>b.classList.toggle('active',+b.dataset.y===n));
  }
  function isCaptionEditor(){return ($('textKind')?.value||'caption')==='caption';}
  function hideGuide(){guideToggle.checked=false;guide.style.display='none';}
  function updateGuide(){guide.style.display=(!textSheetOpen()||!isCaptionEditor()||!guideToggle.checked)?'none':'block';}
  function updateTools(){
    const cap=isCaptionEditor();box.style.display=cap?'block':'none';
    const t=selectedText();applyAll.style.display=(cap&&t?.kind==='caption')?'block':'none';
    setSlider(yForPosition(pos.value,t));updateGuide();
  }
  function setCustomY(y,render=true){
    const n=clamp(+y||SAFE_Y,.10,.90);pos.value='custom';setSlider(n);
    const t=selectedText();if(t&&t.kind==='caption'){t.position='custom';t.customY=n;}
    if(render){renderTextList();renderFrame(state.currentTime,false);v038ScheduleAutosave?.(180);}
  }
  function setPresetPosition(p){
    pos.value=p;const t=selectedText();if(t&&t.kind==='caption'){t.position=p;if(p==='shorts-safe')t.customY=SAFE_Y;}
    setSlider(yForPosition(p,t));renderFrame(state.currentTime,false);v038ScheduleAutosave?.(180);
  }

  slider.addEventListener('input',()=>setCustomY((+slider.value||70)/100),{passive:true});
  document.querySelectorAll('.v055-quick [data-y]').forEach(b=>b.addEventListener('click',()=>setCustomY(+b.dataset.y/100)));
  guideToggle.addEventListener('change',updateGuide);
  pos.addEventListener('change',()=>{const t=selectedText();if(t&&t.kind==='caption'){t.position=pos.value;if(pos.value==='shorts-safe')t.customY=SAFE_Y;}setSlider(yForPosition(pos.value,t));renderFrame(state.currentTime,false);updateGuide();v038ScheduleAutosave?.(180);});
  $('textKind')?.addEventListener('change',()=>{setTimeout(()=>{if(!state.selectedTextId&&isCaptionEditor())setPresetPosition('shorts-safe');updateTools();},0);});

  // Preserve subtitle-only placement in the draft that is drawn while editing.
  const oldDraft=v039CurrentTextDraft;
  v039CurrentTextDraft=function(){
    const d=oldDraft();if(!d)return d;
    if(($('textKind')?.value||d.kind)==='caption'){
      d.kind='caption';
      if(['ledoatv','impact2'].includes(d.shortsPreset)){d.styleMode='caption';}
      d.position=pos.value||d.position||'shorts-safe';
      const t=selectedText();d.customY=(d.position==='custom')?clamp((+slider.value||70)/100,.10,.90):(d.position==='shorts-safe'?SAFE_Y:(t?.customY??SAFE_Y));
    }
    return d;
  };
  draftTextFromUi=v039CurrentTextDraft;

  // Draw normal captions at the exact chosen Y position. Titles/templates keep their own renderer.
  drawPlainText=function(c2,t){
    if(!t)return;
    const y=canvas.height*yForPosition(t.position,t);
    c2.save();c2.font=`500 ${t.size}px ${fontStack(t.font)}`;c2.textAlign='center';c2.textBaseline='middle';
    const lines=String(t.text||'').split('\n').slice(0,3),pad=18,lineH=t.size*1.18,
      widths=lines.map(x=>c2.measureText(x).width),bw=Math.min(canvas.width*.92,Math.max(...widths,10)+pad*2),bh=lineH*lines.length+pad;
    const bgA=clamp(t.bgOpacity==null?1:+t.bgOpacity,0,1);
    if(bgA>0){c2.fillStyle=hexAlpha(t.bg||'#000000',.62*bgA);roundRect(c2,(canvas.width-bw)/2,y-bh/2,bw,bh,14);c2.fill();}
    c2.fillStyle=t.color||'#fff';lines.forEach((line,i)=>c2.fillText(line,canvas.width/2,y+(i-(lines.length-1)/2)*lineH));
    if(t.draft){c2.strokeStyle='rgba(255,214,125,.86)';c2.lineWidth=2;roundRect(c2,(canvas.width-bw)/2-3,y-bh/2-3,bw+6,bh+6,16);c2.stroke();}
    c2.restore();
  };

  // If a caption uses a Shorts preset but the user chooses safe/custom positioning,
  // honor the requested position instead of forcing it into the bottom template band.
  const oldDrawItem=v039DrawTextItem;
  v039DrawTextItem=function(c2,t){
    if(t?.kind==='caption'&&(t.position==='shorts-safe'||t.position==='custom')){drawPlainText(c2,t);return;}
    oldDrawItem(c2,t);
  };

  // Keep custom Y synchronized during live edits.
  const oldSyncLive=v039SyncSelectedTextLive;
  v039SyncSelectedTextLive=function(){oldSyncLive();const t=selectedText();if(t?.kind==='caption'){t.position=pos.value||t.position;t.customY=(t.position==='custom')?clamp((+slider.value||70)/100,.10,.90):(t.position==='shorts-safe'?SAFE_Y:(t.customY??SAFE_Y));}};

  const oldLoad=loadTextToEditor;
  loadTextToEditor=function(t){oldLoad(t);if(t?.kind==='caption'){if(!['top','center','bottom','shorts-safe','custom'].includes(t.position))t.position='shorts-safe';pos.value=t.position||'shorts-safe';setSlider(yForPosition(pos.value,t));}updateTools();};
  const oldReset=resetTextEditor;
  resetTextEditor=function(){oldReset();hideGuide();if(isCaptionEditor()){pos.value='shorts-safe';setSlider(SAFE_Y);}updateTools();};

  // Final text-save handler: includes customY while preserving current title-template fields.
  const oldAdd=$('addTextBtn');
  if(oldAdd){
    const btn=oldAdd.cloneNode(true);oldAdd.replaceWith(btn);
    btn.addEventListener('click',e=>{
      e.preventDefault();e.stopImmediatePropagation();
      const text=($('textInput')?.value||'').trim();if(!text){toast('문구를 입력해 주세요.');return;}
      const start=clamp(+$('textStart')?.value||0,0,totalDuration()||999),req=+$('textEnd')?.value||start+3,end=Math.max(start+.1,req);
      const kind=$('textKind')?.value||'caption',old=selectedText();
      const data={text,start,end:clamp(end,0,totalDuration()||end),kind,font:$('textFont')?.value||'Gowun Dodum',size:+$('textSize')?.value||52,
        position:pos.value||(kind==='caption'?'shorts-safe':'top'),color:$('textColor')?.value||'#ffffff',bg:$('textBg')?.value||'#000000',
        bgOpacity:clamp(Number($('textBgOpacity')?.value ?? 100)/100,0,1),styleMode:(kind==='caption'&&['ledoatv','impact2'].includes(v037TextPreset))?'caption':($('shortsStyleEnabled')?.checked?'shorts':'caption'),shortsPreset:v037TextPreset||'news',
        titleOffsetX:(v037TextPreset==='ledoatv'?clamp((+$('ledoaTitleX')?.value||0)/100,-.2,.2):(old?.titleOffsetX||0))};
      if(kind==='caption')data.customY=data.position==='custom'?clamp((+slider.value||70)/100,.10,.90):(data.position==='shorts-safe'?SAFE_Y:(old?.customY??SAFE_Y));
      pushHistory();if(old)Object.assign(old,data);else state.texts.push({id:uid('text'),...data});
      v038ScheduleAutosave?.(100);hideGuide();resetTextEditor();renderTextList();renderFrame(state.currentTime,false);toast(old?'문구를 수정했습니다.':'문구를 추가했습니다.');
    },true);
    addText=()=>btn.click();
  }

  // Apply ONLY the current caption's visual settings to every caption.
  applyAll.addEventListener('click',()=>{
    const src=selectedText();if(!src||src.kind!=='caption'){toast('기준이 될 자막을 먼저 선택해 주세요.');return;}
    const styleData={
      font:$('textFont')?.value||src.font||'Gowun Dodum',size:+$('textSize')?.value||src.size||52,
      position:pos.value||src.position||'shorts-safe',customY:(pos.value==='custom'?clamp((+slider.value||70)/100,.10,.90):(pos.value==='shorts-safe'?SAFE_Y:(src.customY??SAFE_Y))),
      color:$('textColor')?.value||src.color||'#ffffff',bg:$('textBg')?.value||src.bg||'#000000',bgOpacity:clamp(Number($('textBgOpacity')?.value ?? 100)/100,0,1),
      styleMode:['ledoatv','impact2'].includes(v037TextPreset)?'caption':($('shortsStyleEnabled')?.checked?'shorts':'caption'),shortsPreset:v037TextPreset||src.shortsPreset||'news'
    };
    pushHistory();let count=0;
    state.texts.forEach(t=>{if(t.kind!=='caption')return;Object.assign(t,styleData);count++;});
    // Re-load the selected caption so controls stay synchronized, but do not touch any title.
    Object.assign(src,styleData);setSlider(yForPosition(styleData.position,src));renderTextList();renderFrame(state.currentTime,false);v038ScheduleAutosave?.(100);
    hideGuide();toast(`자막 ${count}개의 스타일을 동일하게 적용했습니다. 제목은 그대로 유지됩니다.`);
  });

  // Drag the visible caption directly in the preview to set its vertical position.
  let drag=null;
  function currentCaptionDraft(){const d=v039CurrentTextDraft();return d?.kind==='caption'?d:null;}
  function captionHit(p,t){
    if(!t)return false;ctx.save();ctx.font=`500 ${t.size}px ${fontStack(t.font)}`;const lines=String(t.text||'').split('\n').slice(0,3),pad=26,lineH=t.size*1.18;
    const bw=Math.min(canvas.width*.94,Math.max(...lines.map(x=>ctx.measureText(x).width),10)+pad*2),bh=lineH*lines.length+pad*1.6,y=canvas.height*yForPosition(t.position,t);ctx.restore();
    return p.x>=(canvas.width-bw)/2-20&&p.x<=(canvas.width+bw)/2+20&&p.y>=y-bh/2-28&&p.y<=y+bh/2+28;
  }
  function down(e){if(!textSheetOpen()||!isCaptionEditor())return;const t=currentCaptionDraft(),p=canvasPoint(e);if(!captionHit(p,t))return;e.preventDefault();e.stopImmediatePropagation();drag={id:e.pointerId};try{canvas.setPointerCapture(e.pointerId);}catch{}setCustomY(p.y/canvas.height,false);renderFrame(state.currentTime,false);}
  function move(e){if(!drag||drag.id!==e.pointerId)return;e.preventDefault();e.stopImmediatePropagation();const p=canvasPoint(e);setCustomY(p.y/canvas.height,false);renderFrame(state.currentTime,false);}
  function up(e){if(!drag||drag.id!==e.pointerId)return;e.preventDefault();e.stopImmediatePropagation();try{canvas.releasePointerCapture(e.pointerId);}catch{}drag=null;v038ScheduleAutosave?.(100);}
  canvas.addEventListener('pointerdown',down,true);canvas.addEventListener('pointermove',move,true);canvas.addEventListener('pointerup',up,true);canvas.addEventListener('pointercancel',up,true);

  // New subtitles start in the safer Shorts position.
  setTimeout(()=>{if(!state.selectedTextId&&isCaptionEditor()){pos.value='shorts-safe';setSlider(SAFE_Y);}updateTools();},0);
  document.querySelectorAll('#textSheet .close-sheet').forEach(b=>b.addEventListener('click',hideGuide));
  const oldOpenSheet=openSheet;
  openSheet=function(id){oldOpenSheet(id);if(id==='textSheet')setTimeout(updateTools,0);else hideGuide();};
})();


'use strict';
// v0.5.10: Android multi-clip playback freeze fix.
// Keep only nearby decoders resident, never pause a runtime that is still used by
// the active clip, and recover a stalled HTMLVideoElement without disturbing the
// master timeline/autosave/export logic.
(function installMainVideoPlaybackFixV0510(){
  const baseCreateVideoRuntime=createVideoRuntime;
  const runtimeWatch=new WeakMap();

  function attachSourceIfNeeded(c,rt){
    const v=rt?.el;
    if(!v||!c?.url) return rt;
    if(v.dataset.ledoaUnloaded==='1' || !v.getAttribute('src')){
      try{
        v.preload='metadata';
        v.src=c.url;
        v.playsInline=true;
        v.setAttribute('playsinline','');
        v.setAttribute('webkit-playsinline','');
        v.load();
      }catch{}
      delete v.dataset.ledoaUnloaded;
      rt.synced=false;
      rt.preloaded=false;
    }
    return rt;
  }

  createVideoRuntime=function(c){
    if(c?.rt?.el) return attachSourceIfNeeded(c,c.rt);
    const rt=baseCreateVideoRuntime(c);
    if(rt?.el){
      // 'auto' on every clip can exhaust Android/WebView hardware decoders.
      rt.el.preload='metadata';
      rt.el.dataset.ledoaMainVideo='1';
      runtimeWatch.set(rt.el,{lastTime:-1,lastProgress:performance.now(),lastRetry:0});
    }
    return rt;
  };

  ensureRuntime=function(c){
    if(c?.type==='video') return createVideoRuntime(c);
    return createImageRuntime(c);
  };

  function ownersByRuntime(){
    const m=new Map();
    state.clips.forEach((c,i)=>{
      if(c.type!=='video'||!c.rt?.el) return;
      const rt=c.rt;
      if(!m.has(rt)) m.set(rt,[]);
      m.get(rt).push(i);
    });
    return m;
  }

  function safeSeek(v,target){
    if(!Number.isFinite(target)||v.seeking) return;
    try{v.currentTime=target;}catch{}
  }

  function ensurePlaying(v,target,rt){
    const now=performance.now();
    let w=runtimeWatch.get(v);
    if(!w){w={lastTime:-1,lastProgress:now,lastRetry:0};runtimeWatch.set(v,w);}
    const ct=Number.isFinite(v.currentTime)?v.currentTime:0;
    if(w.lastTime<0 || Math.abs(ct-w.lastTime)>.012){
      w.lastTime=ct;w.lastProgress=now;
    }

    const start=()=>{
      try{
        const q=v.play();
        q?.catch?.(()=>{});
      }catch{}
    };

    if(v.paused) start();

    // Timeline moving + video clock frozen means WebView lost/stalled its decoder.
    // Retry at a controlled cadence instead of seeking on every animation frame.
    if(now-w.lastProgress>420 && Math.abs(ct-target)>.08 && now-w.lastRetry>360){
      w.lastRetry=now;
      safeSeek(v,target);
      rt.synced=true;
      const resume=()=>start();
      if(v.readyState>=2) resume();
      else v.addEventListener('canplay',resume,{once:true});
      w.lastProgress=now;
    }
  }

  prepareMedia=function(g,forPlay=false){
    const ai=activeInfo(g);
    if(!ai) return;

    // A clip split/duplicate made in the same session used to share one <video>.
    // During a transition those two clips need independent source clocks.
    if(ai.trans && ai.trans.prev!==ai.trans.cur && ai.trans.prev?.rt && ai.trans.prev.rt===ai.trans.cur?.rt){
      ai.trans.cur.rt=null;
    }

    const items=ai.trans?[ai.trans.prev,ai.trans.cur]:[ai.cur];
    const activeRuntimes=new Set();

    for(const c of items){
      if(!c||c.type!=='video'||c.placeholder) continue;
      const rt=ensureRuntime(c),v=rt.el;
      activeRuntimes.add(rt);
      const target=mediaLocalSourceTime(c,g);
      v.playbackRate=Math.max(.5,Math.min(2,+c.speed||1));
      const ct=Number.isFinite(v.currentTime)?v.currentTime:0;
      const drift=Math.abs(ct-target);

      if(!forPlay){
        try{v.pause();}catch{}
        if(drift>.04) safeSeek(v,target);
        rt.synced=true;
        const w=runtimeWatch.get(v);if(w){w.lastTime=target;w.lastProgress=performance.now();}
      }else{
        // Exact seek only when entering a clip or after a very large discontinuity.
        // Small drift is left to the media clock; constant seek calls freeze Android WebView.
        if(!rt.synced || drift>1.15){safeSeek(v,target);rt.synced=true;}
        ensurePlaying(v,target,rt);
      }
    }

    const ownerMap=ownersByRuntime();
    for(const [rt,indices] of ownerMap){
      const v=rt.el;
      if(activeRuntimes.has(rt)) continue;
      try{v.pause();}catch{}
      rt.synced=false;

      // Keep only the immediate neighbours ready. Far clips release their decoder
      // but keep the blob URL, so seeking back can restore them on demand.
      const near=indices.some(i=>Math.abs(i-ai.idx)<=1);
      if(!near && v.getAttribute('src')){
        try{
          v.removeAttribute('src');
          v.load();
          v.dataset.ledoaUnloaded='1';
          rt.preloaded=false;
        }catch{}
      }
    }

    // Metadata-preload just the next clip after far decoders have been released.
    const next=state.clips[ai.idx+1];
    if(next?.type==='video'&&!next.placeholder){
      const nr=ensureRuntime(next);
      if(nr?.el && !nr.el.getAttribute('src')) attachSourceIfNeeded(next,nr);
    }

    applyVideoAudioGains(ai);
  };

  // New splits/duplicates must not share the same HTMLVideoElement runtime.
  splitSelected=function(){
    const c=selectedClip();if(!c)return;
    const idx=state.clips.indexOf(c),local=state.currentTime-c.start;
    if(local<=.15||local>=c.effDuration-.15)return toast('클립 중간 위치에서 분할해 주세요.');
    pushHistory();
    if(c.type==='video'){
      const src=c.trimStart+local*c.speed;
      const a={...c,id:uid('clip'),name:`${c.name} A`,trimEnd:src,rt:null};
      const b={...c,id:uid('clip'),name:`${c.name} B`,trimStart:src,rt:null};
      state.clips.splice(idx,1,a,b);state.selectedClipId=b.id;
    }else{
      const a={...c,id:uid('clip'),name:`${c.name} A`,duration:local};
      const b={...c,id:uid('clip'),name:`${c.name} B`,duration:c.effDuration-local};
      state.clips.splice(idx,1,a,b);state.selectedClipId=b.id;
    }
    recalc();renderAll();
  };

  duplicateSelected=function(){
    const c=selectedClip();if(!c)return;
    pushHistory();
    const idx=state.clips.indexOf(c),copy={...c,id:uid('clip'),name:`${c.name} 복사`,rt:c.type==='video'?null:c.rt};
    state.clips.splice(idx+1,0,copy);state.selectedClipId=copy.id;recalc();renderAll();
  };

  // Reclassify already-created shared runtimes after a project is open. We do not
  // alter project/autosave data; only in-memory playback objects are separated lazily.
  try{
    const seen=new Map();
    state.clips.forEach(c=>{
      if(c.type!=='video'||!c.rt?.el)return;
      if(seen.has(c.rt)) c.rt=null; else seen.set(c.rt,c.id);
    });
  }catch{}
})();


'use strict';
// v0.5.11: remove black flashes between clips on Android/WebView.
// The v0.5.10 decoder-limit fix is kept. This layer primes only the immediate
// next clip and holds the last valid video frame until the incoming clip has
// actually decoded a drawable frame. Autosave/project/export logic is untouched.
// v0.6.2: confirm decoded frames after loading/seeking, and retain clean per-clip layers.
const v062VideoFrames=new WeakMap();
let v062ClearFrameCaches=()=>{};
function v062ObserveVideoFrame(v){
  let w=v062VideoFrames.get(v);if(w)return w;
  w={ready:false,epoch:0,presentedEpoch:-1,id:0,mediaTime:NaN,serial:0,target:null,rvfc:typeof v.requestVideoFrameCallback==='function'};
  if(!w.rvfc)w.ready=v.readyState>=2&&!v.seeking;
  v062VideoFrames.set(v,w);
  const arm=()=>{
    if(!w.rvfc||w.id||!v.getAttribute('src'))return;
    const epoch=w.epoch;
    try{w.id=v.requestVideoFrameCallback((now,m)=>{
      w.id=0;
      if(epoch===w.epoch&&Math.abs((m.mediaTime??v.currentTime)-(v.currentTime||0))<.25){
        // Chromium may present the new seek frame before it emits seeked/loadeddata.
        // Retain that confirmation and make it drawable once the media state catches up.
        w.presentedEpoch=epoch;w.mediaTime=m.mediaTime??v.currentTime;w.serial++;
        w.ready=!v.seeking&&v.readyState>=2;
      }
      if(!v.paused)arm();
    });}catch{w.rvfc=false;w.ready=v.readyState>=2&&!v.seeking;}
  };
  w.arm=arm;
  const invalidate=()=>v062InvalidateVideoFrame(v,Number.isFinite(v.currentTime)?v.currentTime:null);
  v.addEventListener('seeking',invalidate);
  v.addEventListener('emptied',()=>v062InvalidateVideoFrame(v,null,false));
  v.addEventListener('loadstart',invalidate);
  const decoded=()=>{
    if(w.rvfc){
      if(!v.seeking&&v.readyState>=2&&w.presentedEpoch===w.epoch&&Math.abs(w.mediaTime-(v.currentTime||0))<.25)w.ready=true;
      arm();
    }
    else requestAnimationFrame(()=>{if(v.readyState>=2&&!v.seeking){w.ready=true;w.mediaTime=v.currentTime;w.serial++;}});
  };
  for(const name of ['loadeddata','seeked','canplay'])v.addEventListener(name,decoded);
  v.addEventListener('play',arm);
  arm();return w;
}
function v062InvalidateVideoFrame(v,target=null,rearm=true){
  const w=v062ObserveVideoFrame(v);w.ready=false;w.epoch++;w.presentedEpoch=-1;w.target=target;w.mediaTime=NaN;
  if(w.id){try{v.cancelVideoFrameCallback?.(w.id);}catch{}w.id=0;}
  if(rearm)w.arm();
}
function v062VideoFrameReady(v){
  if(!v||v.seeking||v.readyState<2||!v.videoWidth||!v.videoHeight)return false;
  const w=v062ObserveVideoFrame(v);
  return w.ready&&(!w.rvfc||Math.abs((v.currentTime||0)-w.mediaTime)<.25);
}
async function v062WaitVideoFrame(v,target,timeout=1000){
  const start=performance.now();
  while(performance.now()-start<timeout){
    if(v062VideoFrameReady(v)&&Math.abs((v.currentTime||0)-target)<.12)return true;
    await new Promise(r=>setTimeout(r,16));
  }
  return false;
}
(function installClipHandoffNoBlackV0511(){
  const originalCreateVideoRuntime=createVideoRuntime;
  createVideoRuntime=function(c){const rt=originalCreateVideoRuntime(c);if(rt?.el)v062ObserveVideoFrame(rt.el);return rt;};
  const baseFrame=document.createElement('canvas'),baseCtx=baseFrame.getContext('2d',{alpha:false});
  const staging=document.createElement('canvas'),stagingCtx=staging.getContext('2d',{alpha:false});
  const layers=new Map();let haveBase=false;
  v062ClearFrameCaches=()=>{haveBase=false;layers.clear();};
  function resizeBase(){
    if(baseFrame.width!==canvas.width||baseFrame.height!==canvas.height){
      baseFrame.width=staging.width=canvas.width;baseFrame.height=staging.height=canvas.height;v062ClearFrameCaches();
    }
  }
  function layerFor(c){
    if(!c||c.placeholder)return null;
    resizeBase();
    const key=`${c.id}:${c.url}:${c.trimStart}:${c.trimEnd}`;
    let layer=layers.get(key);
    const rt=ensureRuntime(c),src=c.type==='video'?rt?.el:rt?.img;
    if(!src)return layer?.valid?layer.canvas:null;
    const ready=c.type==='video'?v062VideoFrameReady(src):!!(src.complete&&(src.naturalWidth||src.width));
    if(!ready)return layer?.valid?layer.canvas:null;
    const serial=c.type==='video'?(v062ObserveVideoFrame(src).rvfc?v062ObserveVideoFrame(src).serial:src.currentTime):'image';
    if(layer?.valid&&layer.serial===serial)return layer.canvas;
    try{
      stagingCtx.fillStyle='#000';stagingCtx.fillRect(0,0,canvas.width,canvas.height);
      drawContained(stagingCtx,src,1);
      if(c.type==='video'&&!v062VideoFrameReady(src))return layer?.valid?layer.canvas:null;
      if(!layer){
        const buffer=document.createElement('canvas');buffer.width=canvas.width;buffer.height=canvas.height;
        layer={canvas:buffer,ctx:buffer.getContext('2d',{alpha:false}),valid:false,serial:null};
      }
      layer.ctx.drawImage(staging,0,0,canvas.width,canvas.height);layer.valid=true;layer.serial=serial;
      layers.delete(key);layers.set(key,layer);
      // Only the outgoing and incoming clip snapshots stay resident.
      while(layers.size>2)layers.delete(layers.keys().next().value);
    }catch{return layer?.valid?layer.canvas:null;}
    return layer.canvas;
  }
  drawClip=function(c,c2,alpha=1,dx=0,dy=0,scale=1){
    const layer=layerFor(c);if(!layer)return false;drawContained(c2,layer,alpha,dx,dy,scale);return true;
  };
  renderFrame=function(g,forPlay=false){
    g=clamp(g,0,totalDuration()||0);state.currentTime=g;resizeBase();
    if(!state.clips.length)v062ClearFrameCaches();
    prepareMedia(g,forPlay);
    const ai=activeInfo(g);
    ctx.fillStyle='#000';ctx.fillRect(0,0,canvas.width,canvas.height);
    let baseDrawn=false;
    if(ai?.trans){
      const prevOK=!!layerFor(ai.trans.prev),curOK=!!layerFor(ai.trans.cur);
      if(prevOK&&curOK){drawTransition(ctx,ai.trans);baseDrawn=true;}
      else if(curOK)baseDrawn=drawClip(ai.trans.cur,ctx,1);
      else if(prevOK)baseDrawn=drawClip(ai.trans.prev,ctx,1);
    }else if(ai?.cur)baseDrawn=drawClip(ai.cur,ctx,1);
    if(baseDrawn){
      try{baseCtx.drawImage(canvas,0,0,canvas.width,canvas.height);haveBase=true;}catch{}
    }else if(haveBase){
      try{ctx.drawImage(baseFrame,0,0,canvas.width,canvas.height);}catch{}
    }
    // Cached layers contain no text or overlays; their timing stays live.
    if(state.clips.length){drawOverlays(ctx,g,forPlay);drawTexts(ctx,g);}
    updateTime();updatePlayhead();
  };
  document.addEventListener('loadeddata',e=>{
    if(!state.playing&&!state.exporting&&e.target?.dataset?.ledoaMainVideo==='1'){
      requestAnimationFrame(()=>{try{renderFrame(state.currentTime,false);}catch{}});
    }
  },true);
})();

'use strict';
// v0.5.12: multiple BGM clips on the project timeline.
// Each BGM has independent project start/end time and volume. Existing video,
// transition, autosave and overlay playback code is left intact; only the music
// layer is replaced here.
(function installMultiBgmTimelineV0512(){
  state.musicTracks=Array.isArray(state.musicTracks)?state.musicTracks:[];
  state.selectedMusicId=state.selectedMusicId||null;
  state.musicDefaultVolume=state.musicDefaultVolume==null?(state.musicVolume==null?.2:state.musicVolume):state.musicDefaultVolume;

  function bgmSelected(){return state.musicTracks.find(x=>x.id===state.selectedMusicId)||null;}
  function bgmIsMp4(file){return (file?.type||'').toLowerCase()==='video/mp4'||/\.(mp4|m4v)$/i.test(file?.name||'');}
  function bgmMakeMedia(track){
    if(track.audio)return track.audio;
    // v0.5.18: always use HTMLAudioElement, even when the BGM source file is MP4.
    // Android WebView may allocate a hardware VIDEO decoder for a hidden <video>;
    // when that BGM begins it can starve the actual timeline video and freeze the picture.
    // <audio> demuxes the MP4 AAC track without consuming a video decoder.
    const media=new Audio();
    media.src=track.url;media.preload='auto';media.loop=false;media.volume=track.volume??state.musicDefaultVolume;
    media.setAttribute('aria-hidden','true');
    media.addEventListener('error',()=>toast(track.isMp4?'이 MP4의 오디오를 재생할 수 없습니다.':'이 음악 파일을 재생할 수 없습니다.'),{once:true});
    track.audio=media;
    return media;
  }
  function bgmDisconnect(track){
    if(track?.node){try{track.node.source.disconnect();}catch{}try{track.node.gain.disconnect();}catch{}track.node=null;}
  }
  function bgmRelease(track){
    if(!track)return;
    bgmDisconnect(track);
    if(track.audio){try{track.audio.pause();track.audio.removeAttribute('src');track.audio.load();if(track.audio.tagName==='VIDEO')track.audio.remove();}catch{}track.audio=null;}
    if(track.url){try{URL.revokeObjectURL(track.url);}catch{}track.url=null;}
  }
  function bgmConnect(track){
    if(!track||!state.audioCtx||!state.audioDest)return;
    const media=bgmMakeMedia(track);if(track.node)return;
    try{
      media.muted=false;media.defaultMuted=false;media.volume=1;
      const source=state.audioCtx.createMediaElementSource(media),gain=state.audioCtx.createGain();
      source.connect(gain);gain.connect(state.audioDest);gain.connect(state.audioCtx.destination);
      gain.gain.value=track.volume??state.musicDefaultVolume;
      track.node={source,gain};
    }catch(e){console.warn('BGM audio graph connect failed',e);}
  }
  function bgmCreateTrack(file,opts={}){
    const start=clamp(opts.start==null?state.currentTime:+opts.start,0,totalDuration()||9999);
    const id=opts.id||uid('bgm');
    const track={id,file,url:URL.createObjectURL(file),name:opts.name||file.name||'BGM',type:file.type||opts.type||'',isMp4:bgmIsMp4(file),start,end:opts.end==null?Math.max(start+.1,totalDuration()||start+30):+opts.end,volume:opts.volume==null?state.musicDefaultVolume:+opts.volume,audio:null,node:null,_manualEnd:!!opts.manualEnd};
    const media=bgmMakeMedia(track);
    const setNaturalEnd=()=>{
      if(track._manualEnd)return;
      const d=media.duration;
      if(Number.isFinite(d)&&d>0){track.end=Math.max(track.start+.1,Math.min(totalDuration()||track.start+d,track.start+d));renderMusicList();if(track.id===state.selectedMusicId)syncMusicEditor();}
    };
    if(media.readyState>=1)setNaturalEnd();else media.addEventListener('loadedmetadata',setNaturalEnd,{once:true});
    if(state.audioCtx)bgmConnect(track);
    return track;
  }
  function bgmAddFiles(files){
    const arr=[...files].filter(Boolean);if(!arr.length)return;
    pushHistory();
    let cursor=state.currentTime,added=0;
    for(const file of arr){
      const ok=(file.type||'').startsWith('audio/')||bgmIsMp4(file)||/\.(mp3|m4a|aac|wav|ogg|flac)$/i.test(file.name||'');if(!ok)continue;
      const tr=bgmCreateTrack(file,{start:cursor});state.musicTracks.push(tr);state.selectedMusicId=tr.id;added++;
    }
    state.music=null;state.musicAudio=null;state.musicNode=null;
    renderMusicList();syncMusicEditor();v038ScheduleAutosave?.(100);
    toast(`${added}개 BGM을 추가했습니다.`);
  }
  function bgmDeleteSelected(){
    const tr=bgmSelected();if(!tr)return toast('삭제할 BGM을 선택해 주세요.');
    pushHistory();bgmRelease(tr);state.musicTracks=state.musicTracks.filter(x=>x.id!==tr.id);state.selectedMusicId=state.musicTracks[0]?.id||null;renderMusicList();syncMusicEditor();v038ScheduleAutosave?.(100);renderFrame(state.currentTime,false);
  }
  function bgmSelect(id){state.selectedMusicId=id;renderMusicList();syncMusicEditor();}
  function syncMusicEditor(){
    const tr=bgmSelected(),status=$('musicStatus'),st=$('musicStart'),en=$('musicEnd'),vol=$('musicVolume'),vl=$('musicVolumeLabel');
    if(!tr){if(status)status.textContent=state.musicTracks.length?`${state.musicTracks.length}개 BGM · 하나를 선택해 주세요.`:'선택된 BGM 없음';if(st)st.value=state.currentTime.toFixed(1);if(en)en.value=Math.min(totalDuration()||state.currentTime+3,state.currentTime+3).toFixed(1);if(vol)vol.value=Math.round(state.musicDefaultVolume*100);if(vl)vl.textContent=`${Math.round(state.musicDefaultVolume*100)}%`;return;}
    if(status)status.textContent=`선택: ${tr.name}`;
    if(st)st.value=(+tr.start||0).toFixed(1);if(en)en.value=(+tr.end||(+tr.start||0)+.1).toFixed(1);
    if(vol)vol.value=Math.round((tr.volume??state.musicDefaultVolume)*100);if(vl)vl.textContent=`${Math.round((tr.volume??state.musicDefaultVolume)*100)}%`;
  }
  function saveMusicTiming(){
    const tr=bgmSelected();if(!tr)return toast('시간을 수정할 BGM을 선택해 주세요.');
    const start=clamp(+$('musicStart').value||0,0,totalDuration()||9999),rawEnd=+$('musicEnd').value||start+.1,end=Math.max(start+.1,Math.min(totalDuration()||rawEnd,rawEnd));
    pushHistory();tr.start=start;tr.end=end;tr._manualEnd=true;
    try{tr.audio?.pause();}catch{}renderMusicList();syncMusicEditor();syncMusic(false);v038ScheduleAutosave?.(100);toast(`BGM ${start.toFixed(1)}초 ~ ${end.toFixed(1)}초로 저장했습니다.`);
  }
  function renderMusicList(){
    const host=$('musicList');if(!host)return;host.innerHTML='';
    state.musicTracks.slice().sort((a,b)=>a.start-b.start).forEach(tr=>{
      const row=document.createElement('div');row.className=`v0512-music-item${tr.id===state.selectedMusicId?' selected-item':''}`;
      row.innerHTML=`<b>${escapeHtml(tr.name||'BGM')}</b><span>${(+tr.start||0).toFixed(1)} ~ ${(+tr.end||0).toFixed(1)}초</span><div class="v0512-music-price">♫ 시작 ${fmt(tr.start)} · 끝 ${fmt(tr.end)} · 볼륨 ${Math.round((tr.volume??state.musicDefaultVolume)*100)}%</div>`;
      row.onclick=()=>bgmSelect(tr.id);host.appendChild(row);
    });
  }

  // Compatibility: old code calls loadMusic/removeMusic. From v0.5.12 they mean add/delete selected BGM.
  loadMusic=function(file){if(file)bgmAddFiles([file]);};
  removeMusic=bgmDeleteSelected;
  clearMusicMedia=function(){
    for(const tr of state.musicTracks||[])bgmRelease(tr);
    state.musicTracks=[];state.selectedMusicId=null;
    if(state.musicUrl){try{URL.revokeObjectURL(state.musicUrl);}catch{}}
    state.music=null;state.musicUrl=null;state.musicAudio=null;state.musicNode=null;
  };
  connectMusicAudio=function(track){if(track)return bgmConnect(track);for(const tr of state.musicTracks||[])bgmConnect(tr);};

  const oldEnsureAudioGraph=ensureAudioGraph;
  ensureAudioGraph=async function(){const ac=await oldEnsureAudioGraph();if(ac)for(const tr of state.musicTracks||[])bgmConnect(tr);return ac;};

  syncMusic=function(playing){
    const g=state.currentTime;
    for(const tr of state.musicTracks||[]){
      const a=bgmMakeMedia(tr);if(state.audioCtx)bgmConnect(tr);
      const active=g>=tr.start-.015 && g<tr.end-.005;
      const d=Number.isFinite(a.duration)&&a.duration>0?a.duration:Infinity;
      const local=Math.max(0,g-tr.start);
      const hasAudio=local<d-.01;
      const gain=tr.volume??state.musicDefaultVolume;
      if(!active||!hasAudio){if(tr.node)tr.node.gain.gain.value=0;try{a.pause();}catch{}continue;}
      if(tr.node)tr.node.gain.gain.value=gain;else a.volume=gain;
      const target=Math.max(0,Math.min(local,Number.isFinite(d)?Math.max(0,d-.02):local));
      if(Math.abs((a.currentTime||0)-target)>.35){try{a.currentTime=target;}catch{}}
      if(playing){const p=a.play();p?.catch?.(()=>{});}else try{a.pause();}catch{}
    }
  };

  const oldStopMedia=stopMedia;
  stopMedia=function(){oldStopMedia();for(const tr of state.musicTracks||[])try{tr.audio?.pause();}catch{}};
  const oldMuteAllForCover=muteAllForCover;
  muteAllForCover=function(){oldMuteAllForCover();for(const tr of state.musicTracks||[]){if(tr.node)tr.node.gain.gain.value=0;try{tr.audio?.pause();}catch{}}};

  // Music sheet behavior.
  if($('musicInput'))$('musicInput').onchange=e=>{const fs=e.target.files;if(fs?.length)bgmAddFiles(fs);e.target.value='';};
  if($('saveMusicTimeBtn'))$('saveMusicTimeBtn').onclick=saveMusicTiming;
  if($('removeMusicBtn'))$('removeMusicBtn').onclick=bgmDeleteSelected;
  if($('musicVolume'))$('musicVolume').oninput=e=>{const n=clamp((+e.target.value||0)/100,0,1),tr=bgmSelected();if(tr){tr.volume=n;if(tr.node)tr.node.gain.gain.value=n;else if(tr.audio)tr.audio.volume=n;}else state.musicDefaultVolume=n;state.musicVolume=n;$('musicVolumeLabel').textContent=`${e.target.value}%`;renderMusicList();v038ScheduleAutosave?.(350);};
  if($('musicVolume'))$('musicVolume').onchange=()=>{pushHistory();v038ScheduleAutosave?.(100);};
  for(const id of ['musicStart','musicEnd'])if($(id))$(id).addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();saveMusicTiming();}});

  const oldOpenSheet=openSheet;
  openSheet=function(id){oldOpenSheet(id);if(id==='musicSheet'){renderMusicList();syncMusicEditor();}};

  // Durable autosave with multiple BGM media blobs. Compatible with old single-BGM projects.
  v038SaveProject=async function(silent=true){
    if(v038Saving||v038Restoring||state.exporting||state.importing)return false;v038Saving=true;
    try{
      const db=await v038Db();const tx=db.transaction(['project',V038_MEDIA],'readwrite'),ps=tx.objectStore('project'),ms=tx.objectStore(V038_MEDIA),keep=new Set(),writes=new Map();
      const clips=[];for(const c of state.clips){const m=v038CloneMeta(c),key=v038MediaKey('clip',c.id);m.mediaKey=key;clips.push(m);if(c.file){keep.add(key);v066PutMedia(ms,key,{blob:c.file,name:c.name||c.file.name||'media',type:c.file.type||''},writes);}}
      const overlays=[];for(const o of state.overlays){const m=v038CloneMeta(o);if(['image','video'].includes(o.type)){const key=v038MediaKey('overlay',o.id);m.mediaKey=key;if(o.file){keep.add(key);v066PutMedia(ms,key,{blob:o.file,name:o.name||o.file.name||'overlay',type:o.file.type||''},writes);}}overlays.push(m);}
      const musicTracks=[];for(const tr of state.musicTracks||[]){const key=v038MediaKey('music',tr.id);const m={id:tr.id,name:tr.name,type:tr.type,isMp4:!!tr.isMp4,start:+tr.start||0,end:+tr.end||0,volume:tr.volume??state.musicDefaultVolume,manualEnd:!!tr._manualEnd,mediaKey:key};musicTracks.push(m);if(tr.file){keep.add(key);v066PutMedia(ms,key,{blob:tr.file,name:tr.name||tr.file.name||'BGM',type:tr.file.type||''},writes);}}
      const project={version:512,savedAt:Date.now(),ratio:state.ratio,currentTime:state.currentTime,zoom:state.zoom,clips,texts:state.texts.map(v=>({...v})),selectedClipId:state.selectedClipId,selectedTextId:state.selectedTextId,overlays,selectedOverlayId:state.selectedOverlayId,shortsStyle:{...state.shortsStyle},coverDataUrl:state.coverDataUrl||null,coverHold:state.coverHold,musicTracks,selectedMusicId:state.selectedMusicId,musicVolume:state.musicDefaultVolume};
      ps.put(project,V038_PROJECT);const keys=await v038Req(ms.getAllKeys());keys.forEach(k=>{if(!keep.has(k))ms.delete(k);});
      await new Promise((resolve,reject)=>{tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error);});
      for(const [key,file] of writes)v066SavedFiles.set(key,file);for(const key of v066SavedFiles.keys())if(!keep.has(key))v066SavedFiles.delete(key);db.close();v038LastSave=Date.now();if(!silent)toast('현재 작업을 자동 저장했습니다.');return true;
    }catch(e){console.warn('LEDOA autosave failed',e);if(!silent)toast('자동 저장에 실패했습니다.');return false;}finally{v038Saving=false;}
  };

  v038RestoreProject=async function(){
    if(v038Restoring)return;v038Restoring=true;
    try{
      const db=await v038Db(),tx=db.transaction(['project',V038_MEDIA],'readonly'),p=await v038Req(tx.objectStore('project').get(V038_PROJECT));if(!p)return;
      pause();stopMedia();state.clips.forEach(c=>{if(c.url)try{URL.revokeObjectURL(c.url)}catch{}});state.overlays.forEach(o=>{if(o.url)try{URL.revokeObjectURL(o.url)}catch{}});clearMusicMedia();
      const ms=tx.objectStore(V038_MEDIA),clips=[];for(const m of p.clips||[]){const c={...m,file:null,url:null,rt:null};delete c.mediaKey;const rec=m.mediaKey?await v038Req(ms.get(m.mediaKey)):null;if(rec?.blob){const f=new File([rec.blob],rec.name||c.name||'media',{type:rec.type||rec.blob.type||''});c.file=f;c.url=URL.createObjectURL(f);v066SavedFiles.set(m.mediaKey,f);}clips.push(c);}
      const overlays=[];for(const m of p.overlays||[]){const o={...m,file:null,url:null,rt:null};delete o.mediaKey;const rec=m.mediaKey?await v038Req(ms.get(m.mediaKey)):null;if(rec?.blob){const f=new File([rec.blob],rec.name||o.name||'overlay',{type:rec.type||rec.blob.type||''});o.file=f;o.url=URL.createObjectURL(f);v066SavedFiles.set(m.mediaKey,f);if(['image','video'].includes(o.type))overlayRuntime(o);}overlays.push(o);}
      state.clips=clips;state.texts=(p.texts||[]).map(t=>({bgOpacity:t.bgOpacity==null?1:t.bgOpacity,...t}));state.overlays=overlays;state.selectedClipId=p.selectedClipId||clips[0]?.id||null;state.selectedTextId=p.selectedTextId||null;state.selectedOverlayId=p.selectedOverlayId||null;state.shortsStyle=p.shortsStyle||state.shortsStyle;state.coverDataUrl=p.coverDataUrl||null;state.coverHold=+p.coverHold||.2;state.musicDefaultVolume=p.musicVolume==null?.2:+p.musicVolume;state.musicVolume=state.musicDefaultVolume;state.zoom=+p.zoom||1;state.currentTime=+p.currentTime||0;hydrateCover();
      const restored=[];
      if(Array.isArray(p.musicTracks)){
        for(const m of p.musicTracks){const rec=m.mediaKey?await v038Req(ms.get(m.mediaKey)):null;if(!rec?.blob)continue;const f=new File([rec.blob],rec.name||m.name||'BGM',{type:rec.type||rec.blob.type||''});const tr=bgmCreateTrack(f,{id:m.id,name:m.name,type:m.type,start:m.start,end:m.end,volume:m.volume,manualEnd:m.manualEnd});v066SavedFiles.set(m.mediaKey,f);restored.push(tr);}
      }else if(p.musicKey){
        // Migration from v0.5.11 and older: old BGM started at project 0 and ran to the end.
        const rec=await v038Req(ms.get(p.musicKey));if(rec?.blob){const f=new File([rec.blob],rec.name||'BGM',{type:rec.type||rec.blob.type||''});restored.push(bgmCreateTrack(f,{start:0,end:totalDuration()||9999,volume:p.musicVolume==null?.2:+p.musicVolume,manualEnd:true}));}
      }
      state.musicTracks=restored;state.selectedMusicId=p.selectedMusicId&&restored.some(x=>x.id===p.selectedMusicId)?p.selectedMusicId:(restored[0]?.id||null);
      setCanvasRatio(p.ratio||'9:16');recalc();renderAll();renderMusicList();syncMusicEditor();seek(clamp(state.currentTime,0,totalDuration()||0));$('emptyViewer').classList.toggle('hidden',!!state.clips.length);toast('이전 작업을 이어서 불러왔습니다.');
    }catch(e){console.warn('LEDOA restore failed',e);}finally{v038Restoring=false;}
  };

  // Ensure reset/new-project logic recognizes and clears multiple BGM tracks.
  const oldResetEditorState=typeof resetEditorState==='function'?resetEditorState:null;
  if(oldResetEditorState)resetEditorState=function(){clearMusicMedia();oldResetEditorState();state.musicTracks=[];state.selectedMusicId=null;state.musicDefaultVolume=.2;renderMusicList();syncMusicEditor();};

  renderMusicList();syncMusicEditor();
})();


'use strict';
// v0.5.13: first-frame warmup + draggable video trim handles.
// Existing autosave/project/BGM/overlay/export structures are intentionally untouched.
(function installFirstFrameAndTimelineTrimV0513(){
  let playGate=false;
  let warmSeq=0;

  function drawableVideo(c){
    if(!c||c.type!=='video'||c.placeholder)return true;
    const rt=ensureRuntime(c),v=rt?.el;
    return !!(v&&v.readyState>=2&&v.videoWidth>0&&v.videoHeight>0);
  }

  function warmCurrentFrame(g,done){
    g=clamp(+g||0,0,totalDuration()||0);
    const ai=activeInfo(g),c=ai?.cur;
    if(!c||c.type!=='video'||c.placeholder){done?.(true);return;}
    const rt=ensureRuntime(c),v=rt?.el;
    if(!v){done?.(false);return;}
    const seq=++warmSeq;
    try{v.preload='auto';v.playsInline=true;v.setAttribute('playsinline','');}catch{}
    const target=mediaLocalSourceTime(c,g);
    let finished=false;
    const cleanup=()=>{for(const ev of ['loadeddata','canplay','seeked'])try{v.removeEventListener(ev,onReady);}catch{}};
    const finish=ok=>{
      if(finished||seq!==warmSeq)return;finished=true;cleanup();
      if(ok){rt.synced=true;try{if(!state.playing)renderFrame(g,false);}catch{}}
      done?.(ok);
    };
    const onReady=()=>{
      if(v.readyState>=2&&v.videoWidth>0&&v.videoHeight>0)finish(true);
    };
    if(drawableVideo(c))return finish(true);
    for(const ev of ['loadeddata','canplay','seeked'])try{v.addEventListener(ev,onReady);}catch{}
    const seek=()=>{
      try{
        if(Math.abs((v.currentTime||0)-target)>.025)v.currentTime=target;
        else onReady();
      }catch{}
    };
    if(v.readyState>=1)seek();
    else{
      try{v.addEventListener('loadedmetadata',seek,{once:true});v.load();}catch{}
    }
    setTimeout(()=>finish(drawableVideo(c)),1200);
  }

  // Any normal rerender/import/restore should proactively decode the visible first frame.
  const baseRenderAll=renderAll;
  renderAll=function(){
    const r=baseRenderAll.apply(this,arguments);
    setTimeout(()=>warmCurrentFrame(state.currentTime),0);
    return r;
  };

  const baseSeek=seek;
  seek=function(t){
    const r=baseSeek.apply(this,arguments);
    if(!state.playing)setTimeout(()=>warmCurrentFrame(state.currentTime),0);
    return r;
  };

  // Do not start the master timeline clock while the first/current video is still black.
  const basePlay=play;
  play=function(){
    if(state.playing||playGate)return;
    if(!state.clips.length)return basePlay.apply(this,arguments);
    const g=state.currentTime>=totalDuration()-.03?0:state.currentTime;
    const ai=activeInfo(g),c=ai?.cur;
    if(!c||c.type!=='video'||drawableVideo(c))return basePlay.apply(this,arguments);
    playGate=true;
    const btn=$('playBtn'),oldLabel=btn?.textContent;
    if(btn)btn.textContent='…';
    state.currentTime=g;updateTime();updatePlayhead();
    warmCurrentFrame(g,()=>{
      playGate=false;
      if(btn)btn.textContent=oldLabel||'▶';
      if(!state.playing)basePlay();
    });
  };

  // Prime once after app boot too. Restore/import wrappers above cover later projects.
  setTimeout(()=>warmCurrentFrame(state.currentTime),120);

  function recalcModelOnly(){
    let t=0;
    state.clips.forEach((c,i)=>{
      c.start=t;c.effDuration=clipDuration(c);c.end=c.start+c.effDuration;t=c.end-overlapAfter(i);
    });
    state.total=Math.max(0,state.clips.length?state.clips[state.clips.length-1].end:0);
    state.currentTime=clamp(state.currentTime,0,state.total||0);
  }

  function refreshTimelineGeometry(){
    const pps=42*state.zoom;
    document.querySelectorAll('#clipTrack .clip-block[data-id]').forEach(b=>{
      const c=state.clips.find(x=>x.id===b.dataset.id);if(!c)return;
      b.style.left=`${c.start*pps}px`;
      b.style.width=`${Math.max(38,c.effDuration*pps)}px`;
      const meta=b.querySelector('.clip-meta');if(meta)meta.textContent=`${c.effDuration.toFixed(1)}초`;
    });
    const total=Math.max(totalDuration(),8),width=Math.max($('timelineScroll').clientWidth-2,total*pps+50);
    $('clipTrack').style.width=`${width}px`;$('ruler').style.width=`${width}px`;
    updateTime();updatePlayhead();
  }

  let trimDrag=null,trimRaf=0;
  function scheduleTrimPreview(){
    cancelAnimationFrame(trimRaf);
    trimRaf=requestAnimationFrame(()=>{
      recalcModelOnly();refreshTimelineGeometry();
      try{renderFrame(state.currentTime,false);}catch{}
    });
  }

  function startTrim(e,c,side,block){
    if(!c||c.type!=='video'||c.placeholder)return;
    e.preventDefault();e.stopPropagation();e.stopImmediatePropagation?.();
    if(state.playing)pause();
    state.selectedClipId=c.id;
    pushHistory();
    const speed=Math.max(.1,+c.speed||1);
    trimDrag={pointerId:e.pointerId,clipId:c.id,side,startX:e.clientX,trimStart:+c.trimStart||0,trimEnd:+c.trimEnd||c.sourceDuration||0,sourceDuration:+c.sourceDuration||(+c.trimEnd||0),speed,block,moved:false};
    block?.classList.add('v0513-trimming');
    try{e.currentTarget.setPointerCapture(e.pointerId);}catch{}
  }

  function moveTrim(e){
    const d=trimDrag;if(!d||e.pointerId!==d.pointerId)return;
    e.preventDefault();e.stopPropagation();
    const c=state.clips.find(x=>x.id===d.clipId);if(!c)return;
    const pps=Math.max(1,42*state.zoom),deltaProject=(e.clientX-d.startX)/pps,deltaSource=deltaProject*d.speed,minSource=Math.max(.08,.15*d.speed);
    if(d.side==='left')c.trimStart=clamp(d.trimStart+deltaSource,0,Math.max(0,d.trimEnd-minSource));
    else c.trimEnd=clamp(d.trimEnd+deltaSource,Math.min(d.sourceDuration,d.trimStart+minSource),d.sourceDuration);
    d.moved=d.moved||Math.abs(e.clientX-d.startX)>2;
    scheduleTrimPreview();
  }

  function endTrim(e){
    const d=trimDrag;if(!d||e.pointerId!==d.pointerId)return;
    e.preventDefault();e.stopPropagation();
    try{e.currentTarget.releasePointerCapture(e.pointerId);}catch{}
    d.block?.classList.remove('v0513-trimming');trimDrag=null;
    cancelAnimationFrame(trimRaf);trimRaf=0;
    recalc();updateClipSheet();renderFrame(state.currentTime,false);
    v038ScheduleAutosave?.(100);
  }

  function addTrimHandles(){
    const pps=42*state.zoom;
    document.querySelectorAll('#clipTrack .clip-block[data-id]').forEach(block=>{
      const c=state.clips.find(x=>x.id===block.dataset.id);
      if(!c||c.type!=='video'||c.placeholder||block.querySelector('.v0513-trim-handle'))return;
      for(const side of ['left','right']){
        const h=document.createElement('span');h.className=`v0513-trim-handle ${side}`;h.dataset.side=side;h.title=side==='left'?'시작부분 줄이기':'끝부분 줄이기';
        h.addEventListener('pointerdown',e=>startTrim(e,c,side,block));
        h.addEventListener('pointermove',moveTrim);h.addEventListener('pointerup',endTrim);h.addEventListener('pointercancel',endTrim);
        h.addEventListener('click',e=>{e.preventDefault();e.stopPropagation();});
        block.appendChild(h);
      }
    });
  }

  const baseRenderTimeline=renderTimeline;
  renderTimeline=function(){
    const r=baseRenderTimeline.apply(this,arguments);
    addTrimHandles();
    return r;
  };

  // Timeline may already be on screen when this patch installs.
  addTrimHandles();
})();


'use strict';
// v0.5.14: restore a reliable play button while keeping v0.5.13 first-frame warmup.
// Fixes the v0.5.13 warmup gate getting stuck on the temporary "…" label.
(function installReliablePlayButtonV0514(){
  let starting=false;
  let startToken=0;

  function setPlayIcon(){
    const b=$('playBtn');
    if(b && !state.playing) b.textContent='▶';
  }

  function currentVideoAt(g){
    const ai=activeInfo(g);
    const c=ai?.cur;
    if(!c || c.type!=='video' || c.placeholder) return null;
    const rt=ensureRuntime(c);
    return {c,rt,v:rt?.el,ai};
  }

  function isDrawable(v){
    return !!(v && v.readyState>=2 && v.videoWidth>0 && v.videoHeight>0);
  }

  function directStart(g){
    if(state.playing) return;
    starting=false;
    state.currentTime=clamp(g,0,totalDuration()||0);
    ensureAudioGraph().catch(()=>{});
    state.playing=true;
    state.playStartPerf=performance.now();
    state.playStartTime=state.currentTime;
    const b=$('playBtn'); if(b)b.textContent='Ⅱ';
    syncMusic(true);
    cancelAnimationFrame(raf);
    raf=requestAnimationFrame(playLoop);
  }

  function warmThenStart(g){
    const info=currentVideoAt(g);
    if(!info || isDrawable(info.v)) return directStart(g);
    const {c,rt,v}=info;
    if(!v) return directStart(g);
    starting=true;
    const token=++startToken;
    setPlayIcon(); // never replace the play icon with an ellipsis
    try{v.preload='auto';v.playsInline=true;v.setAttribute('playsinline','');}catch{}
    const target=mediaLocalSourceTime(c,g);
    let done=false;
    const cleanup=()=>{
      for(const ev of ['loadeddata','canplay','seeked','playing']){
        try{v.removeEventListener(ev,onReady);}catch{}
      }
    };
    const finish=()=>{
      if(done || token!==startToken) return;
      done=true;cleanup();
      directStart(g);
    };
    const onReady=()=>{if(isDrawable(v))finish();};
    for(const ev of ['loadeddata','canplay','seeked','playing']){
      try{v.addEventListener(ev,onReady);}catch{}
    }
    try{
      if(v.readyState>=1){
        if(Math.abs((v.currentTime||0)-target)>.025)v.currentTime=target;
        else onReady();
      }else{
        v.addEventListener('loadedmetadata',()=>{
          try{v.currentTime=target;}catch{}
          onReady();
        },{once:true});
        v.load();
      }
    }catch{}
    // Never leave the app locked if Android fails to emit a readiness event.
    setTimeout(()=>{if(!done && token===startToken)finish();},700);
  }

  // Replace the v0.5.13 play wrapper entirely. This avoids its private playGate
  // getting stranded if another warmup supersedes the pending warmup sequence.
  play=function(){
    if(state.playing) return;
    if(starting) return;
    if(!state.clips.length){setPlayIcon();return toast('먼저 미디어를 넣어주세요.');}
    let g=state.currentTime;
    if(g>=totalDuration()-.03)g=0;
    state.currentTime=g;updateTime();updatePlayhead();
    warmThenStart(g);
  };

  // Ensure pause/cancel always restores the visible button.
  const basePauseV0514=pause;
  pause=function(){
    startToken++;
    starting=false;
    const r=basePauseV0514.apply(this,arguments);
    setPlayIcon();
    return r;
  };

  // Defensive UI repair after restore/import/service-worker refresh.
  setPlayIcon();
  setTimeout(setPlayIcon,250);
  document.addEventListener('visibilitychange',()=>{if(!document.hidden && !state.playing)setPlayIcon();});
})();


'use strict';
// v0.5.15: explicit left/right timeline viewport controls.
// These move only the visible timeline window; they never alter clip times or the playhead.
(function installTimelineSlideButtonsV0515(){
  const sc=$('timelineScroll'), left=$('timelineLeftBtn'), right=$('timelineRightBtn');
  if(!sc||!left||!right)return;

  let repeatTimer=null, repeatDelay=null;
  const maxScroll=()=>Math.max(0,sc.scrollWidth-sc.clientWidth);
  const step=()=>Math.max(90,Math.round(sc.clientWidth*0.42));
  function move(dir, small=false){
    const amount=small?Math.max(36,Math.round(sc.clientWidth*0.12)):step();
    const target=clamp(sc.scrollLeft+dir*amount,0,maxScroll());
    try{sc.scrollTo({left:target,behavior:'smooth'});}catch{sc.scrollLeft=target;}
  }
  function stopRepeat(){
    if(repeatDelay){clearTimeout(repeatDelay);repeatDelay=null;}
    if(repeatTimer){clearInterval(repeatTimer);repeatTimer=null;}
  }
  function bind(btn,dir){
    btn.addEventListener('click',e=>{e.preventDefault();move(dir,false);});
    // Long press = fine, continuous movement for zoomed-in editing.
    btn.addEventListener('pointerdown',e=>{
      if(e.pointerType==='mouse'&&e.button!==0)return;
      stopRepeat();
      repeatDelay=setTimeout(()=>{
        move(dir,true);
        repeatTimer=setInterval(()=>move(dir,true),125);
      },420);
    });
    for(const ev of ['pointerup','pointercancel','pointerleave'])btn.addEventListener(ev,stopRepeat);
  }
  bind(left,-1); bind(right,1);

  function refreshDisabled(){
    const m=maxScroll();
    left.disabled=sc.scrollLeft<=1;
    right.disabled=sc.scrollLeft>=m-1;
  }
  sc.addEventListener('scroll',refreshDisabled,{passive:true});
  window.addEventListener('resize',refreshDisabled,{passive:true});
  const baseRender=renderTimeline;
  renderTimeline=function(){
    const oldLeft=sc.scrollLeft;
    const r=baseRender.apply(this,arguments);
    requestAnimationFrame(()=>{sc.scrollLeft=clamp(oldLeft,0,maxScroll());refreshDisabled();});
    return r;
  };
  setTimeout(refreshDisabled,0);
})();


'use strict';
// v0.5.17: export stability + audio preservation.
// Keep the proven v0.5.15 preview/timeline engine. During export only:
// 1) keep at most active clip(s) + next clip decoded,
// 2) recover a stalled decoder without aborting the whole export,
// 3) keep an always-live silent WebAudio source so Android MediaRecorder cannot
//    silently omit the audio track while real clip/BGM sources are switched.
(function installExportStabilityAudioV0517(){
  const basePrepare=prepareMedia;
  const baseExport=exportVideo;
  const watch=new Map();
  let silentSource=null,silentGain=null,cleanupTimer=null;

  function frameCount(v){
    try{const q=v.getVideoPlaybackQuality?.();if(q&&Number.isFinite(q.totalVideoFrames))return q.totalVideoFrames;}catch{}
    const n=+v.webkitDecodedFrameCount;return Number.isFinite(n)?n:-1;
  }
  function playSafe(v){try{const p=v.play();p?.catch?.(()=>{});}catch{}}
  function attachIfNeeded(c,rt){
    const v=rt?.el;if(!v)return;
    if(v.getAttribute('src'))return;
    try{v.src=c.url;v.preload='auto';v.playsInline=true;v.setAttribute('playsinline','');v.setAttribute('webkit-playsinline','');v.load();delete v.dataset.ledoaUnloaded;}catch{}
  }
  function restartSameElement(c,rt,target){
    const v=rt?.el;if(!v)return;
    try{v.pause();}catch{}
    try{v.removeAttribute('src');v.load();}catch{}
    rt.synced=false;rt.preloaded=false;v.dataset.ledoaUnloaded='1';
    setTimeout(()=>{
      attachIfNeeded(c,rt);
      const go=()=>{
        try{v.currentTime=target;}catch{}
        try{v.playbackRate=Math.max(.5,Math.min(2,+c.speed||1));}catch{}
        playSafe(v);
      };
      if(v.readyState>=1)go(); else try{v.addEventListener('loadedmetadata',go,{once:true});}catch{}
    },30);
  }
  function prime(c){
    if(!c||c.type!=='video'||c.placeholder)return;
    const rt=ensureRuntime(c),v=rt?.el;if(!v)return;
    attachIfNeeded(c,rt);
    try{v.preload='auto';}catch{}
    const lo=+c.trimStart||0,hi=Math.max(lo,(+c.trimEnd||c.sourceDuration||lo+.1)-.02),t=clamp(lo+.008,lo,hi);
    const go=()=>{try{if(!v.seeking&&Math.abs((v.currentTime||0)-t)>.03)v.currentTime=t;}catch{}};
    if(v.readyState>=1)go(); else try{v.addEventListener('loadedmetadata',go,{once:true});v.load();}catch{}
  }
  function exportPrepare(g){
    // First let the known-good v0.5.15 engine perform its normal sync and gains.
    basePrepare(g,true);
    const ai=activeInfo(g);if(!ai)return;
    const active=ai.trans?[ai.trans.prev,ai.trans.cur]:[ai.cur];
    const keep=new Set(active.filter(Boolean));
    const next=state.clips[ai.idx+1];if(next)keep.add(next);

    // Export is where Android is most sensitive to decoder exhaustion. As soon as
    // a clip is no longer active, release it (except one look-ahead clip).
    for(const c of state.clips){
      if(c.type!=='video'||!c.rt?.el||keep.has(c))continue;
      const v=c.rt.el;
      try{v.pause();}catch{}
      c.rt.synced=false;
      if(v.getAttribute('src')){
        try{v.removeAttribute('src');v.load();v.dataset.ledoaUnloaded='1';c.rt.preloaded=false;}catch{}
      }
      watch.delete(c.id);
    }
    prime(next);

    const now=performance.now();
    for(const c of active){
      if(!c||c.type!=='video'||c.placeholder)continue;
      const rt=ensureRuntime(c),v=rt?.el;if(!v)continue;
      attachIfNeeded(c,rt);
      const target=mediaLocalSourceTime(c,g),ct=Number.isFinite(v.currentTime)?v.currentTime:0,fc=frameCount(v);
      let w=watch.get(c.id);
      if(!w){w={ct,fc,lastProgress:now,lastNudge:0,lastRestart:0,restarts:0,lastTarget:target};watch.set(c.id,w);}
      const moved=Math.abs(ct-w.ct)>.012||(fc>=0&&w.fc>=0&&fc>w.fc);
      if(moved){w.lastProgress=now;w.ct=ct;w.fc=fc;w.restarts=0;}
      const targetAdvancing=target>w.lastTarget+.008;w.lastTarget=target;
      const drift=Math.abs(ct-target);
      try{v.playbackRate=Math.max(.5,Math.min(2,+c.speed||1));}catch{}
      if(v.paused)playSafe(v);

      // First recovery: one controlled seek. This is intentionally not done on
      // every animation frame, because repeated seeking is what froze older builds.
      if(targetAdvancing&&now-w.lastProgress>420&&drift>.10&&now-w.lastNudge>420){
        w.lastNudge=now;
        try{if(!v.seeking)v.currentTime=target;}catch{}
        playSafe(v);
      }
      // Second recovery: reopen only the current element after a sustained stall.
      // Same element is kept so any MediaElementAudioSource connection remains valid.
      if(targetAdvancing&&now-w.lastProgress>1100&&drift>.16&&now-w.lastRestart>1500){
        w.lastRestart=now;w.restarts++;
        const st=$('exportStatus');if(st)st.textContent='영상 프레임 복구 중';
        restartSameElement(c,rt,target);
        w.lastProgress=now;
      }
    }
  }

  prepareMedia=function(g,forPlay=false){
    if(state.exporting&&forPlay)return exportPrepare(g);
    return basePrepare(g,forPlay);
  };

  async function ensureLiveAudioTrack(){
    try{
      let ac=await ensureAudioGraph();
      if(!ac)return false;
      if(!state.audioDest)state.audioDest=ac.createMediaStreamDestination();
      // Reconnect all already-instantiated clip runtimes and BGM tracks.
      for(const c of state.clips)try{connectClipAudio(c);}catch{}
      try{connectMusicAudio();}catch{}
      try{if(typeof bgmConnect==='function')for(const tr of (state.musicTracks||[]))bgmConnect(tr);}catch{}
      if(!silentSource){
        silentSource=ac.createConstantSource();silentGain=ac.createGain();silentGain.gain.value=0;
        silentSource.connect(silentGain);silentGain.connect(state.audioDest);silentSource.start();
      }
      if(ac.state==='suspended')try{await ac.resume();}catch{}
      return !!state.audioDest.stream?.getAudioTracks?.().length;
    }catch(e){console.warn('v0517 audio preflight',e);return false;}
  }
  function cleanupAudioKeeper(){
    if(cleanupTimer){clearInterval(cleanupTimer);cleanupTimer=null;}
    try{silentSource?.stop();}catch{}
    try{silentSource?.disconnect();}catch{}
    try{silentGain?.disconnect();}catch{}
    silentSource=null;silentGain=null;
  }

  exportVideo=async function(){
    if(state.exporting)return;
    const st=$('exportStatus');if(st)st.textContent='오디오 확인 중';
    const audioOK=await ensureLiveAudioTrack();
    if(!audioOK){
      if(st)st.textContent='오디오 준비 실패';
      return toast('오디오 트랙을 준비하지 못했습니다. 저장을 시작하지 않습니다.');
    }
    const r=await baseExport.apply(this,arguments);
    // baseExport starts MediaRecorder asynchronously and returns immediately.
    cleanupTimer=setInterval(()=>{
      if(!state.exporting){cleanupAudioKeeper();}
    },500);
    return r;
  };
  const btn=$('startExportBtn');if(btn)btn.onclick=exportVideo;
})();


'use strict';
// v0.5.18: restart-to-zero transport control + export/BGM decoder guard.
(function installV0518BgmFreezeAndRestart(){
  // Existing projects restored after app launch now create BGM as <audio>. Defensive
  // cleanup in case a stale hidden BGM <video> survived a WebView page restore.
  function removeStaleBgmVideos(){
    try{
      document.querySelectorAll('video[aria-hidden="true"]').forEach(v=>{
        if(v.dataset?.ledoaMainVideo==='1')return;
        try{v.pause();v.removeAttribute('src');v.load();v.remove();}catch{}
      });
    }catch{}
  }
  removeStaleBgmVideos();

  const restart=$('restartBtn');
  if(restart){
    restart.onclick=()=>{
      // seek() preserves current play/pause state. If playing, playback continues from 0.
      seek(0);
      const sc=$('timelineScroll');
      if(sc){try{sc.scrollTo({left:0,behavior:'smooth'});}catch{sc.scrollLeft=0;}}
    };
  }

  // Before export, make absolutely sure no hidden MP4-BGM video element is holding
  // a hardware decoder. Audio elements remain connected to the existing WebAudio graph.
  const baseExportV0518=exportVideo;
  exportVideo=async function(){
    removeStaleBgmVideos();
    return baseExportV0518.apply(this,arguments);
  };
  const btn=$('startExportBtn');if(btn)btn.onclick=exportVideo;
})();


'use strict';
// v0.5.19: deterministic export clip handoff for Android WebView.
// Root cause addressed here: export used the normal preview engine, which decoded
// outgoing + current + look-ahead video at clip boundaries. On some Android devices
// the incoming decoder never advanced, so canvas.captureStream kept recording the
// last good frame while audio continued. Export now uses a strict decoder budget,
// resets to project 0 before recording, and has a seek-driven fallback if a video's
// playback clock stalls. Preview/autosave/project data are not changed.
(function installExportClipHandoffV0519(){
  const previewPrepare=prepareMedia;
  const exportWatch=new Map();
  let lastExportKey='';

  function isVideoClip(c){return !!(c&&c.type==='video'&&!c.placeholder);}
  function attached(v){return !!v?.getAttribute?.('src');}
  function attach(c,rt){
    const v=rt?.el;if(!v||!c?.url)return;
    if(attached(v))return;
    try{
      v.src=c.url;v.preload='auto';v.playsInline=true;
      v.setAttribute('playsinline','');v.setAttribute('webkit-playsinline','');
      v.load();delete v.dataset.ledoaUnloaded;rt.preloaded=false;rt.synced=false;
    }catch(e){console.warn('v0519 attach',e);}
  }
  function release(c){
    const rt=c?.rt,v=rt?.el;if(!v)return;
    try{v.pause();}catch{}
    try{if(rt.gain)rt.gain.gain.value=0;}catch{}
    rt.synced=false;
    if(attached(v)){
      try{v.removeAttribute('src');v.load();v.dataset.ledoaUnloaded='1';rt.preloaded=false;}catch{}
    }
    exportWatch.delete(c.id);
  }
  function seekSafe(v,t){
    if(!v||v.seeking||!Number.isFinite(t))return false;
    try{v.currentTime=t;return true;}catch{return false;}
  }
  function startSafe(v,w){
    const now=performance.now();
    if(!v||!v.paused||now-(w.lastPlayTry||0)<280)return;
    w.lastPlayTry=now;
    try{
      const p=v.play();
      if(p?.then)p.then(()=>{w.playRejected=false;}).catch(()=>{w.playRejected=true;});
    }catch{w.playRejected=true;}
  }

  function strictExportPrepare(g,forPlay){
    const ai=activeInfo(g);if(!ai)return;
    // CUT = exactly one video decoder. A real transition may use two, but never a
    // third look-ahead decoder. This is the key difference from preview playback.
    const active=ai.trans?[ai.trans.prev,ai.trans.cur]:[ai.cur];
    const keep=new Set(active.filter(isVideoClip));

    // Free non-active hardware decoders BEFORE starting the incoming clip.
    for(const c of state.clips){if(isVideoClip(c)&&c.rt?.el&&!keep.has(c))release(c);}

    const now=performance.now();
    const key=active.filter(Boolean).map(c=>c.id).join('|');
    const entered=key!==lastExportKey;
    if(entered)lastExportKey=key;

    for(const c of active){
      if(!isVideoClip(c))continue;
      const rt=ensureRuntime(c),v=rt?.el;if(!v)continue;
      attach(c,rt);
      try{v.preload='auto';v.playbackRate=Math.max(.5,Math.min(2,+c.speed||1));}catch{}
      const target=mediaLocalSourceTime(c,g);
      let w=exportWatch.get(c.id);
      if(!w){w={lastCt:NaN,lastProgress:now,lastSeek:0,lastPlayTry:0,playRejected:false,enteredAt:now};exportWatch.set(c.id,w);}
      if(entered)w.enteredAt=now;

      const ct=Number.isFinite(v.currentTime)?v.currentTime:0;
      if(!Number.isFinite(w.lastCt)||Math.abs(ct-w.lastCt)>.010){w.lastCt=ct;w.lastProgress=now;}
      const drift=Math.abs(ct-target);

      // On clip entry, force the exact source point once. If metadata is still loading,
      // queue that seek. Do not repeatedly seek a healthy playing element.
      if(entered||!rt.synced){
        const doEntrySeek=()=>{
          seekSafe(v,target);rt.synced=true;
          if(forPlay)startSafe(v,w);
        };
        if(v.readyState>=1)doEntrySeek();
        else try{v.addEventListener('loadedmetadata',doEntrySeek,{once:true});v.load();}catch{}
      }

      if(forPlay){
        startSafe(v,w);
        const stalled=now-w.lastProgress>360;
        // Fallback that does NOT depend on autoplay/play() succeeding. If Android's
        // media clock stalls, advance by controlled seeks. This may decode at a lower
        // cadence but prevents the rest of the exported movie becoming one still image.
        if((w.playRejected||v.paused||stalled) && drift>.055 && now-w.lastSeek>85){
          w.lastSeek=now;seekSafe(v,target);
          if(!w.playRejected)startSafe(v,w);
        }else if(drift>1.0 && now-w.lastSeek>180){
          w.lastSeek=now;seekSafe(v,target);
        }
      }else{
        try{v.pause();}catch{}
        if(drift>.025)seekSafe(v,target);
      }
    }
    applyVideoAudioGains(ai);
  }

  prepareMedia=function(g,forPlay=false){
    if(state.exporting)return strictExportPrepare(g,forPlay);
    return previewPrepare(g,forPlay);
  };

  // BGM state machine: v0.5.12 called audio.play()/pause() every animation frame.
  // That can flood Android WebView exactly when music begins. Start/stop only when
  // the BGM state changes, and throttle correction seeks.
  const legacySyncMusic=syncMusic;
  syncMusic=function(playing){
    const tracks=state.musicTracks||[];
    if(!tracks.length)return legacySyncMusic(playing);
    const g=state.currentTime,now=performance.now();
    for(const tr of tracks){
      const a=tr.audio;if(!a)continue;
      const active=g>=tr.start-.015&&g<tr.end-.005;
      const d=Number.isFinite(a.duration)&&a.duration>0?a.duration:Infinity;
      const local=Math.max(0,g-tr.start),hasAudio=local<d-.01;
      const shouldPlay=!!(playing&&active&&hasAudio);
      if(tr.node)tr.node.gain.gain.value=(active&&hasAudio)?(tr.volume??state.musicDefaultVolume):0;
      else a.volume=(active&&hasAudio)?(tr.volume??state.musicDefaultVolume):0;

      const target=Math.max(0,Math.min(local,Number.isFinite(d)?Math.max(0,d-.02):local));
      if(active&&hasAudio&&Math.abs((a.currentTime||0)-target)>.45&&now-(tr._v0519LastSeek||0)>450){
        tr._v0519LastSeek=now;try{a.currentTime=target;}catch{}
      }
      if(shouldPlay){
        if(a.paused&&now-(tr._v0519LastPlay||0)>350){
          tr._v0519LastPlay=now;try{const p=a.play();p?.catch?.(()=>{});}catch{}
        }
        tr._v0519WasPlaying=true;
      }else if(tr._v0519WasPlaying||!a.paused){
        try{a.pause();}catch{}tr._v0519WasPlaying=false;
      }
    }
  };

  function resetExportDecodersToZero(){
    try{stopMedia();}catch{}
    state.currentTime=0;updateTime();updatePlayhead();
    lastExportKey='';exportWatch.clear();
    const ai=activeInfo(0),keep=new Set((ai?.trans?[ai.trans.prev,ai.trans.cur]:[ai?.cur]).filter(isVideoClip));
    for(const c of state.clips){
      if(!isVideoClip(c)||!c.rt?.el)continue;
      if(!keep.has(c))release(c);else{c.rt.synced=false;attach(c,c.rt);}
    }
  }
  function waitFirstDrawable(timeout=1600){
    return new Promise(resolve=>{
      const start=performance.now();
      const tick=()=>{
        const ai=activeInfo(0),c=ai?.cur;
        if(!isVideoClip(c))return resolve(true);
        const rt=ensureRuntime(c),v=rt?.el;attach(c,rt);
        const target=mediaLocalSourceTime(c,0);
        if(v&&v.readyState>=2&&v.videoWidth>0&&v.videoHeight>0){seekSafe(v,target);return resolve(true);}
        if(v?.readyState>=1)seekSafe(v,target); else try{v?.load();}catch{}
        if(performance.now()-start>=timeout)return resolve(false);
        setTimeout(tick,35);
      };tick();
    });
  }

  const baseExportV0519=exportVideo;
  exportVideo=async function(){
    if(state.exporting)return;
    // Saving is always the whole project: reset visual/media state to 0 even if the
    // user pressed Save while previewing the middle or end of the timeline.
    resetExportDecodersToZero();
    const st=$('exportStatus');if(st)st.textContent='첫 장면 준비 중';
    await waitFirstDrawable();
    try{renderFrame(0,false);}catch{}
    return baseExportV0519.apply(this,arguments);
  };
  const saveBtn=$('startExportBtn');if(saveBtn)saveBtn.onclick=exportVideo;
})();


'use strict';
// v0.6.0: export engine reset.
// Do not record the preview clock blindly. Android WebView was emitting 80~95 fps
// and sometimes dropping audio. v0.6.0 uses one fixed 30 fps canvas stream, an
// explicit A/V MediaStream, a two-decoder current+next policy, and aborts rather
// than saving a known-frozen movie.
(function installV0600ExportEngineReset(){
  const EXPORT_FPS=30;
  const previewState={key:'',activeIds:new Set()};
  let keeperSource=null, keeperGain=null;

  function isVideo(c){return !!(c&&c.type==='video'&&!c.placeholder);}
  function attach(c){
    if(!isVideo(c))return null;
    const rt=ensureRuntime(c),v=rt?.el;if(!v)return null;try{v.preload='auto';}catch{}
    if(!v.getAttribute('src')){
      try{v062InvalidateVideoFrame(v,null,false);v.src=c.url;v.preload='auto';v.playsInline=true;v.setAttribute('playsinline','');v.setAttribute('webkit-playsinline','');v.load();rt.synced=false;rt.preloaded=false;}catch{}
    }
    if(state.audioCtx)try{connectClipAudio(c);}catch{}
    return {rt,v};
  }
  function park(c,hard=false){
    const rt=c?.rt,v=rt?.el;if(!v)return;
    try{v.pause();}catch{}
    rt.synced=false;
    if(hard&&v.getAttribute('src')){try{v062InvalidateVideoFrame(v,null,false);v.removeAttribute('src');v.load();rt.preloaded=false;}catch{}}
  }
  function safeSeek(v,t){if(!v||!Number.isFinite(t))return false;try{if(!v.seeking&&Math.abs((v.currentTime||0)-t)>.025){v062InvalidateVideoFrame(v,t);v.currentTime=t;}return true;}catch{return false;}}
  function safePlay(v){if(!v||!v.paused)return;try{const p=v.play();p?.catch?.(()=>{});}catch{}}

  function lookAhead(ai){
    const active=ai.trans?[ai.trans.prev,ai.trans.cur]:[ai.cur];
    if(active.filter(isVideo).length>=2)return null;
    const next=state.clips[ai.idx+1];return isVideo(next)?next:null;
  }
  // Keep at most two video decoders. A transition already uses both slots.
  prepareMedia=function(g,forPlay=false){
    v065SeparateRuntimes();
    const ai=activeInfo(g);if(!ai)return;
    const active=ai.trans?[ai.trans.prev,ai.trans.cur]:[ai.cur],next=lookAhead(ai);
    const keep=new Set([...active,next].filter(Boolean));
    const keepRuntimes=new Set([...keep].map(c=>c.rt).filter(Boolean));
    for(const c of state.clips){if(isVideo(c)&&c.rt?.el&&!keep.has(c)&&!keepRuntimes.has(c.rt))park(c,true);}
    const previouslyActive=previewState.activeIds;
    for(const c of active){
      if(!isVideo(c))continue;
      const x=attach(c);if(!x)continue;const {rt,v}=x,target=mediaLocalSourceTime(c,g);
      try{v.playbackRate=Math.max(.5,Math.min(2,+c.speed||1));}catch{}
      const drift=Math.abs((v.currentTime||0)-target),entering=!previouslyActive.has(c.id);
      if(!forPlay){try{v.pause();}catch{};if(drift>.035)safeSeek(v,target);rt.synced=true;}
      else{
        // A changed transition pair must never re-seek the healthy outgoing clip.
        if(!rt.synced||drift>.75||(entering&&drift>.12)){safeSeek(v,target);rt.synced=true;}
        safePlay(v);
      }
    }
    previewState.activeIds=new Set(active.filter(Boolean).map(c=>c.id));
    previewState.key=active.filter(Boolean).map(c=>c.id).join('|');
    if(next){
      const x=attach(next);if(x){const t=+next.trimStart||0;try{x.v.pause();}catch{};
        if(x.v.readyState>=1&&Math.abs((x.v.currentTime||0)-t)>.08)safeSeek(x.v,t);
        v062ObserveVideoFrame(x.v);
      }
    }
    applyVideoAudioGains(ai);
  };

  async function waitEvent(el,names,timeout=1400){
    return new Promise(resolve=>{
      let done=false;const end=v=>{if(done)return;done=true;clearTimeout(to);names.forEach(n=>el?.removeEventListener?.(n,on));resolve(v);};
      const on=()=>end(true);names.forEach(n=>el?.addEventListener?.(n,on,{once:true}));const to=setTimeout(()=>end(false),timeout);
    });
  }
  async function readyClip(c,g,timeout=1700){
    if(!isVideo(c))return true;const x=attach(c);if(!x)return false;const {v,rt}=x,target=mediaLocalSourceTime(c,g);
    if(v.readyState<1){try{v.load();}catch{};await waitEvent(v,['loadedmetadata'],timeout*.45);}
    safeSeek(v,target);
    if(v.readyState<2||v.seeking)await waitEvent(v,['seeked','loadeddata','canplay'],timeout);
    rt.synced=true;
    return v062WaitVideoFrame(v,target,timeout);
  }
  async function prewarmAt(g){
    const ai=activeInfo(g);if(!ai)return false;
    const req=ai.trans?[ai.trans.prev,ai.trans.cur]:[ai.cur];
    for(const c of req)if(!(await readyClip(c,g)))return false;
    const next=lookAhead(ai);if(next)readyClip(next,next.start,900).catch(()=>{});
    return true;
  }

  async function ensureExportAudio(){
    try{
      const ac=await ensureAudioGraph();if(!ac)return null;
      if(!state.audioDest)state.audioDest=ac.createMediaStreamDestination();
      for(const c of state.clips){if(isVideo(c)){attach(c);try{connectClipAudio(c);}catch{}}}
      try{connectMusicAudio();}catch{}
      try{if(typeof bgmConnect==='function')for(const tr of (state.musicTracks||[]))bgmConnect(tr);}catch{}
      if(!keeperSource){
        keeperSource=ac.createConstantSource();keeperGain=ac.createGain();keeperGain.gain.value=0.000001;
        keeperSource.connect(keeperGain);keeperGain.connect(state.audioDest);keeperSource.start();
      }
      if(ac.state==='suspended')await Promise.race([ac.resume().catch(()=>{}),new Promise(r=>setTimeout(r,900))]);
      const at=state.audioDest.stream?.getAudioTracks?.()[0]||null;if(at)at.enabled=true;return at&&at.readyState==='live'?at:null;
    }catch(e){console.warn('v0600 audio prep',e);return null;}
  }

  function makeExportCanvas0600(){
    let c=document.getElementById('ledoaExportCanvasV0600');if(!c){c=document.createElement('canvas');c.id='ledoaExportCanvasV0600';c.style.cssText='position:fixed;left:-9999px;top:0;width:2px;height:2px;opacity:.001;pointer-events:none';document.body.appendChild(c);}c.width=canvas.width;c.height=canvas.height;const x=c.getContext('2d',{alpha:false});x.fillStyle='#000';x.fillRect(0,0,c.width,c.height);return {canvas:c,ctx:x};
  }
  function pickMime0600(){
    const list=['video/mp4;codecs=avc1.42E01E,mp4a.40.2','video/mp4','video/webm;codecs=vp8,opus','video/webm'];
    return list.find(x=>MediaRecorder.isTypeSupported(x))||'';
  }
  function resetAllToZero(){
    try{stopMedia();}catch{};state.currentTime=0;updateTime();updatePlayhead();previewState.key='';previewState.activeIds.clear();v062ClearFrameCaches();
    for(const tr of (state.musicTracks||[])){try{tr.audio?.pause();if(tr.audio)tr.audio.currentTime=0;tr._v0519WasPlaying=false;}catch{}}
    for(const c of state.clips)if(isVideo(c)){c.rt&&(c.rt.synced=false);}
  }

  exportVideo=async function(){
    if(state.exporting)return;if(!state.clips.length)return toast('먼저 미디어를 넣어주세요.');
    if(!window.MediaRecorder||!canvas.captureStream)return toast('이 기기에서는 영상 저장 기능을 지원하지 않습니다.');
    const btn=$('startExportBtn'),status=$('exportStatus'),progress=$('exportProgress');if(btn)btn.disabled=true;if(progress)progress.style.width='0%';
    try{if(typeof v038SaveProject==='function')await Promise.race([v038SaveProject(false),new Promise(r=>setTimeout(r,1000))]);}catch{}
    pause();resetAllToZero();
    if(status)status.textContent='오디오 트랙 확인 중';
    const audioTrack=await ensureExportAudio();
    if(!audioTrack){if(btn)btn.disabled=false;if(status)status.textContent='오디오 준비 실패';return toast('오디오 트랙이 없어 저장을 중단했습니다.');}
    prepareMedia(0,false);
    if(status)status.textContent='첫 영상 준비 중';
    if(!(await prewarmAt(0))){if(btn)btn.disabled=false;if(status)status.textContent='첫 영상 준비 실패';return toast('첫 영상 프레임을 준비하지 못했습니다.');}
    renderFrame(0,false);

    const ex=makeExportCanvas0600();ex.ctx.drawImage(canvas,0,0,ex.canvas.width,ex.canvas.height);
    let vstream;try{vstream=ex.canvas.captureStream(EXPORT_FPS);}catch(e){if(btn)btn.disabled=false;return toast('영상 캡처 스트림을 만들지 못했습니다.');}
    const videoTrack=vstream.getVideoTracks?.()[0];if(!videoTrack){if(btn)btn.disabled=false;return toast('영상 트랙을 만들지 못했습니다.');}
    const out=new MediaStream();out.addTrack(videoTrack);out.addTrack(audioTrack);
    const mime=pickMime0600();let rec;try{rec=new MediaRecorder(out,mime?{mimeType:mime,videoBitsPerSecond:5_000_000}:undefined);}catch(e){try{rec=new MediaRecorder(out);}catch(e2){if(btn)btn.disabled=false;return toast('영상 인코더를 시작하지 못했습니다.');}}
    state.exporting=true;state.recorder=rec;state.chunks=[];
    let finished=false,abortReason='',lastProject=0,lastActive='',stallSince=0,lastVideoCt=NaN,lastVideoProgress=performance.now();
    rec.ondataavailable=e=>{if(e.data?.size)state.chunks.push(e.data);};
    rec.onerror=e=>{abortReason='인코더 오류';console.error('v0600 recorder',e);try{rec.stop();}catch{}};
    rec.onstop=async()=>{
      try{out.getTracks().forEach(t=>{if(t!==audioTrack)try{t.stop();}catch{}});}catch{}
      try{stopMedia();}catch{};state.exporting=false;if(btn)btn.disabled=false;
      if(abortReason){if(status)status.textContent=abortReason;return toast(`${abortReason} · 잘못된 파일은 저장하지 않았습니다.`);}
      try{const type=rec.mimeType||mime||'video/mp4',blob=new Blob(state.chunks,{type});if(!blob.size)throw new Error('empty');const ext=type.includes('mp4')?'mp4':'webm';if(status)status.textContent='파일 저장 중';await saveBlob(blob,`LEDOA-SHORTS-${Date.now()}.${ext}`);if(status)status.textContent=`완료 · ${ext.toUpperCase()}`;toast('영상 저장이 완료되었습니다.');}catch(e){console.error(e);if(status)status.textContent='파일 저장 실패';toast('영상 파일 저장에 실패했습니다.');}
    };

    try{rec.start(500);}catch(e){state.exporting=false;if(btn)btn.disabled=false;return toast('영상 인코더를 시작하지 못했습니다.');}
    const hold=state.coverDataUrl?state.coverHold:0,total=Math.max(.1,totalDuration()),started=performance.now();
    try{if(hold>0)drawCover(ctx);else renderFrame(0,true);ex.ctx.drawImage(canvas,0,0,ex.canvas.width,ex.canvas.height);}catch{}

    const tick=now=>{
      if(!state.exporting||finished)return;
      const elapsed=(now-started)/1000;
      if(elapsed<hold){try{drawCover(ctx);muteAllForCover();}catch{};if(status)status.textContent=`표지 ${Math.max(0,hold-elapsed).toFixed(1)}초`;}
      else{
        const g=Math.min(total,elapsed-hold);state.currentTime=g;
        const ai=activeInfo(g),key=(ai?.trans?[ai.trans.prev,ai.trans.cur]:[ai?.cur]).filter(Boolean).map(c=>c.id).join('|');
        if(key!==lastActive){lastActive=key;stallSince=0;lastVideoCt=NaN;lastVideoProgress=now;const next=ai?lookAhead(ai):null;if(next)readyClip(next,next.start,900).catch(()=>{});}
        try{renderFrame(g,true);syncMusic(true);}catch(e){console.warn('v0600 frame',e);}

        // Freeze watchdog: if the active video clock is not advancing while the
        // project clock is, try one seek; if still dead, abort instead of saving junk.
        const cur=ai?.cur;if(isVideo(cur)&&cur.rt?.el){const v=cur.rt.el,ct=Number.isFinite(v.currentTime)?v.currentTime:0,target=mediaLocalSourceTime(cur,g);
          if(!Number.isFinite(lastVideoCt)||Math.abs(ct-lastVideoCt)>.010){lastVideoCt=ct;lastVideoProgress=now;stallSince=0;}
          else if(g-lastProject>.005&&now-lastVideoProgress>450){if(!stallSince)stallSince=now;if(now-lastVideoProgress>500)safeSeek(v,target);safePlay(v);if(now-stallSince>2200){abortReason='영상 디코더 멈춤 감지';finished=true;try{rec.stop();}catch{};return;}}
        }
        lastProject=g;
        if(status)status.textContent=`${fmt(g)} / ${fmt(total)}`;
        if(g>=total-.001){finished=true;try{rec.stop();}catch{};return;}
      }
      try{ex.ctx.drawImage(canvas,0,0,ex.canvas.width,ex.canvas.height);}catch{}
      if(progress)progress.style.width=`${clamp(elapsed/(hold+total)*100,0,100)}%`;
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  };
  const saveBtn=$('startExportBtn');if(saveBtn)saveBtn.onclick=exportVideo;
})();

// v0.6.1: preserve an explicit zero background opacity in every draft, commit and style-all path.


'use strict';
// v0.6.3: preload both bundled Korean fonts and never record fallback glyphs.
let v063FontPromise=null;
function v063EnsureFonts(){
  if(!document.fonts?.load)return Promise.resolve();
  if(!v063FontPromise){
    v063FontPromise=Promise.all(['Gowun Dodum','Nanum Pen Script'].map(async family=>{
      const faces=await document.fonts.load(`400 52px "${family}"`,'연희동 연희정음 가나다 ABC 123');
      if(!faces.length||faces.some(face=>face.status!=='loaded'))throw new Error(`Bundled font unavailable: ${family}`);
    })).catch(error=>{v063FontPromise=null;throw error;});
  }
  return v063FontPromise;
}
(function installBundledFontsV063(){
  v063EnsureFonts().then(()=>{
    if(!state.playing&&!state.exporting)renderFrame(state.currentTime,false);
  }).catch(error=>{console.warn('LEDOA bundled font load',error);toast('글꼴을 준비하지 못했습니다. 앱을 다시 열어주세요.');});
  const baseExport=exportVideo;let pending=false;
  exportVideo=async function(){
    if(state.exporting||pending)return;
    if(!state.clips.length)return baseExport.apply(this,arguments);
    pending=true;const btn=$('startExportBtn'),status=$('exportStatus');
    if(btn)btn.disabled=true;if(status)status.textContent='글꼴 준비 중';
    try{
      await v063EnsureFonts();
      return await baseExport.apply(this,arguments);
    }catch(error){
      console.warn('LEDOA export font preparation',error);
      if(status)status.textContent='글꼴 준비 실패';toast('글꼴을 준비하지 못했습니다. 앱을 다시 열고 저장해 주세요.');
    }finally{pending=false;if(btn&&!state.exporting)btn.disabled=false;}
  };
  if($('startExportBtn'))$('startExportBtn').onclick=exportVideo;
})();

'use strict';
// v0.6.4: show the stored text size and allow direct numeric editing.
(function installTextSizeInputV064(){
  const slider=$('textSize'),number=$('textSizeNumber');
  if(!slider||!number)return;
  const min=Number(slider.min),max=Number(slider.max);

  function syncNumber(force=false){
    if(force||document.activeElement!==number)number.value=slider.value;
  }
  function applySize(value){
    const next=String(Math.round(clamp(value,min,max)));
    if(slider.value===next)return;
    slider.value=next;
    // Use the existing draft, live caption, rendering and autosave handlers.
    slider.dispatchEvent(new Event('input',{bubbles:true}));
  }
  function commitNumber(){
    const raw=number.value.trim(),value=Number(raw);
    if(raw!==''&&Number.isFinite(value))applySize(value);
    syncNumber(true);
  }

  slider.addEventListener('input',()=>syncNumber());
  slider.addEventListener('change',()=>syncNumber());
  number.addEventListener('input',()=>{
    const raw=number.value.trim(),value=Number(raw);
    // Leave partial input alone so clearing 52 and typing 60 does not apply 24.
    if(raw!==''&&Number.isInteger(value)&&value>=min&&value<=max)applySize(value);
  });
  number.addEventListener('change',commitNumber);
  number.addEventListener('blur',commitNumber);
  number.addEventListener('keydown',event=>{
    if(event.key!=='Enter')return;
    event.preventDefault();commitNumber();number.blur();
  });

  const oldLoad=loadTextToEditor;
  loadTextToEditor=function(t){oldLoad(t);syncNumber(true);};
  const oldReset=resetTextEditor;
  resetTextEditor=function(){oldReset();syncNumber(true);};
  const oldSync=syncShortsStyleUi;
  syncShortsStyleUi=function(){oldSync();syncNumber();};
  const oldOpen=openSheet;
  openSheet=function(id){
    oldOpen(id);
    if(id==='textSheet'){
      const t=selectedText();
      if(t)loadTextToEditor(t);
      else syncNumber(true);
    }
  };
  const oldRenderAll=renderAll;
  renderAll=function(){
    oldRenderAll();
    const t=selectedText();
    if(t)loadTextToEditor(t);
    else syncNumber();
  };
  syncNumber(true);
})();


// v0.6.5: isolated clip transports, resumed audio, media-clock preview.
// Project storage, native file picker, fonts and text rendering stay on v0.6.4.
function v065SeparateRuntimes(){
  const used=new Set();
  for(const c of state.clips){
    if(c.type!=='video'||!c.rt?.el)continue;
    if(used.has(c.rt.el))c.rt=null;
    else used.add(c.rt.el);
  }
}
function v065RetireRuntimes(previous){
  const keep=new Set(state.clips.map(c=>c.rt).filter(Boolean));
  for(const rt of new Set(previous)){
    if(!rt||keep.has(rt)||!rt.el)continue;
    const v=rt.el;
    try{v.pause();}catch{}
    try{rt.source?.disconnect();rt.gain?.disconnect();}catch{}
    try{v062InvalidateVideoFrame(v,null,false);v.removeAttribute('src');v.load();v.remove();}catch{}
  }
}
(function installPlaybackV065(){
  const oldSplit=splitSelected,oldDuplicate=duplicateSelected,oldDelete=deleteSelected,oldUndo=undo;
  function edit(fn,args){
    pause();
    const previous=state.clips.map(c=>c.rt).filter(Boolean);
    const result=fn.apply(null,args);
    v065SeparateRuntimes();v065RetireRuntimes(previous);
    if(typeof v038ScheduleAutosave==='function')v038ScheduleAutosave(100);
    return result;
  }
  splitSelected=function(){return edit(oldSplit,arguments);};
  duplicateSelected=function(){return edit(oldDuplicate,arguments);};
  deleteSelected=function(){return edit(oldDelete,arguments);};
  undo=function(){return edit(oldUndo,arguments);};

  let lastTick=0,watch=null;
  const basePlay=play,basePause=pause;
  play=function(){lastTick=0;watch=null;v065SeparateRuntimes();return basePlay.apply(this,arguments);};
  pause=function(){lastTick=0;watch=null;return basePause.apply(this,arguments);};

  function checkProgress(c,v,now){
    const fw=v062ObserveVideoFrame(v);
    const ct=Number.isFinite(v.currentTime)?v.currentTime:0;
    if(!watch||watch.id!==c.id||watch.el!==v){watch={id:c.id,el:v,ct,serial:fw.serial,progress:now,retry:0};return true;}
    const frameMoved=fw.rvfc?fw.serial!==watch.serial:Math.abs(ct-watch.ct)>.004;
    if(frameMoved&&!v.seeking){watch.progress=now;watch.retry=0;}
    watch.ct=ct;watch.serial=fw.serial;
    const stalledFor=now-watch.progress;
    if(stalledFor>1200&&watch.retry===0){
      watch.retry=1;
      // One re-arm and play retry; never seek on each animation frame.
      try{v062InvalidateVideoFrame(v,ct);const p=v.play();p?.catch?.(()=>{});}catch{}
    }
    if(stalledFor>2500&&watch.retry===1){
      watch.retry=2;
      const target=mediaLocalSourceTime(c,state.currentTime);
      try{
        v.pause();v062InvalidateVideoFrame(v,null,false);v.load();c.rt.synced=false;
        const resume=()=>{
          if(!state.playing||!state.clips.includes(c)||activeInfo(state.currentTime)?.cur!==c)return;
          try{v.currentTime=target;const p=v.play();p?.catch?.(()=>{});}catch{}
        };
        if(v.readyState>=1)resume();else v.addEventListener('loadedmetadata',resume,{once:true});
      }catch{}
    }
    if(stalledFor>6500){pause();toast('영상 재생을 복구하지 못했습니다. 재생을 다시 눌러주세요.');return false;}
    return stalledFor<250;
  }

  playLoop=function(now){
    if(!state.playing)return;
    const dt=lastTick?Math.min(.1,Math.max(0,(now-lastTick)/1000)):0;
    lastTick=now;
    // Prepare at the actual project position before consulting the media clock.
    renderFrame(state.currentTime,true);
    const ai=activeInfo(state.currentTime),c=ai?.cur;
    let g=state.currentTime,advancing=true;
    if(c?.type==='video'&&!c.placeholder){
      const v=c.rt?.el;
      if(v){
        advancing=checkProgress(c,v,now);
        if(!state.playing)return;
        if(advancing&&!v.seeking&&v.readyState>=2){
          const speed=Math.max(.1,+c.speed||1),end=+c.trimEnd||c.sourceDuration;
          if(v.currentTime>=end-.025||v.ended)g=c.end+.002;
          else g=Math.max(g,c.start+(v.currentTime-(+c.trimStart||0))/speed);
        }
      }else advancing=false;
    }else{watch=null;g+=dt;}
    if(g>=totalDuration()){state.currentTime=totalDuration();pause();return;}
    if(g!==state.currentTime)renderFrame(g,true);
    syncMusic(advancing);
    raf=requestAnimationFrame(playLoop);
  };
})();


// v0.6.6: bounded multi-file import and unchanged-file autosave.
const v066SavedFiles=new Map();
function v066PutMedia(store,key,record,writes){
  if(v066SavedFiles.get(key)===record.blob)return;
  store.put(record,key);writes.set(key,record.blob);
}
(function installBatchImportV066(){
  let session=null;
  function panel(){
    let el=$('importProgressV066');if(el)return el;
    el=document.createElement('div');el.id='importProgressV066';
    el.style.cssText='position:fixed;inset:0;z-index:30000;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;padding:20px';
    el.setAttribute('role','dialog');el.setAttribute('aria-modal','true');el.setAttribute('aria-label','영상 불러오기');
    el.innerHTML='<div style="width:100%;max-width:360px;background:#20232b;color:#fff;border:1px solid #555;border-radius:16px;padding:22px"><strong id="importTitleV066">영상 불러오기</strong><p id="importStatusV066" role="status" style="overflow-wrap:anywhere;line-height:1.5"></p><progress id="importBarV066" max="1" value="0" style="width:100%"></progress><button id="importCancelV066" style="margin-top:18px;width:100%;min-height:44px">불러오기 중단</button></div>';
    document.body.appendChild(el);return el;
  }
  function progress(text,done,total){
    panel().style.display='flex';$('importStatusV066').textContent=text;
    const bar=$('importBarV066');bar.max=Math.max(1,total);bar.value=done;
  }
  function releaseProbe(v){try{v.pause();v.removeAttribute('src');v.load();v.remove();}catch{}}
  function videoMetadata(url,job){
    return new Promise(resolve=>{
      const v=document.createElement('video');let settled=false;
      v.preload='metadata';v.muted=true;v.playsInline=true;v.dataset.importProbe='1';
      v.style.cssText='position:fixed;left:-10000px;width:2px;height:2px';
      const finish=result=>{if(settled)return;settled=true;clearTimeout(timer);v.removeEventListener('loadedmetadata',loaded);v.removeEventListener('error',error);job.abort=null;releaseProbe(v);resolve(result);};
      const loaded=()=>{const d=v.duration;finish(Number.isFinite(d)&&d>0?{duration:d}:{error:'영상 길이를 확인할 수 없음'});};
      const error=()=>finish({error:'이 영상 형식을 읽을 수 없음'});
      const timer=setTimeout(()=>finish({error:'파일 응답 시간 초과'}),12000);
      job.abort=()=>finish({cancelled:true});
      v.addEventListener('loadedmetadata',loaded);v.addEventListener('error',error);
      document.body.appendChild(v);v.src=url;v.load();
    });
  }
  function imageMetadata(url,job){
    return new Promise(resolve=>{
      const img=new Image();let settled=false;
      const finish=result=>{if(settled)return;settled=true;clearTimeout(timer);img.onload=img.onerror=null;job.abort=null;if(!result.img)img.src='';resolve(result);};
      const timer=setTimeout(()=>finish({error:'이미지 응답 시간 초과'}),12000);
      job.abort=()=>finish({cancelled:true});
      img.onload=()=>finish({img});img.onerror=()=>finish({error:'이미지를 읽을 수 없음'});img.src=url;
    });
  }
  const draw=renderFrame;
  renderFrame=function(){if(state.importing)return;return draw.apply(this,arguments);};
  addFiles=async function(files){
    if(session){toast('현재 파일을 불러오는 중입니다.');return;}
    if(state.exporting){toast('영상 저장이 끝난 뒤 추가해 주세요.');return;}
    const arr=Array.from(files||[]);if(!arr.length)return;
    const job={cancelled:false,abort:null};session=job;
    let added=0,processed=0,saveOK=false,unexpected=null;const failures=[];
    pause();state.importing=true;clearTimeout(v038SaveTimer);pushHistory();clearTimeout(v038SaveTimer);
    progress('파일을 준비하고 있습니다…',0,arr.length);
    const cancel=$('importCancelV066');cancel.disabled=false;cancel.textContent='불러오기 중단';
    cancel.onclick=()=>{job.cancelled=true;job.abort?.();cancel.disabled=true;};
    // Park all old transports before probing, so a 12-file selection does not
    // compete with current/next playback decoders or trigger preview re-entry.
    for(const c of state.clips){const v=c.rt?.el;if(!v)continue;try{v.pause();v062InvalidateVideoFrame(v,null,false);v.removeAttribute('src');v.load();c.rt.synced=false;c.rt.preloaded=false;}catch{}}
    try{
      for(let i=0;i<arr.length&&!job.cancelled;i++){
        const file=arr[i],type=file.type||'',name=file.name||'미디어';
        progress(`${i+1} / ${arr.length} · ${name}`,i,arr.length);
        await new Promise(r=>setTimeout(r,0));
        const isImage=type.startsWith('image/')||/\.(jpe?g|png|webp|bmp)$/i.test(name);
        const isVideo=type.startsWith('video/')||/\.(mp4|mov|m4v|webm|mkv)$/i.test(name);
        if(!isImage&&!isVideo){failures.push({name,error:'지원하지 않는 파일'});processed++;continue;}
        const url=URL.createObjectURL(file);
        const meta=await(isImage?imageMetadata(url,job):videoMetadata(url,job));processed++;
        if(meta.error||meta.cancelled){URL.revokeObjectURL(url);if(meta.error)failures.push({name,error:meta.error});continue;}
        const c={id:uid('clip'),type:isImage?'image':'video',file,url,name,duration:3,sourceDuration:isImage?0:meta.duration,trimStart:0,trimEnd:isImage?0:meta.duration,speed:1,volume:1,transition:'cut',transitionDuration:.35,rt:isImage?{img:meta.img}:null};
        state.clips.push(c);state.selectedClipId=c.id;added++;
      }
    }catch(error){unexpected=error;console.warn('LEDOA import',error);}
    finally{
      job.abort?.();state.importing=false;
      recalc();renderAll();$('emptyViewer').classList.toggle('hidden',!!state.clips.length);
      cancel.disabled=true;progress(`${added}개 추가 · 작업 자동 저장 중…`,processed,arr.length);
      // An autosave that began before the file picker may still be finishing.
      const waitUntil=performance.now()+12000;
      while(v038Saving&&performance.now()<waitUntil)await new Promise(r=>setTimeout(r,80));
      try{saveOK=await v038SaveProject(true);}catch{}
      if(!saveOK)v038ScheduleAutosave(1000);
      session=null;state.lastImportResult={selected:arr.length,added,failed:failures,cancelled:job.cancelled,saved:saveOK};
      const summary=`${added}개 추가${job.cancelled?' · 중단됨':''}${failures.length?` · ${failures.length}개 제외`:''}`;
      if(failures.length||unexpected||!saveOK){
        const details=failures.slice(0,3).map(f=>`${f.name}: ${f.error}`).join('\n');
        $('importStatusV066').style.whiteSpace='pre-wrap';
        progress(summary+(details?'\n'+details:'')+(!saveOK?'\n자동 저장을 완료하지 못했습니다. 저장 공간을 확인하고 앱을 닫지 마세요.':'')+(unexpected?'\n일부 파일을 읽는 중 오류가 발생했습니다.':''),processed,arr.length);
        cancel.disabled=false;cancel.textContent='편집 화면으로';cancel.onclick=()=>panel().style.display='none';
      }else{panel().style.display='none';toast(summary);}
    }
  };
})();

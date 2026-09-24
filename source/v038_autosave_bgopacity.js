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
draftTextFromUi=function(){const d=v038DraftOld();if(d)d.bgOpacity=clamp((+$('textBgOpacity')?.value||0)/100,0,1);return d;};
const v038LoadTextOld=loadTextToEditor;
loadTextToEditor=function(t){v038LoadTextOld(t);if($('textBgOpacity')){$('textBgOpacity').value=Math.round(clamp(t?.bgOpacity==null?1:+t.bgOpacity,0,1)*100);$('textBgOpacityLabel').textContent=`${$('textBgOpacity').value}%`;}};
const v038ResetTextOld=resetTextEditor;
resetTextEditor=function(){v038ResetTextOld();if($('textBgOpacity')){$('textBgOpacity').value=100;$('textBgOpacityLabel').textContent='100%';}};
addText=function(){
  const text=$('textInput').value.trim();if(!text)return toast('문구를 입력해 주세요.');
  const start=clamp(+$('textStart').value||0,0,totalDuration()||999),end=Math.max(start+.1,+$('textEnd').value||start+3),kind=$('textKind').value,font=$('textFont').value;
  const data={text,start,end:clamp(end,0,totalDuration()||end),kind,font,size:+$('textSize').value||52,position:$('textPosition').value,color:$('textColor').value,bg:$('textBg').value,bgOpacity:clamp((+$('textBgOpacity')?.value||0)/100,0,1),styleMode:$('shortsStyleEnabled').checked?'shorts':'caption',shortsPreset:v037TextPreset};
  pushHistory();const old=selectedText();if(old)Object.assign(old,data);else state.texts.push({id:uid('text'),...data});resetTextEditor();v038ScheduleAutosave();toast(old?'문구를 수정했습니다.':'문구를 추가했습니다.');
};
(function v038InstallTextOpacityUi(){
  const bg=$('textBg');if(!bg||$('textBgOpacity'))return;
  const host=bg.closest('label')||bg.parentElement;const row=document.createElement('label');row.className='v038-bg-opacity';row.innerHTML=`글자 배경 투명도 <span id="textBgOpacityLabel">100%</span><input id="textBgOpacity" type="range" min="0" max="100" step="1" value="100">`;
  host.insertAdjacentElement('afterend',row);
  $('textBgOpacity').addEventListener('input',e=>{$('textBgOpacityLabel').textContent=`${e.target.value}%`;refreshTextDraft();v038ScheduleAutosave();});
  $('textBgOpacity').addEventListener('change',()=>{pushHistory();v038ScheduleAutosave();});
  const s=document.createElement('style');s.textContent='.v038-bg-opacity{display:block;margin-top:8px}.v038-bg-opacity span{float:right;color:#f0b85e}.v038-bg-opacity input{width:100%;margin-top:7px}';document.head.appendChild(s);
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
    const ms=tx.objectStore(V038_MEDIA),clips=[];for(const m of p.clips||[]){const c={...m,file:null,url:null,rt:null};delete c.mediaKey;const rec=m.mediaKey?await v038Req(ms.get(m.mediaKey)):null;if(rec?.blob){const f=new File([rec.blob],rec.name||c.name||'media',{type:rec.type||rec.blob.type||''});c.file=f;c.url=URL.createObjectURL(f);ensureRuntime(c);}clips.push(c);}
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

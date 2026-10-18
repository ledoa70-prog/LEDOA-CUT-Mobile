/* LEDOA CUT: selected clip -> native automatic/manual mosaic -> one undoable clip update. */
(function(){
 'use strict';
 const native=()=>window.LedoaMosaic,KEY='ledoa-mosaic-pending-v1';
 const files=new Map(),originals=new Map();let busy=false,processing=false,cancelled=false,current=null,polling=false;
 const button=document.createElement('button');button.id='mosaicEditBtn';button.className='primary wide';button.textContent='모자이크 · 자동 / 수동';
 document.getElementById('clipSheetName').insertAdjacentElement('afterend',button);
 const panel=document.createElement('div');panel.id='mosaicProgress';panel.hidden=true;panel.setAttribute('role','dialog');panel.setAttribute('aria-modal','true');panel.setAttribute('aria-label','모자이크 결과 반영');
 panel.innerHTML='<div><strong>모자이크</strong><p id="mosaicStatus" role="status"></p><progress id="mosaicBar" max="100"></progress><button id="mosaicCancel" class="ghost wide">취소</button></div>';document.body.appendChild(panel);
 const style=document.createElement('style');style.textContent='#mosaicEditBtn{margin:8px 0 12px;min-height:46px}#mosaicProgress:not([hidden]){position:fixed;inset:0;z-index:40000;background:#000b;display:flex;align-items:center;justify-content:center;padding:20px}#mosaicProgress>div{width:100%;max-width:360px;padding:22px;background:#172231;border-radius:16px;color:#eef3fa}#mosaicProgress progress{width:100%}';document.head.appendChild(style);
 function status(text,percent=0){panel.hidden=false;document.getElementById('mosaicStatus').textContent=text;document.getElementById('mosaicBar').value=percent;}
 function fingerprint(clip){return JSON.stringify([clip.id,clip.name,clip.file?.size,clip.sourceDuration,clip.trimStart,clip.trimEnd,clip.speed,clip.volume,clip.transition,clip.transitionDuration,clip.mosaic?.token||null]);}
 function tick(){return new Promise(resolve=>setTimeout(resolve,0));}
 function parse(value){try{return JSON.parse(value||'{}');}catch{return {};}}
 function progressDone(){busy=false;processing=false;state.mosaicBusy=false;current=null;panel.hidden=true;document.getElementById('mosaicCancel').disabled=false;refresh();}
 function forget(id){try{native()?.acknowledge(id);}catch{}try{const value=parse(localStorage.getItem(KEY));if(value.id===id)localStorage.removeItem(KEY);}catch{}}
 function refresh(){const clip=selectedClip();button.disabled=busy||state.exporting||state.importing||!clip||clip.type!=='video'||clip.placeholder;button.textContent=clip?.mosaic?'모자이크 수정':'모자이크 · 자동 / 수동';}
 function remember(clip){
  if(!clip.mosaicMediaKey)clip.mosaicMediaKey=uid('mosaic-source');files.set(clip.mosaicMediaKey,clip.file);if(!originals.has(clip.id))originals.set(clip.id,clip.file);return clip.mosaicMediaKey;
 }
 async function saveBeforeEdit(){
  clearTimeout(v038SaveTimer);const until=performance.now()+15000;while(v038Saving&&performance.now()<until)await new Promise(resolve=>setTimeout(resolve,50));
  if(!await v038SaveProject(true))throw Error('현재 작업을 저장하지 못했습니다. 저장 공간을 확인해 주세요.');
 }
 async function source(clip,id){
  if(clip.mosaic?.token){const session=parse(native().session(clip.mosaic.token));if(session.available)return {...session,previousToken:clip.mosaic.token};throw Error('이 모자이크의 원본을 찾지 못했습니다. 기존 결과는 유지했습니다.');}
  const found=parse(native().resolveSource(clip.captionSourceId||'',clip.file.name,clip.file.size));if(found.sourceId)return {sourceId:found.sourceId,name:clip.file.name,size:clip.file.size};
  // IndexedDB may still hold the exact file after an Android picker URI expires.
  // Prepare those bytes directly, without asking the user to select the video again.
  native().beginSource(id,clip.file.name,clip.file.size);
  try{for(let offset=0;offset<clip.file.size;offset+=262144){
   if(cancelled)throw Error('CANCELLED');const bytes=new Uint8Array(await clip.file.slice(offset,offset+262144).arrayBuffer());let binary='';for(let i=0;i<bytes.length;i+=32768)binary+=String.fromCharCode(...bytes.subarray(i,i+32768));
   native().appendSource(id,btoa(binary));status('영상을 준비하고 있습니다.',Math.round((offset+bytes.length)/clip.file.size*100));await tick();
  }if(cancelled)throw Error('CANCELLED');const sourceId=native().finishSource(id);clip.captionSourceId=sourceId;return {sourceId,name:clip.file.name,size:clip.file.size};
  }catch(error){native().cancelSource(id);throw error;}
 }
 async function open(){
  const clip=selectedClip();if(busy||state.importing||state.exporting)return;
  if(!clip||clip.type!=='video'||!clip.file||clip.placeholder)return toast('타임라인에서 영상 클립을 선택해 주세요.');
  if(!native())return toast('모자이크 편집은 Android 설치 앱에서 사용할 수 있습니다.');
  pause();closeSheets();busy=true;cancelled=false;state.mosaicBusy=true;refresh();const id=uid('mosaic');current={id,clipId:clip.id};status('현재 작업을 저장하고 있습니다.');
  try{
   remember(clip);await saveBeforeEdit();const selectedSource=await source(clip,id);if(cancelled)throw Error('CANCELLED');
   // The source link can be refreshed while preparing bytes; capture the final source fingerprint.
   const pending={id,clipId:clip.id,fingerprint:fingerprint(clip)};current=pending;localStorage.setItem(KEY,JSON.stringify(pending));
   const sourceTime=clamp(clip.trimStart+Math.max(0,state.currentTime-clip.start)*(+clip.speed||1),clip.trimStart,clip.trimEnd-.001);
   status('모자이크 편집 화면을 여는 중입니다.');document.getElementById('mosaicCancel').disabled=true;
   native().edit(JSON.stringify({...pending,...selectedSource,sourceStart:clip.trimStart,sourceEnd:clip.trimEnd,sourceTime}));
  }catch(error){forget(id);progressDone();toast(error.message==='CANCELLED'?'모자이크를 취소했습니다.':error.message||'모자이크를 열지 못했습니다.');}
 }
 document.getElementById('mosaicCancel').onclick=()=>{if(processing)return;cancelled=true;if(current?.id)try{native()?.cancelSource(current.id);}catch{}};
 async function readFile(event){
  if(!Number.isSafeInteger(event.size)||event.size<1024)throw Error('모자이크 결과 크기를 확인하지 못했습니다.');
  const parts=[];for(let offset=0;offset<event.size;offset+=262144){
   const count=Math.min(262144,event.size-offset),text=native().readResult(event.token,offset,count),binary=atob(text);if(binary.length!==count)throw Error('모자이크 결과를 끝까지 읽지 못했습니다.');
   const bytes=new Uint8Array(count);for(let i=0;i<count;i++)bytes[i]=binary.charCodeAt(i);parts.push(bytes);status('클립에 모자이크 결과를 준비하고 있습니다.',Math.round((offset+count)/event.size*100));await tick();
  }return new File(parts,event.name,{type:'video/mp4'});
 }
 function validate(file){return new Promise((resolve,reject)=>{
  const video=document.createElement('video'),url=URL.createObjectURL(file);let done=false;
  const finish=(error)=>{if(done)return;done=true;clearTimeout(timer);const duration=video.duration;try{video.removeAttribute('src');video.load();URL.revokeObjectURL(url);}catch{}error?reject(error):resolve(duration);};
  const timer=setTimeout(()=>finish(Error('모자이크 결과 영상을 확인하지 못했습니다.')),15000);video.preload='metadata';video.muted=true;video.onloadedmetadata=()=>finish(Number.isFinite(video.duration)&&video.duration>0?null:Error('결과 영상 길이가 잘못되었습니다.'));video.onerror=()=>finish(Error('모자이크 결과 영상을 읽지 못했습니다.'));video.src=url;video.load();
 });}
 function retire(previous,oldUrl){if(typeof v065RetireRuntimes==='function')v065RetireRuntimes(previous);if(oldUrl&&!state.clips.some(clip=>clip.url===oldUrl))try{URL.revokeObjectURL(oldUrl);}catch{}}
 async function receive(event){
  const pending=current||parse(localStorage.getItem(KEY));if(!event||!pending.id||event.id!==pending.id||processing)return;
  if(event.state==='editing')return;
  if(event.state!=='ready'){forget(event.id);progressDone();toast(event.state==='cancelled'?'모자이크를 취소했습니다. 기존 클립을 유지합니다.':event.message||'모자이크를 적용하지 못했습니다.');return;}
  const clip=state.clips.find(item=>item.id===pending.clipId);if(!clip||fingerprint(clip)!==pending.fingerprint){forget(event.id);progressDone();toast('클립 편집이 달라져 모자이크 결과를 적용하지 않았습니다.');return;}
  busy=processing=true;state.mosaicBusy=true;refresh();status('모자이크 결과를 확인하고 있습니다.');document.getElementById('mosaicCancel').disabled=true;
  let before=null,historyBefore=null;
  try{
   const file=await readFile(event),duration=await validate(file);if(Math.abs(duration-clip.sourceDuration)>.25)throw Error('결과 영상 길이가 원본과 달라 적용하지 않았습니다.');
   if(!state.clips.includes(clip)||fingerprint(clip)!==pending.fingerprint)throw Error('클립 편집이 달라져 결과를 적용하지 않았습니다.');
   remember(clip);before={...clip};historyBefore=state.history.slice();pushHistory();const previous=state.clips.map(item=>item.rt).filter(Boolean),oldUrl=clip.url;
   const key=uid('mosaic-media');files.set(key,file);Object.assign(clip,{file,url:URL.createObjectURL(file),rt:null,name:event.name,mosaicMediaKey:key,captionSourceId:event.resultSourceId,mosaic:{token:event.token,sourceId:event.sourceId,sourceName:event.sourceName,sourceSize:event.sourceSize,outputDuration:duration}});
   retire(previous,oldUrl);recalc();renderAll();await saveBeforeEdit();forget(event.id);progressDone();toast('선택한 클립에 모자이크를 적용했습니다. ↶로 되돌릴 수 있습니다.');
  }catch(error){if(before&&state.clips.includes(clip)){const previous=state.clips.map(item=>item.rt).filter(Boolean),oldUrl=clip.url;for(const key of Object.keys(clip))delete clip[key];Object.assign(clip,before,{url:URL.createObjectURL(before.file),rt:null});state.history=historyBefore;retire(previous,oldUrl);recalc();renderAll();document.getElementById('undoBtn').disabled=!state.history.length;}forget(event.id);progressDone();toast(error.message||'모자이크 결과를 적용하지 못했습니다.');}
 }
 window.LEDOA_MOSAIC_EVENT=receive;
 const oldUpdate=updateClipSheet;updateClipSheet=function(){const result=oldUpdate.apply(this,arguments);refresh();return result;};
 const oldUndo=undo;undo=function(){
  if(busy)return;if(!state.history.length)return oldUndo.apply(this,arguments);
  const previous=state.clips.map(clip=>clip.rt).filter(Boolean),raw=parse(state.history[state.history.length-1]);const result=oldUndo.apply(this,arguments);
  for(const clip of state.clips){const expected=(raw.clips||[]).find(item=>item.id===clip.id);const file=expected?.mosaicMediaKey?files.get(expected.mosaicMediaKey):originals.get(clip.id);
   if(file&&file!==clip.file){const oldUrl=clip.url;clip.file=file;clip.url=URL.createObjectURL(file);clip.rt=null;retire(previous,oldUrl);}
  }recalc();renderAll();v038ScheduleAutosave(100);refresh();return result;
 };
 // The editor's original handler captured the previous undo function at load time.
 document.getElementById('undoBtn').onclick=undo;button.onclick=open;
 async function pendingResult(){
  if(!native()||processing||polling||typeof v038Restoring!=='undefined'&&v038Restoring)return;polling=true;
  try{const pending=parse(localStorage.getItem(KEY));if(!pending.id)return;const clip=state.clips.find(item=>item.id===pending.clipId);if(!clip)return;
   const event=parse(native().pending());if(event.id===pending.id){current=pending;busy=true;state.mosaicBusy=true;await receive(event);}
  }catch{}finally{polling=false;}
 }
 setInterval(pendingResult,800);window.addEventListener('pageshow',pendingResult);window.addEventListener('focus',pendingResult);refresh();
 window.LedoaMosaicEditor={open,get busy(){return busy;}};
})();

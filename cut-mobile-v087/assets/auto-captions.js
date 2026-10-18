/* Google device-recognition beta. No Cloud key, server or paid API calls. */
(function(){
 'use strict';
 const C=window.LedoaCaptionCore;if(!C||document.getElementById('autoCaptionSheet'))return;
 const el=id=>document.getElementById(id);
 const section=document.createElement('section');section.id='autoCaptionSheet';section.className='bottom-sheet hidden';section.setAttribute('aria-label','자동자막 시험');
 section.innerHTML=`<div class="sheet-handle"></div><div class="sheet-head"><strong>자동자막 <small>구글 · 시험</small></strong><button id="acClose" aria-label="자동자막 닫기">×</button></div>
 <p class="sheet-sub">① 구글 인식 → ② 문단·오타 수정 → ③ 시간 정렬 → 자동 배치</p>
 <label>음성을 읽을 영상<select id="acClip"></select></label>
 <div class="two-col"><label>시험 길이<select id="acLength"><option value="15">15초</option><option value="30" selected>30초</option><option value="60">60초</option></select></label><label>자막 시작 위치<select id="acBegin"><option value="current">현재 재생 위치</option><option value="begin">클립 시작부터</option></select></label></div>
 <div id="acRange" class="ac-note"></div>
 <div class="ac-row"><button id="acLink" class="ghost">원본 파일 연결</button><button id="acGenerate" class="primary">구글로 자막 생성</button><button id="acCancel" class="danger hidden">작업 취소</button></div>
 <input id="acSourceFile" type="file" accept="video/*" hidden>
 <p class="ac-note">선택한 영상 오디오를 Google 음성서비스에 전달합니다. 인터넷 연결이 필요할 수 있고, 인식에는 영상 길이만큼 걸릴 수 있습니다. 첫 실행 시 오디오 권한을 허용해 주세요.</p>
 <div id="acStatus" class="ac-status" role="status" aria-live="polite">영상과 시험 길이를 선택해 주세요.</div><progress id="acProgress" max="100" value="0" class="hidden"></progress>
 <div id="acReview" class="hidden"><div class="ac-review-head"><strong id="acCount"></strong><button id="acDiscard" class="ghost">결과 비우기</button></div><p id="acTimingNote" class="ac-note"></p><div id="acRows"></div><p class="ac-note">문단과 오타를 고친 뒤 시간 정렬을 누르면 기기 안에서 싱크를 계산하고 타임라인에 배치합니다. 처음에는 모델 준비에 시간이 걸립니다.</p><button id="acAlign" class="primary wide">시간 정렬</button><button id="acApply" class="primary wide">검토한 자막 적용</button></div>
 <button id="acExisting" class="ghost wide">기존 자막 가져오기</button><div class="ac-files"><button id="acImport" class="ghost">SRT 자막 불러오기</button><button id="acExport" class="ghost">현재 자막 SRT 저장</button><input id="acSrtFile" type="file" accept=".srt,text/plain,application/x-subrip" hidden></div>`;
 document.body.appendChild(section);
 const launcher=document.createElement('button');launcher.id='autoCaptionBtn';launcher.className='primary wide';launcher.textContent='자동자막 · 구글 시험';
 const head=el('textSheet')?.querySelector('.sheet-head');if(head)head.insertAdjacentElement('afterend',launcher);
 let busy=false,jobId=null,pendingResolve=null,runSerial=0,draft=[],draftFingerprint=null,draftIsSrt=false,draftAnchor=null,draftExistingIds=[],draftExistingSnapshot=null,alignmentJob=null,alignmentWaiter=null;
 const bridge=()=>window.LedoaCaptions;
 function status(message,error=false){el('acStatus').textContent=message;el('acStatus').classList.toggle('ac-error',error);}
 function capabilities(){try{return JSON.parse(bridge()?.capabilities()||'{}');}catch{return {};}}
 function chosen(){return state.clips.find(c=>c.id===el('acClip').value&&c.type==='video');}
 function fingerprint(){return JSON.stringify(state.clips.map(c=>({id:c.id,start:c.start,end:c.end,trimStart:c.trimStart,trimEnd:c.trimEnd,speed:c.speed,source:c.captionSourceId,name:c.name,size:c.file?.size})));}
 function selectedRange(){const c=chosen();if(!c)return null;const speed=+c.speed||1;const begin=el('acBegin').value==='current'&&state.currentTime>=c.start&&state.currentTime<c.end?state.currentTime:c.start;
   const sourceStart=c.trimStart+(begin-c.start)*speed;const sourceEnd=Math.min(c.trimEnd,sourceStart+Number(el('acLength').value)*speed,sourceStart+120);
   return {clip:c,sourceStart,sourceEnd,clipStart:c.start,trimStart:c.trimStart,speed};}
 function updateRange(){const r=selectedRange();el('acRange').textContent=r?`${r.clip.name} · 타임라인 ${C.clock(C.at(r.sourceStart,r))} ~ ${C.clock(C.at(r.sourceEnd,r))}`:'먼저 영상 클립을 넣어 주세요.';}
 function setBusy(value){busy=value;for(const id of ['acClip','acLength','acBegin','acGenerate','acLink','acImport','acSourceFile','acSrtFile','acDiscard','acExisting'])el(id).disabled=value;for(const input of el('acRows').querySelectorAll('textarea,input,button'))input.disabled=value;el('acAlign').disabled=value||!draft.length||!window.LedoaAlignment;el('acCancel').classList.toggle('hidden',!value);el('acProgress').classList.toggle('hidden',!value);el('acApply').disabled=value||!draft.length;}
 function open(){if(state.exporting||state.importing)return toast('현재 작업이 끝난 뒤 열어 주세요.');if(state.playing)pause();const previous=el('acClip').value;el('acClip').replaceChildren();
   for(const c of state.clips.filter(c=>c.type==='video')){const option=document.createElement('option');option.value=c.id;option.textContent=c.name;el('acClip').appendChild(option);}
   el('acClip').value=state.clips.some(c=>c.id===previous)?previous:state.selectedClipId;
   if(!el('acClip').value&&el('acClip').options.length)el('acClip').selectedIndex=0;
   openSheet('autoCaptionSheet');updateRange();const cap=capabilities();if(!bridge())status('구글 인식은 Android 설치 앱에서 시험할 수 있습니다. SRT 불러오기는 여기서도 가능합니다.');else if(!cap.fileInput)status('구글 인식 시험에는 Android 14 이상이 필요합니다.',true);else if(!cap.googleAvailable)status('Google 앱과 Google 음성서비스가 설치되어 있는지 확인해 주세요.',true);
 }
 function cancel(){runSerial++;bridge()?.cancel();window.LedoaAlignment?.cancel();if(alignmentWaiter){alignmentWaiter.reject(Error('CANCELLED'));alignmentWaiter=null;alignmentJob=null;}jobId=null;const wait=pendingResolve;pendingResolve=null;if(wait)wait.reject(new Error('CANCELLED'));setBusy(false);status('생성을 취소했습니다. 적용된 기존 자막은 유지됩니다.');}
 function close(){if(busy)cancel();closeSheets();}
 function clearDraft(){draft=[];draftFingerprint=null;draftIsSrt=false;draftAnchor=null;draftExistingIds=[];draftExistingSnapshot=null;el('acRows').replaceChildren();el('acReview').classList.add('hidden');el('acApply').disabled=true;}
 function renderDraft(){el('acReview').classList.toggle('hidden',!draft.length);el('acCount').textContent=`자막 ${draft.length}개 검토`;el('acRows').replaceChildren();
   const aligned=draft.every(c=>c.timing==='forced-aligned');const approximate=draft.some(c=>c.timing==='range'),split=draft.some(c=>c.timing==='manual-split');el('acTimingNote').textContent=aligned?'음성과 수정된 문장으로 시간을 맞췄습니다. 확인 필요 문단은 재생하며 점검해 주세요.':split?'문단별 시간은 글자 수로 나눴습니다. 재생하며 시작·끝을 확인해 주세요.':approximate?'이 기기가 단어별 시간을 제공하지 않아 구간 전체에 임시 배치했습니다. 시작·끝 시간을 직접 확인해 주세요.':'단어 시작 시간을 기준으로 배치했습니다. 자막의 끝 시간과 오타는 재생하며 확인해 주세요.';
   if(draftIsSrt&&!split)el('acTimingNote').textContent='SRT에 저장된 시간을 사용합니다. 타임라인 범위와 내용을 확인해 주세요.';
   draft.forEach((cue,i)=>{const row=document.createElement('div');row.className='ac-cue';
     const text=document.createElement('textarea');text.value=cue.text;text.setAttribute('aria-label',`${i+1}번 자막 내용`);text.rows=2;text.maxLength=10000;text.addEventListener('input',()=>{cue.text=text.value;delete cue.wordTimings;delete cue.alignmentReview;cue.timing='edited';row.querySelector('.ac-alignment-review')?.remove();el('acTimingNote').textContent='문단·오타를 수정했습니다. 시간 정렬을 눌러 수정한 문장에 맞춰 주세요.';});row.appendChild(text);if(cue.alignmentReview){const badge=document.createElement('div');badge.className='ac-alignment-review';badge.textContent='시간 확인 필요 · 이 문단의 일부 단어가 불확실합니다.';row.appendChild(badge);}
     let caret=0,composing=false;text.addEventListener('compositionstart',()=>{composing=true;});text.addEventListener('compositionend',()=>{composing=false;caret=text.selectionStart;});
     const splitTools=document.createElement('div');splitTools.className='ac-cue-split';
     {const button=document.createElement('button');button.type='button';button.className='ghost';button.dataset.split='cursor';button.textContent='커서에서 나누기';
       button.addEventListener('pointerdown',()=>{caret=text.selectionStart;});
       button.addEventListener('click',()=>{if(composing)return status('글자 입력을 마친 뒤 눌러 주세요.',true);try{cue.text=text.value;const parts=C.splitCueAt(cue,caret);parts.forEach((part,index)=>{delete part.wordTimings;delete part.alignmentReview;if(index)delete part._existingId;});draft.splice(i,1,...parts);renderDraft();const next=el('acRows').children[i+1]?.querySelector('textarea');if(next){next.focus({preventScroll:true});next.setSelectionRange(0,0);}status(`${parts.length}개 문단으로 나눴습니다. 시간을 확인한 뒤 적용해 주세요.`);}catch(error){status(error.message,true);}});splitTools.appendChild(button);
     }
     for(const name of ['input','select','keyup','pointerup'])text.addEventListener(name,()=>{caret=text.selectionStart;});row.appendChild(splitTools);
     const timing=document.createElement('div');timing.className='ac-cue-time';
     for(const [key,label] of [['start','시작 초'],['end','끝 초']]){const wrap=document.createElement('label');wrap.textContent=label;const input=document.createElement('input');input.type='number';input.step='.01';input.min='0';input.value=cue[key].toFixed(2);input.setAttribute('aria-label',`${i+1}번 자막 ${label}`);input.addEventListener('input',()=>cue[key]=input.value===''?NaN:Number(input.value));wrap.appendChild(input);timing.appendChild(wrap);}
     const remove=document.createElement('button');remove.className='ghost';remove.textContent='삭제';remove.addEventListener('click',()=>{draft.splice(i,1);renderDraft();});timing.appendChild(remove);row.appendChild(timing);el('acRows').appendChild(row);
   });el('acApply').disabled=busy||!draft.length;el('acAlign').disabled=busy||!draft.length||!window.LedoaAlignment;
 }
 function requestNative(payload){return new Promise((resolve,reject)=>{jobId=payload.id;pendingResolve={resolve,reject};try{bridge().transcribe(JSON.stringify(payload));}catch(error){jobId=null;pendingResolve=null;reject(error);}});}
 window.LEDOA_CAPTION_EVENT=function(event){if(!busy||event.id!==jobId)return;if(event.type==='progress'){status(event.message);el('acProgress').value=event.percent;return;}
   const waiter=pendingResolve;pendingResolve=null;jobId=null;if(!waiter)return;if(event.type==='result')waiter.resolve(event);else waiter.reject(new Error(event.message||'음성인식에 실패했습니다.'));
 };
 async function generate(){if(busy)return;const r=selectedRange(),cap=capabilities();if(!r||!r.clip.file)return status('음성이 있는 영상 클립을 먼저 넣어 주세요.',true);
   if(!bridge()||!cap.fileInput||!cap.googleAvailable)return status('Android 14 이상 설치 앱과 Google 음성서비스가 필요합니다.',true);
   if(r.sourceEnd-r.sourceStart<.2)return status('인식할 구간이 너무 짧습니다.',true);
   try{if(!r.clip.captionSourceId){const found=JSON.parse(bridge().resolveSource(r.clip.file.name,r.clip.file.size));if(found.sourceId)r.clip.captionSourceId=found.sourceId;}
     if(!r.clip.captionSourceId)return status('원본 파일 연결을 눌러 이 영상과 같은 파일을 선택해 주세요. 영상이 중복 추가되지는 않습니다.',true);
   }catch{return status('원본 파일 연결을 눌러 다시 선택해 주세요.',true);}
   clearDraft();const serial=++runSerial;const original=fingerprint();setBusy(true);let output=[];const count=Math.ceil((r.sourceEnd-r.sourceStart)/30);
   try{for(let i=0;i<count;i++){if(serial!==runSerial)throw Error('CANCELLED');const start=r.sourceStart+i*30,end=Math.min(r.sourceEnd,start+30);status(`${i+1}/${count} 구간을 준비하고 있습니다.`);
       const result=await requestNative({id:`ac-${Date.now()}-${serial}-${i}`,sourceId:r.clip.captionSourceId,name:r.clip.file.name,size:r.clip.file.size,sourceStart:start,sourceEnd:end});
       output.push(...C.sourceCues(result,r));
     }
     if(serial!==runSerial)return;if(original!==fingerprint())throw Error('영상 편집이 변경되었습니다. 변경된 구간으로 다시 생성해 주세요.');
     draft=output;draftFingerprint=original;draftAnchor={clipId:r.clip.id,sourceId:r.clip.captionSourceId,name:r.clip.file.name,size:r.clip.file.size,sourceStart:r.sourceStart,sourceEnd:r.sourceEnd,clipStart:r.clipStart,trimStart:r.trimStart,speed:r.speed};draftIsSrt=false;renderDraft();status(draft.length?`${draft.length}개를 인식했습니다. 검토한 뒤 적용해 주세요.`:'이 구간에서 인식한 말이 없습니다.');
   }catch(error){if(serial!==runSerial)return;status(error.message==='CANCELLED'?'생성을 취소했습니다.':error.message,true);}
   finally{if(serial===runSerial){pendingResolve=null;jobId=null;setBusy(false);}}
 }
 function existingSnapshot(ids){return JSON.stringify(ids.map(id=>state.texts.find(t=>t.id===id)||null));}
 function loadExisting(){
   if(busy)return;const r=selectedRange();if(!r)return status('먼저 영상 클립을 선택해 주세요.',true);
   const begin=C.at(r.sourceStart,r),end=C.at(r.sourceEnd,r),rows=state.texts.filter(t=>t.kind==='caption'&&t.end>begin&&t.start<end).sort((a,b)=>a.start-b.start);
   if(!rows.length)return status('선택한 구간에 적용된 자막이 없습니다.',true);
   if(rows.some(t=>t.start<r.clip.start-.01||t.end>r.clip.end+.01))return status('한 영상 클립 안에 있는 자막을 선택해 주세요. 클립 경계를 넘는 문단은 먼저 나눠 주세요.',true);
   const sourceStart=Math.max(r.clip.trimStart,Math.min(r.sourceStart,...rows.map(t=>r.trimStart+(t.start-r.clipStart)*r.speed)));
   const sourceEnd=Math.min(r.clip.trimEnd,Math.max(r.sourceEnd,...rows.map(t=>r.trimStart+(t.end-r.clipStart)*r.speed)));
   if(sourceEnd-sourceStart>120.01)return status('한 번에 원본 음성 120초 안의 자막을 선택해 주세요.',true);
   clearDraft();draft=rows.map(t=>({text:t.text,start:t.start,end:t.end,timing:t.captionTiming||'existing',_existingId:t.id,_style:JSON.parse(JSON.stringify(t)),alignmentReview:!!t.captionReview,wordTimings:t.captionWords}));
   draftExistingIds=rows.map(t=>t.id);draftExistingSnapshot=existingSnapshot(draftExistingIds);draftFingerprint=fingerprint();
   draftAnchor={clipId:r.clip.id,sourceId:r.clip.captionSourceId,name:r.clip.file?.name,size:r.clip.file?.size,sourceStart,sourceEnd,clipStart:r.clipStart,trimStart:r.trimStart,speed:r.speed};renderDraft();status('기존 자막을 가져왔습니다. 문단과 오타를 수정한 뒤 시간 정렬을 눌러 주세요. 같은 자막의 시간을 갱신합니다.');
 }
 window.LEDOA_ALIGNMENT_EVENT=function(event){
   if(!busy||event.id!==alignmentJob)return;
   if(event.type==='progress'){status(event.message);el('acProgress').value=event.percent;return;}
   const waiter=alignmentWaiter;alignmentWaiter=null;alignmentJob=null;if(!waiter)return;
   if(event.type==='result')waiter.resolve(event);else waiter.reject(Error(event.message||'시간 정렬에 실패했습니다. 기존 자막은 유지했습니다.'));
 };
 async function align(){
   if(busy||!draft.length)return;if(!window.LedoaAlignment)return status('시간 정렬은 Android 설치 앱에서 사용할 수 있습니다.',true);
   if(!draftAnchor||draftFingerprint!==fingerprint())return status('영상 구간이 변경되었습니다. 자막을 다시 가져오거나 생성해 주세요.',true);
   if(draftExistingIds.length&&draftExistingSnapshot!==existingSnapshot(draftExistingIds))return status('적용된 자막이 변경되었습니다. 기존 자막을 다시 가져와 주세요.',true);
   if(draft.some(c=>!String(c.text).trim()))return status('비어 있는 문단을 수정하거나 삭제해 주세요.',true);
   const c=state.clips.find(c=>c.id===draftAnchor.clipId);if(!c?.file)return status('원본 영상을 다시 연결해 주세요.',true);
   if(!c.captionSourceId)try{c.captionSourceId=JSON.parse(bridge()?.resolveSource(c.file.name,c.file.size)||'{}').sourceId;}catch{}
   if(!c.captionSourceId)return status('원본 파일 연결을 눌러 같은 영상 파일을 선택해 주세요.',true);
   draftAnchor.sourceId=c.captionSourceId;draftFingerprint=fingerprint();
   const original=fingerprint(),content=JSON.stringify(draft),serial=++runSerial;setBusy(true);status('시간 정렬을 시작합니다. 자막과 음성은 기기 안에서 대조합니다.');
   try{
     const result=await new Promise((resolve,reject)=>{alignmentJob=`align-${Date.now()}-${serial}`;alignmentWaiter={resolve,reject};try{window.LedoaAlignment.align(JSON.stringify({...draftAnchor,id:alignmentJob,cues:draft.map(c=>c.text)}));}catch(e){alignmentWaiter=null;alignmentJob=null;reject(e);}});
     if(serial!==runSerial)return;if(original!==fingerprint()||content!==JSON.stringify(draft))throw Error('편집 내용이 바뀌어 정렬 결과를 적용하지 않았습니다. 다시 눌러 주세요.');
     draft=window.LedoaAlignmentCore.alignRows(draft,result,draftAnchor);renderDraft();setBusy(false);
     const review=draft.filter(c=>c.alignmentReview).length;if(apply({keepReview:true}))status(`시간 정렬을 마치고 ${draft.length}개 문단을 배치했습니다.${review?' 확인 필요 '+review+'개 문단은 재생하며 확인해 주세요.':' 미리보기로 싱크를 확인해 주세요.'}`);
   }catch(e){if(serial===runSerial)status(e.message==='CANCELLED'?'시간 정렬을 취소했습니다.':e.message,true);}
   finally{if(serial===runSerial){alignmentWaiter=null;alignmentJob=null;setBusy(false);}}
 }
 function apply(options={}){if(busy||!draft.length)return false;if(draftFingerprint!==fingerprint())return status('영상 편집이 변경되었습니다. 자막을 다시 생성하거나 불러와 주세요.',true),false;
   if(draftExistingIds.length&&draftExistingSnapshot!==existingSnapshot(draftExistingIds))return status('기존 자막이 변경되어 적용하지 않았습니다. 다시 가져와 주세요.',true),false;
   const duration=totalDuration();if(draft.some(c=>!String(c.text).trim()||!Number.isFinite(c.start)||!Number.isFinite(c.end)||c.start<0||c.end<=c.start||c.start>=duration||c.end>duration+.015))return status('자막의 내용과 시작·끝 시간을 확인해 주세요. 영상 범위 안에 있어야 합니다.',true),false;
   const amount=draft.length,batch=uid('ac');const selected=state.texts.find(t=>t.id===state.selectedTextId&&t.kind==='caption')||{};
   const used=new Set();const added=draft.map(c=>{const style=c._style||selected;const id=c._existingId&&!used.has(c._existingId)?c._existingId:uid('text');used.add(id);return {...style,id,text:c.text.trim(),start:c.start,end:Math.min(duration,c.end),kind:'caption',font:style.font||'Gowun Dodum',size:style.size||52,position:style.position||'shorts-safe',customY:style.customY??.7,color:style.color||'#ffffff',bg:style.bg||'#000000',bgOpacity:style.bgOpacity??0,styleMode:style.styleMode||'caption',shortsPreset:style.shortsPreset||'news',captionBatch:batch,captionEngine:draftIsSrt?'srt':'android-google',captionTiming:c.timing,captionWords:c.wordTimings,captionReview:!!c.alignmentReview,captionSource:draftAnchor?{...draftAnchor}:undefined};});
   pushHistory();if(draftExistingIds.length){const ids=new Set(draftExistingIds);let inserted=false;state.texts=state.texts.flatMap(t=>{if(!ids.has(t.id))return [t];if(inserted)return [];inserted=true;return added;});}else state.texts.push(...added);
   state.selectedTextId=null;renderTextList();renderFrame(state.currentTime,false);v038ScheduleAutosave(100);
   if(options.keepReview){draft=draft.map((row,i)=>({...row,_existingId:added[i].id,_style:JSON.parse(JSON.stringify(added[i]))}));draftExistingIds=added.map(t=>t.id);draftExistingSnapshot=existingSnapshot(draftExistingIds);renderDraft();}
   else clearDraft();status(`${amount}개 자막을 적용했습니다. 되돌리기로 취소할 수 있습니다.`);toast(`자막 ${amount}개를 적용했습니다.`);return true;
 }
 el('autoCaptionBtn').addEventListener('click',open);el('acClose').addEventListener('click',close);el('acCancel').addEventListener('click',cancel);el('acGenerate').addEventListener('click',generate);el('acApply').addEventListener('click',()=>apply());el('acAlign').addEventListener('click',align);el('acExisting').addEventListener('click',loadExisting);el('acDiscard').addEventListener('click',clearDraft);
 for(const id of ['acClip','acLength','acBegin'])el(id).addEventListener('change',updateRange);
 el('acLink').addEventListener('click',()=>{if(!chosen())return status('연결할 영상 클립을 선택해 주세요.',true);el('acSourceFile').click();});
 el('acSourceFile').addEventListener('change',async event=>{const file=event.target.files?.[0];event.target.value='';const c=chosen();if(!file||!c)return;
   if(!c.file||file.name!==c.file.name||file.size!==c.file.size)return status('선택한 영상과 이름·크기가 같은 원본 파일을 연결해 주세요.',true);
   try{const info=JSON.parse(bridge()?.lastSource()||'{}');if(info.name!==file.name||info.size!==file.size||!info.sourceId)throw Error('원본 연결은 Android 설치 앱에서 할 수 있습니다.');c.captionSourceId=info.sourceId;v038ScheduleAutosave(100);status('원본 파일을 연결했습니다. 구글로 자막 생성을 눌러 주세요.');}catch(e){status(e.message,true);}
 });
 el('acImport').addEventListener('click',()=>{if(!state.clips.length)return status('먼저 영상을 넣어 주세요.',true);el('acSrtFile').click();});
 el('acSrtFile').addEventListener('change',async event=>{const file=event.target.files?.[0];event.target.value='';if(!file)return;try{if(file.size>2000000)throw Error('자막 파일은 2MB 이하로 선택해 주세요.');const rows=C.parseSrt(await file.text());if(rows.some(c=>c.start>=totalDuration()||c.end>totalDuration()+.015))throw Error('SRT 자막 시간이 현재 영상 길이를 벗어납니다. 영상을 먼저 확인해 주세요.');clearDraft();draft=rows;draftFingerprint=fingerprint();draftIsSrt=true;const r=selectedRange();if(r)draftAnchor={clipId:r.clip.id,sourceId:r.clip.captionSourceId,name:r.clip.file?.name,size:r.clip.file?.size,sourceStart:r.sourceStart,sourceEnd:r.sourceEnd,clipStart:r.clipStart,trimStart:r.trimStart,speed:r.speed};renderDraft();status('자막 파일을 불러왔습니다. 검토 후 적용해 주세요.');}catch(e){status(e.message,true);}});
 el('acExport').addEventListener('click',async()=>{const text=C.toSrt(state.texts);if(!text)return status('저장할 자막이 없습니다.',true);await saveBlob(new Blob(['\uFEFF'+text],{type:'application/x-subrip'}),`LEDOA-CAPTIONS-${Date.now()}.srt`);});
 const oldClose=closeSheets;closeSheets=function(){if(busy&&!section.classList.contains('hidden'))cancel();return oldClose.apply(this,arguments);};
 const oldOpen=openSheet;openSheet=function(id){if(id!=='autoCaptionSheet'&&busy&&!section.classList.contains('hidden'))cancel();return oldOpen.apply(this,arguments);};
 const oldBack=window.LEDOA_CLOSE_TOP_SHEET;window.LEDOA_CLOSE_TOP_SHEET=function(){if(!section.classList.contains('hidden')){close();return true;}return oldBack?oldBack():false;};
 new MutationObserver(()=>{if(busy&&section.classList.contains('hidden'))cancel();}).observe(section,{attributes:true,attributeFilter:['class']});
 window.LedoaAutoCaptions={open,close,cancel,version:'0.8.6'};
})();

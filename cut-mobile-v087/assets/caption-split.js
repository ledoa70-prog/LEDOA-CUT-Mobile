/* Local caption editing only. The working v0.8.3 recognition engine is preserved. */
(function(){
 'use strict';
 const C=window.LedoaCaptionCore,input=$('textInput');if(!C||!input||$('captionSplitTools'))return;
 const panel=document.createElement('div');panel.id='captionSplitTools';panel.className='control-card caption-split-tools';
 panel.innerHTML=`<div class="control-title"><b>자막 나누기</b></div><p class="caption-split-hint">나눌 곳을 터치해 커서를 놓고, 커서에서 나누기를 누르세요. Enter는 같은 문단 안에서 줄바꿈합니다.</p><div class="caption-split-buttons"><button id="captionSplitCursor" type="button" class="ghost">커서에서 나누기</button></div><div class="caption-split-time"><label>분할 시각 (초)<input id="captionSplitTime" type="number" min="0" step="0.01" inputmode="decimal" placeholder="커서를 문장 중간에 놓으세요"></label><button id="captionSplitPlayhead" type="button" class="ghost">현재 재생 위치</button></div><p id="captionSplitStatus" class="caption-split-hint" role="status" aria-live="polite">시간은 글자 수에 따라 배분하며, 위 시각을 직접 바꿀 수도 있습니다.</p>`;
 input.insertAdjacentElement('afterend',panel);
 const originalMax=input.maxLength;let composing=false,cursor=0,manualTime=false,context='',refreshing=false;
 const draftRestores=[];
 function message(text,error=false){$('captionSplitStatus').textContent=text;$('captionSplitStatus').classList.toggle('split-error',error);}
 function editorCue(){
   const text=input.value,start=Number($('textStart').value),end=Number($('textEnd').value);
   if(!text.trim())throw Error('나눌 자막을 입력하거나 목록에서 선택해 주세요.');
   if($('textStart').value===''||$('textEnd').value===''||!Number.isFinite(start)||!Number.isFinite(end)||start<0||end<=start||end>totalDuration()+.015)throw Error('자막의 시작·끝 시간을 현재 영상 범위 안으로 설정해 주세요.');
   const base=selectedText(),draft=draftTextFromUi()||{},extra=v039CurrentTextDraft()||{};
   const cue={...(base||{}),...extra,...draft,text,start,end,kind:$('textKind').value};delete cue.id;delete cue.draft;
   if(cue.kind!=='caption')throw Error('문단 나누기는 자막에서 사용할 수 있습니다.');
   return cue;
 }
 function refresh(){
   if(refreshing)return;refreshing=true;
   try{
     const caption=$('textKind').value==='caption';panel.classList.toggle('hidden',!caption);input.maxLength=caption?10000:originalMax;
     const key=String(state.selectedTextId||'new');if(key!==context){context=key;manualTime=false;cursor=input.selectionStart||0;}
     if(document.activeElement===input)cursor=input.selectionStart||0;
     let cue=null;try{cue=editorCue();}catch{}
     const valid=!!cue&&!state.exporting&&!composing;
     let parts=null;try{if(valid)parts=C.splitCueAt(cue,cursor);}catch{}
     $('captionSplitCursor').disabled=!valid||!parts;
     $('captionSplitTime').disabled=!valid||!parts;
     $('captionSplitPlayhead').disabled=!valid||!parts;
     if(parts){
       $('captionSplitTime').min=String(cue.start+.1);$('captionSplitTime').max=String(cue.end-.1);
       if(!manualTime)$('captionSplitTime').value=parts[0].end.toFixed(3);
     }else if(!manualTime)$('captionSplitTime').value='';
   }finally{refreshing=false;}
 }
 function rememberCursor(){if(document.activeElement===input)cursor=input.selectionStart||0;refresh();}
 function commit(parts,original){
   if(state.exporting||state.importing)throw Error('현재 작업이 끝난 뒤 자막을 나눠 주세요.');
   const target=selectedText(),index=target?state.texts.indexOf(target):-1;
   const batch=original.captionBatch||uid('caption-split');
   const children=parts.map((part,i)=>({...part,id:i===0&&target?target.id:uid('text'),kind:'caption',captionBatch:batch,captionTiming:'manual-split',captionSplitMode:part.splitTiming,captionParentId:target?.captionParentId||target?.id||batch}));
   pushHistory();
   if(!target){draftRestores.push({entry:state.history.at(-1),ids:children.map(c=>c.id),cue:original});if(draftRestores.length>25)draftRestores.shift();}
   if(index>=0)state.texts.splice(index,1,...children);else state.texts.push(...children);
   const next=children[Math.min(1,children.length-1)];state.selectedTextId=next.id;
   loadTextToEditor(next);renderTextList();renderFrame(state.currentTime,false);v038ScheduleAutosave(100);
   manualTime=false;input.focus({preventScroll:true});input.setSelectionRange(0,0);cursor=0;
   message(`자막 ${children.length}개로 나눴습니다. 시간과 내용을 확인해 주세요.`);toast(`자막 ${children.length}개로 나눴습니다.`);refresh();
 }
 function split(){
   if(composing)return message('글자 입력을 마친 뒤 눌러 주세요.',true);
   try{const cue=editorCue();const options=manualTime?{atTime:$('captionSplitTime').value===''?NaN:Number($('captionSplitTime').value)}:{};
     const parts=C.splitCueAt(cue,cursor,options);commit(parts,cue);
   }catch(error){message(error.message,true);}
 }
 for(const id of ['captionSplitCursor','captionSplitPlayhead','captionSplitTime'])$(id).addEventListener('pointerdown',()=>{if(document.activeElement===input)cursor=input.selectionStart||0;});
 $('captionSplitCursor').addEventListener('click',split);
 $('captionSplitTime').addEventListener('input',()=>{manualTime=true;});
 $('captionSplitPlayhead').addEventListener('click',()=>{try{const cue=editorCue();C.splitCueAt(cue,cursor,{atTime:state.currentTime});manualTime=true;$('captionSplitTime').value=state.currentTime.toFixed(3);message('현재 재생 위치를 분할 시각으로 지정했습니다. 커서에서 나누기를 눌러 주세요.');}catch(error){message(error.message,true);}});
 input.addEventListener('compositionstart',()=>{composing=true;refresh();});input.addEventListener('compositionend',()=>{composing=false;rememberCursor();});
 for(const event of ['input','select','keyup','pointerup','focus'])input.addEventListener(event,rememberCursor);
 for(const id of ['textStart','textEnd','textKind'])$(id).addEventListener('change',()=>{manualTime=false;refresh();});
 const oldLoad=loadTextToEditor;loadTextToEditor=function(){const result=oldLoad.apply(this,arguments);manualTime=false;cursor=input.selectionStart||0;refresh();return result;};
 const oldReset=resetTextEditor;resetTextEditor=function(){const result=oldReset.apply(this,arguments);manualTime=false;cursor=0;refresh();return result;};
 const oldOpen=openSheet;openSheet=function(id){const result=oldOpen.apply(this,arguments);if(id==='textSheet')refresh();return result;};
 const oldUndo=undo;undo=function(){
   const entry=state.history.at(-1),record=draftRestores.findLast(r=>r.entry===entry&&r.ids.every(id=>state.texts.some(t=>t.id===id)));
   const result=oldUndo.apply(this,arguments);
   if(record){const original={...record.cue,id:uid('draft-restored')};loadTextToEditor(original);state.selectedTextId=null;renderTextList();draftRestores.splice(draftRestores.indexOf(record),1);}
   manualTime=false;refresh();v038ScheduleAutosave(100);return result;
 };
 refresh();
})();

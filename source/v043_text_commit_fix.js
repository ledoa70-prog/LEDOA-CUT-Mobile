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
      bgOpacity:clamp((+$('textBgOpacity')?.value||100)/100,0,1),
      styleMode:$('shortsStyleEnabled')?.checked?'shorts':'caption',
      shortsPreset:v037TextPreset||'premium'
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
      const id=btn.dataset.style||'premium';
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
        t.shortsPreset=v037TextPreset||t.shortsPreset||'premium';
      }
      renderFrame(state.currentTime,false);
    },true);
  }
})();

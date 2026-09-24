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

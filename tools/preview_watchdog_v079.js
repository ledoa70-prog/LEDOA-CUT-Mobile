'use strict';
// v0.7.9 preview-only decoder watchdog.
// Do not run extra timers or interfere with the existing export engine.
(function installPreviewWatchdogV079(){
  if(window.__LEDOA_PREVIEW_WATCHDOG_V079__)return;
  window.__LEDOA_PREVIEW_WATCHDOG_V079__=true;
  const basePlayLoop=playLoop,byElement=new WeakMap();
  let previousClipId=null,lastPoll=0;
  function frames(v){
    try{const q=v.getVideoPlaybackQuality?.();if(Number.isFinite(q?.totalVideoFrames))return q.totalVideoFrames;}catch{}
    const n=Number(v.webkitDecodedFrameCount);
    return Number.isFinite(n)&&n>0?n:null;
  }
  function tryPlay(v,w,now){
    if(!v||!v.paused||now-w.lastPlay<480)return;
    w.lastPlay=now;
    try{v.play()?.catch?.(()=>{});}catch{}
  }
  function observe(v,c,now){
    const rv=typeof v062ObserveVideoFrame==='function'?v062ObserveVideoFrame(v):null;
    return {clipId:c.id,lastTime:Number(v.currentTime)||0,lastClock:now,lastFrames:frames(v),
      lastSerial:rv?.serial??null,lastDecoded:now,lastPlay:0,lastNudge:0,lastRestart:0,
      restarts:0,lastFallback:0,failed:false};
  }
  function restart(c,v,w,target,now){
    // Keep the same media element to preserve its existing WebAudio connection.
    try{v.pause();}catch{}
    try{if(typeof v062InvalidateVideoFrame==='function')v062InvalidateVideoFrame(v,null,false);}catch{}
    try{v.removeAttribute('src');v.load();}catch{}
    const resume=()=>{
      try{v.currentTime=target;}catch{}
      try{v.playbackRate=Math.max(.5,Math.min(2,Number(c.speed)||1));}catch{}
      tryPlay(v,w,performance.now());
    };
    try{
      v.src=c.url;v.preload='auto';v.playsInline=true;
      v.setAttribute('playsinline','');v.setAttribute('webkit-playsinline','');
      v.load();
      if(v.readyState>=1)resume();
      else v.addEventListener('loadedmetadata',resume,{once:true});
      if(c.rt){c.rt.synced=false;c.rt.preloaded=false;}
      delete v.dataset.ledoaUnloaded;
    }catch(e){console.warn('LEDOA preview decoder restart',e);}
    w.lastRestart=now;w.restarts++;w.lastClock=now;w.lastDecoded=now;
    w.lastTime=Number(v.currentTime)||0;w.lastFrames=frames(v);
  }
  function poll(now){
    if(!state.playing||state.exporting||document.hidden)return;
    const g=state.currentTime,c=activeInfo(g)?.cur;
    if(!c||c.type!=='video'||c.placeholder){previousClipId=null;return;}
    const v=ensureRuntime(c)?.el;if(!v)return;
    const isNew=previousClipId!==c.id;previousClipId=c.id;
    let w=byElement.get(v);
    if(!w||w.clipId!==c.id||isNew){w=observe(v,c,now);byElement.set(v,w);}
    const ct=Number.isFinite(v.currentTime)?v.currentTime:0,target=mediaLocalSourceTime(c,g);
    const drift=Math.abs(ct-target),f=frames(v);
    const rv=typeof v062ObserveVideoFrame==='function'?v062ObserveVideoFrame(v):null;
    const serial=rv?.serial??null;
    const clockAdvanced=Math.abs(ct-w.lastTime)>.022;
    if(clockAdvanced){w.lastClock=now;w.lastTime=ct;}
    if((f!=null&&w.lastFrames!=null&&f>w.lastFrames)
      ||(serial!=null&&w.lastSerial!=null&&serial>w.lastSerial))w.lastDecoded=now;
    w.lastFrames=f;w.lastSerial=serial;
    if(v.paused)tryPlay(v,w,now);
    // If Chromium's video-frame callback is stuck but media time advances,
    // invalidate stale canvas-layer serial so the preview can draw again.
    if(rv&&clockAdvanced&&now-w.lastDecoded>620&&!v.seeking&&v.readyState>=2
      &&drift<.7&&now-w.lastFallback>180){
      rv.ready=true;rv.mediaTime=ct;rv.serial++;w.lastFallback=now;
    }
    if(v.seeking)return;
    const clockStalled=now-w.lastClock>700;
    const frameStalled=(f!=null||serial!=null)&&now-w.lastDecoded>950;
    const noSource=!v.getAttribute('src')||v.readyState<1;
    if(!clockStalled&&!frameStalled&&!noSource){
      if(drift>1.15&&now-w.lastNudge>850){w.lastNudge=now;try{v.currentTime=target;}catch{}}
      return;
    }
    // Master timeline must not run seconds past the actual picture.
    state.playStartTime=state.currentTime;state.playStartPerf=now;
    if(now-w.lastNudge>750){
      w.lastNudge=now;
      if(noSource&&c.url){try{v.src=c.url;v.preload='auto';v.load();}catch{}}
      else try{v.currentTime=target;}catch{}
      tryPlay(v,w,now);
    }
    if(Math.max(now-w.lastClock,now-w.lastDecoded)>1800
       &&now-w.lastRestart>2400&&w.restarts<3)restart(c,v,w,target,now);
    if(w.restarts>=3&&now-w.lastClock>3800&&now-w.lastDecoded>3800&&!w.failed){
      w.failed=true;
      try{pause();}catch{state.playing=false;}
      toast('영상 미리보기 복구 실패 · 원본 영상을 확인해 주세요.');
    }
  }
  // Plug into existing RAF loop; no parallel playback loop or media timer.
  playLoop=function(now){
    if(state.playing&&!state.exporting&&now-lastPoll>=110){
      lastPoll=now;try{poll(now);}catch(e){console.warn('LEDOA preview watchdog',e);}
    }
    return basePlayLoop(now);
  };
  window.addEventListener('pagehide',()=>{previousClipId=null;});
})();
import sys
p=sys.argv[1]
s=open(p,encoding="utf-8").read()
def rf(src,sig,new):
 a=src.find(sig)
 if a<0: raise SystemExit("missing "+sig)
 b=src.find("{",a); d=0; q=None; esc=False
 for i in range(b,len(src)):
  ch=src[i]
  if q:
   if esc: esc=False
   elif ch=="\\": esc=True
   elif ch==q:q=None
  else:
   if ch in ("'",'"',"`"):q=ch
   elif ch=="{":d+=1
   elif ch=="}":
    d-=1
    if d==0:return src[:a]+new+src[i+1:]
 raise SystemExit("unterminated "+sig)

s=rf(s,"function beginSelection()",r"""async function beginSelection(){
 const clip=currentVideoClip();if(!clip)return toast('먼저 영상 클립을 선택하세요.');if(faceTracking)return;
 pause();if(state.currentTime<clip.start||state.currentTime>clip.end)seek(Math.min(clip.end-.03,clip.start+.03));
 const v=ensureRuntime(clip)?.el,prog=$('faceMaskProgress061');if(!v?.videoWidth)return toast('영상 프레임 준비 중');
 if(prog)prog.textContent='현재 화면의 얼굴을 모두 찾는 중…';
 const det=await getDetector(),ds=det?await detect(det,v,{}):[];
 if(!ds.length){faceSelecting=null;renderFrame(state.currentTime,false);if(prog)prog.textContent='현재 화면에서 추적 가능한 얼굴을 찾지 못했습니다.';return toast('얼굴을 찾지 못했습니다.');}
 const sourceTime=mediaLocalSourceTime(clip,state.currentTime),bounds=clipSourceBounds(clip);
 faceSelecting={clipId:clip.id,sourceTime,bounds,candidates:ds.map((d,i)=>({id:'cand'+i,d,selected:false,maskId:null})),maskIds:[],dragging:false};
 renderFrame(state.currentTime,false);if(prog)prog.textContent=`${ds.length}개 얼굴 감지 · 모자이크할 얼굴 박스를 체크한 뒤 [2. 선택 얼굴 추적]을 누르세요.`;
}""")

s=rf(s,"function drawSelectionGuide()",r"""function drawSelectionGuide(){
 if(!faceSelecting?.candidates?.length)return;const clip=state.clips.find(c=>c.id===faceSelecting.clipId),v=clip&&ensureRuntime(clip)?.el,sw=v?.videoWidth||0,sh=v?.videoHeight||0;if(!sw||!sh)return;
 const sc=Math.min(canvas.width/sw,canvas.height/sh),dw=sw*sc,dh=sh*sc,ox=(canvas.width-dw)/2,oy=(canvas.height-dh)/2;
 ctx.save();ctx.font=`700 ${Math.max(20,canvas.width*.032)}px system-ui`;ctx.textAlign='center';ctx.textBaseline='middle';
 faceSelecting.candidates.forEach((c,i)=>{const d=c.d,r={x:ox+d.x*dw,y:oy+d.y*dh,w:d.w*dw,h:d.h*dh};c.canvasRect=r;ctx.lineWidth=Math.max(3,canvas.width*.005);ctx.strokeStyle=c.selected?'#39e58c':'#ffd36e';ctx.fillStyle=c.selected?'rgba(57,229,140,.16)':'rgba(255,211,110,.08)';ctx.fillRect(r.x,r.y,r.w,r.h);ctx.strokeRect(r.x,r.y,r.w,r.h);ctx.fillStyle=c.selected?'#39e58c':'#222';ctx.beginPath();ctx.arc(r.x+r.w-12,r.y+12,18,0,Math.PI*2);ctx.fill();ctx.fillStyle='#fff';ctx.fillText(c.selected?'✓':String(i+1),r.x+r.w-12,r.y+13);});ctx.restore();
}""")

s=rf(s,"function ptrStart(e)",r"""function ptrStart(e){if(!faceSelecting?.candidates?.length)return;const p=canvasPoint(e);e.preventDefault();e.stopImmediatePropagation();faceSelecting.dragging=true;faceSelecting.start=p;try{canvas.setPointerCapture(e.pointerId);}catch{}}""")
s=rf(s,"function ptrMove(e)",r"""function ptrMove(e){if(faceSelecting?.dragging){e.preventDefault();e.stopImmediatePropagation();}}""")
s=rf(s,"async function ptrEnd(e)",r"""async function ptrEnd(e){
 if(!faceSelecting?.dragging)return;e.preventDefault();e.stopImmediatePropagation();try{canvas.releasePointerCapture(e.pointerId);}catch{}faceSelecting.dragging=false;
 const p=canvasPoint(e),sel=faceSelecting,clip=state.clips.find(c=>c.id===sel.clipId);let hit=null,best=1e9;
 for(const c of sel.candidates){const r=c.canvasRect;if(!r)continue;const inside=p.x>=r.x&&p.x<=r.x+r.w&&p.y>=r.y&&p.y<=r.y+r.h,dist=Math.hypot(p.x-r.x-r.w/2,p.y-r.y-r.h/2);if(inside&&dist<best){best=dist;hit=c;}}
 if(!hit)return renderFrame(state.currentTime,false);hit.selected=!hit.selected;
 if(hit.selected){const d=hit.d,nr={x:d.x,y:d.y,w:d.w,h:d.h},a={sourceTime:sel.sourceTime,...nr,confidence:d.confidence||1,detected:true},m={id:uid('face'),clipId:clip.id,mode:'mosaic',strength:18,padding:.10,sourceStart:sel.bounds.start,sourceEnd:sel.bounds.end,identityAnchor:{...a},keyframes:[a],tracked:false,trackerVersion:68,detectorOnly:true};state.faceMasks.push(m);hit.maskId=m.id;sel.maskIds.push(m.id);state.selectedFaceMaskId=m.id;}
 else if(hit.maskId){state.faceMasks=state.faceMasks.filter(m=>m.id!==hit.maskId);sel.maskIds=sel.maskIds.filter(id=>id!==hit.maskId);hit.maskId=null;}
 syncUi();renderFrame(state.currentTime,false);const n=sel.candidates.filter(x=>x.selected).length,prog=$('faceMaskProgress061');if(prog)prog.textContent=`${sel.candidates.length}개 중 ${n}개 체크 · 더 고르거나 추적을 누르세요.`;
}""")

old="function renderFrame(g,forPlay=false){g=clamp(g,0,totalDuration()||0);state.currentTime=g;ctx.fillStyle='#000';ctx.fillRect(0,0,canvas.width,canvas.height);if(state.clips.length){prepareMedia(g,forPlay);const ai=activeInfo(g);if(ai?.trans)drawTransition(ctx,ai.trans);else if(ai?.cur)drawClip(ai.cur,ctx,1);drawOverlays(ctx,g,forPlay);drawTexts(ctx,g);} updateTime();updatePlayhead();}"
new="function renderFrame(g,forPlay=false){g=clamp(g,0,totalDuration()||0);state.currentTime=g;ctx.fillStyle='#000';ctx.fillRect(0,0,canvas.width,canvas.height);if(state.clips.length){prepareMedia(g,forPlay);const ai=activeInfo(g);if(ai?.trans)drawTransition(ctx,ai.trans);else if(ai?.cur)drawClip(ai.cur,ctx,1);drawOverlays(ctx,g,forPlay);drawTexts(ctx,g);if(faceSelecting?.candidates?.length)drawSelectionGuide();} updateTime();updatePlayhead();}"
if old not in s:raise SystemExit("renderFrame missing")
s=s.replace(old,new,1)
open(p,"w",encoding="utf-8").write(s)

s=open(p,encoding="utf-8").read()
s=rf(s,"async function runTracking()",r"""async function runTracking(){
 if(faceTracking){faceCancel=true;return;}
 let masks=faceSelecting?.maskIds?.map(id=>state.faceMasks.find(m=>m.id===id)).filter(Boolean)||[];if(!masks.length){const m=selectedMask();if(m)masks=[m];}
 const clip=masks[0]&&state.clips.find(c=>c.id===masks[0].clipId);if(!masks.length||!clip)return toast('먼저 얼굴 박스를 하나 이상 체크하세요.');
 pause();faceTracking=true;faceCancel=false;syncUi();const prog=$('faceMaskProgress061');let tv;
 try{
  const det=await getDetector();if(!det)throw new Error('얼굴 감지 엔진 오류');tv=await trackVideo(clip);const bounds={start:+clip.trimStart||0,end:+clip.trimEnd||+clip.sourceDuration||0};
  for(let mi=0;mi<masks.length;mi++){
   if(faceCancel)break;const mask=masks[mi],a=mask.identityAnchor||mask.keyframes?.[0];if(!a)continue;await seekTrack(tv,a.sourceTime);
   const base=sanitizeFaceBox064(a,null),span=Math.max(.01,bounds.end-bounds.start),step=Math.max(.066,Math.min(.10,span/900)),res=[{sourceTime:a.sourceTime,...base,confidence:1}];
   mask.sourceStart=bounds.start;mask.sourceEnd=bounds.end;
   async function pass(dir,limit){
    let prev={...base},good={...base},vel={x:0,y:0},lost=0,t=a.sourceTime+dir*step;
    while(dir>0?t<=limit+.001:t>=limit-.001){
     if(faceCancel)return;await seekTrack(tv,t);const pred={x:clamp(prev.x+vel.x,0,1-prev.w),y:clamp(prev.y+vel.y,0,1-prev.h),w:prev.w,h:prev.h};
     const ds=await detect(det,tv,{rect:pred,lost});let cand=chooseDetectedFace064(ds,prev,pred,base,lost);
     if(cand){const clean=sanitizeFaceBox064(cand,prev),jump=Math.hypot(clean.x+clean.w/2-prev.x-prev.w/2,clean.y+clean.h/2-prev.y-prev.h/2);if(jump>.22+Math.min(.14,lost*.02))cand=null;}
     if(cand){const next=smooth(prev,sanitizeFaceBox064(cand,prev),.70),nx=next.x+next.w/2-prev.x-prev.w/2,ny=next.y+next.h/2-prev.y-prev.h/2;vel={x:vel.x*.35+nx*.65,y:vel.y*.35+ny*.65};prev=next;good={...prev};lost=0;res.push({sourceTime:clamp(t,bounds.start,bounds.end),...prev,confidence:cand.confidence||.7});}
     else{lost++;vel={x:vel.x*.45,y:vel.y*.45};prev={...good};res.push({sourceTime:clamp(t,bounds.start,bounds.end),...prev,confidence:.12});}
     if(prog)prog.textContent=`얼굴 ${mi+1}/${masks.length} · ${dir>0?'뒤쪽':'앞쪽'} 추적 중…`;t+=dir*step;
    }
   }
   await pass(1,bounds.end);if(!faceCancel)await pass(-1,bounds.start);res.sort((x,y)=>x.sourceTime-y.sourceTime);mask.keyframes=res;mask.tracked=true;mask.trackerVersion=68;mask.identityLock=true;
  }
  if(faceCancel)return toast('얼굴 추적을 취소했습니다.');faceSelecting=null;syncUi();renderFrame(state.currentTime,false);v038ScheduleAutosave?.(100);if(prog)prog.textContent=`완료 · 선택 얼굴 ${masks.length}명 · 클립 전체 추적`;toast(`얼굴 ${masks.length}명 전체 추적 완료`);
 }catch(e){console.error('v068',e);if(prog)prog.textContent='추적 실패 · '+(e?.message||e);toast('얼굴 추적 실패');}
 finally{try{tv?.pause();tv?.removeAttribute('src');tv?.load();}catch{}faceTracking=false;faceCancel=false;syncUi();}
}""")
open(p,"w",encoding="utf-8").write(s)

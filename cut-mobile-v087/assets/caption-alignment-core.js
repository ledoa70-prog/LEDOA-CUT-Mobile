(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.LedoaAlignmentCore=api;})(typeof globalThis!=='undefined'?globalThis:this,function(){
 'use strict';
 function alignRows(rows,result,anchor){
  if(!Array.isArray(rows)||!rows.length||!Array.isArray(result.words)||!result.words.length)throw Error('정렬된 단어가 없습니다. 기존 자막은 유지했습니다.');
  const at=t=>anchor.clipStart+(t-anchor.trimStart)/anchor.speed;
  if(!Number.isFinite(anchor.speed)||anchor.speed<=0)throw Error('영상 재생 속도를 확인해 주세요.');
  const groups=rows.map(()=>[]);let previous=-Infinity,previousCue=-1;
  for(const word of result.words){
   if(!Number.isInteger(word.cue)||word.cue<0||word.cue>=rows.length||word.cue<previousCue||!Number.isFinite(word.start)||!Number.isFinite(word.end)||word.start<anchor.sourceStart-.025||word.end>anchor.sourceEnd+.025||word.end<=word.start||word.start<previous-.001)throw Error('정렬 결과의 시간 순서가 올바르지 않습니다. 기존 자막은 유지했습니다.');
   previous=word.end;previousCue=word.cue;groups[word.cue].push(word);
  }
  const output=rows.map((row,i)=>{
   const words=groups[i];if(!words.length)throw Error(`${i+1}번 문단을 정렬하지 못했습니다. 기존 자막은 유지했습니다.`);
   const start=Math.max(anchor.sourceStart,words[0].start-.04),end=Math.min(anchor.sourceEnd,words.at(-1).end+.08);
   return {...row,text:String(row.text),start:at(start),end:at(end),sourceStart:start,sourceEnd:end,timing:'forced-aligned',alignmentReview:words.some(w=>w.review),wordTimings:words.map(w=>({...w,start:at(w.start),end:at(w.end)}))};
  });
  for(let i=0;i<output.length-1;i++)if(output[i].end>output[i+1].start){const boundary=(at(groups[i].at(-1).end)+at(groups[i+1][0].start))/2;output[i].end=boundary;output[i+1].start=boundary;}
  for(const row of output)if(!Number.isFinite(row.start)||!Number.isFinite(row.end)||row.start<0||row.end<=row.start)throw Error('문단 시간을 확인하지 못했습니다. 기존 자막은 유지했습니다.');
  return output;
 }
 return {alignRows};
});

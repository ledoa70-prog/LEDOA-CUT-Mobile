(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.LedoaCaptionCore=api;})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const finite=n=>Number.isFinite(Number(n));
  const chars=s=>Array.from(s).length;
  function at(sourceTime,anchor){return anchor.clipStart+(sourceTime-anchor.trimStart)/anchor.speed;}
  function join(parts){return parts.join(' ').replace(/\s+([,.!?。！？、:;])/g,'$1').trim();}
  function sourceCues(result,anchor){
    if(!finite(anchor.speed)||anchor.speed<=0)throw Error('재생 속도가 올바르지 않습니다.');
    const duration=result.sourceEnd-result.sourceStart;
    if(!(duration>0&&duration<=35.01))throw Error('인식 구간이 올바르지 않습니다.');
    const segments=result.segments||[];const words=[];let last=-1,valid=true;
    for(const segment of segments){
      if(!Array.isArray(segment.words)||!segment.words.length){valid=false;break;}
      for(const w of segment.words){const start=Number(w.start),text=String(w.text||'').trim();if(!text)continue;
        if(!finite(start)||start<0||start>duration+.25||start<last-.05){valid=false;break;}
        words.push({text,start:Math.max(0,Math.min(duration,start))});last=start;
      }
    }
    if(words.length>1&&words.at(-1).start===0)valid=false;
    if(!valid||!words.length){
      const text=segments.map(s=>String(s.text||'').trim()).filter(Boolean).join(' ');
      return text?[{text,start:at(result.sourceStart,anchor),end:at(result.sourceEnd,anchor),sourceStart:result.sourceStart,sourceEnd:result.sourceEnd,timing:'range'}]:[];
    }
    const cues=[];let group=[];
    function flush(next){if(!group.length)return;const first=group[0],last=group.at(-1);
      const end=Math.min(duration,Math.max(last.start+.18,Math.min(next??duration,last.start+.7)));
      const startSource=result.sourceStart+first.start,endSource=result.sourceStart+end;
      if(endSource>startSource)cues.push({text:join(group.map(w=>w.text)),start:at(startSource,anchor),end:at(endSource,anchor),sourceStart:startSource,sourceEnd:endSource,timing:'word-start'});
      group=[];
    }
    for(const w of words){const previous=group.at(-1);
      if(group.length&&(chars(join([...group.map(x=>x.text),w.text]))>20||w.start-group[0].start>3.8||w.start-previous.start>.85))flush(w.start);
      group.push(w);if(/[.!?。！？]$/.test(w.text))flush();
    }
    flush();
    for(let i=0;i<cues.length-1;i++)if(cues[i].end>cues[i+1].start){cues[i].end=cues[i+1].start;cues[i].sourceEnd=anchor.trimStart+(cues[i].end-anchor.clipStart)*anchor.speed;}
    return cues.filter(c=>c.end>c.start);
  }
  function parseClock(text){const m=String(text).trim().match(/^(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})$/);if(!m||+m[2]>59||+m[3]>59)throw Error('자막 시간 형식을 확인해 주세요.');return +m[1]*3600+ +m[2]*60+ +m[3]+Number(m[4].padEnd(3,'0'))/1000;}
  function parseSrt(text){
    if(typeof text!=='string'||text.length>2000000)throw Error('자막 파일은 2MB 이하로 선택해 주세요.');
    const blocks=text.replace(/^\uFEFF/,'').replace(/\r\n?/g,'\n').trim().split(/\n[ \t]*\n+/);const cues=[];
    for(const block of blocks){if(!block.trim())continue;const lines=block.split('\n');if(/^\d+$/.test(lines[0].trim()))lines.shift();
      const times=(lines.shift()||'').split(/\s*-->\s*/);if(times.length!==2)throw Error('올바른 SRT 자막 파일을 선택해 주세요.');
      const start=parseClock(times[0]),end=parseClock(times[1].split(/\s+/)[0]);const value=lines.join('\n').trim();
      if(!(end>start)||!value)throw Error('비어 있거나 종료 시간이 잘못된 자막이 있습니다.');
      cues.push({text:value,start,end,timing:'srt'});if(cues.length>5000)throw Error('한 번에 5,000개 이하의 자막만 불러올 수 있습니다.');
    }
    if(!cues.length)throw Error('자막을 찾지 못했습니다.');return cues.sort((a,b)=>a.start-b.start);
  }
  function clock(value){let t=Math.round(Math.max(0,Number(value)||0)*1000);const ms=t%1000;t=Math.floor(t/1000);const sec=t%60;t=Math.floor(t/60);const min=t%60;const hour=Math.floor(t/60);return [hour,min,sec].map(n=>String(n).padStart(2,'0')).join(':')+','+String(ms).padStart(3,'0');}
  function toSrt(cues){return cues.filter(c=>c.kind!=='title'&&String(c.text||'').trim()&&finite(c.start)&&finite(c.end)&&c.end>c.start).slice().sort((a,b)=>a.start-b.start).map((c,i)=>`${i+1}\n${clock(c.start)} --> ${clock(c.end)}\n${String(c.text).trim()}\n`).join('\n');}
  const MIN_SPLIT_SECONDS=.1;
  let segmenter=null;try{segmenter=new Intl.Segmenter('ko',{granularity:'grapheme'});}catch{}
  function weight(text){const value=String(text).replace(/\s+/gu,'');return Math.max(1,segmenter?Array.from(segmenter.segment(value)).length:Array.from(value).length);}
  function safeCursor(text,cursor){
    const value=Math.max(0,Math.min(text.length,Math.trunc(Number(cursor)||0)));let last=0;
    if(segmenter){for(const item of segmenter.segment(text)){if(item.index>value)break;last=item.index;}return value===text.length?value:last;}
    let offset=0;for(const char of text){if(offset+char.length>value)break;offset+=char.length;}return offset;
  }
  function partitionCue(cue,parts,options={}){
    const start=Number(cue.start),end=Number(cue.end),span=end-start;
    if(!Number.isFinite(start)||!Number.isFinite(end)||start<0||span<=0)throw Error('자막의 시작·끝 시간을 먼저 확인해 주세요.');
    if(parts.length<2)throw Error('문장 사이에 엔터를 넣어 두 문단 이상으로 나눠 주세요.');
    if(parts.length>200)throw Error('한 번에 200개 이하의 문단으로 나눠 주세요.');
    if(span+1e-8<parts.length*MIN_SPLIT_SECONDS)throw Error('자막 구간이 너무 짧습니다. 시작·끝 시간을 늘려 주세요.');
    const weights=parts.map(weight),total=weights.reduce((a,b)=>a+b,0);let sum=0,previous=start;
    const result=[];
    for(let i=0;i<parts.length;i++){
      sum+=weights[i];let boundary=end;
      if(i<parts.length-1){
        boundary=start+span*sum/total;
        if(parts.length===2&&options.atTime!==undefined){boundary=Number(options.atTime);if(!Number.isFinite(boundary)||boundary<start+MIN_SPLIT_SECONDS-1e-8||boundary>end-MIN_SPLIT_SECONDS+1e-8)throw Error('분할 시각은 자막 시작과 끝 사이에 있어야 합니다. 양쪽에 0.1초 이상 남겨 주세요.');}
        boundary=Math.round(boundary*1000)/1000;
        boundary=Math.max(previous+MIN_SPLIT_SECONDS,Math.min(end-(parts.length-i-1)*MIN_SPLIT_SECONDS,boundary));
      }
      const part={...cue,text:parts[i],start:previous,end:boundary,timing:'manual-split',splitTiming:options.atTime!==undefined?'chosen-time':'text-length'};
      if(Number.isFinite(cue.sourceStart)&&Number.isFinite(cue.sourceEnd)){
        const scale=(cue.sourceEnd-cue.sourceStart)/span;
        part.sourceStart=cue.sourceStart+(previous-start)*scale;part.sourceEnd=cue.sourceStart+(boundary-start)*scale;
      }
      result.push(part);previous=boundary;
    }
    return result;
  }
  function splitCueAt(cue,cursor,options={}){
    const text=String(cue.text||''),point=safeCursor(text,cursor);const left=text.slice(0,point).trim(),right=text.slice(point).trim();
    if(!left||!right)throw Error('나눌 문장 사이에 커서를 놓아 주세요.');
    return partitionCue(cue,[left,right],options);
  }
  function splitCueByLines(cue){
    const parts=String(cue.text||'').replace(/\r\n?/g,'\n').split(/\n+/).map(s=>s.trim()).filter(Boolean);
    return partitionCue(cue,parts);
  }
  return {sourceCues,parseSrt,toSrt,clock,at,splitCueAt,splitCueByLines};
});

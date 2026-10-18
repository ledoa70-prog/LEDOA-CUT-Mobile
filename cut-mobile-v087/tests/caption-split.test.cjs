const assert=require('assert/strict'),C=require('../assets/caption-core.js');
const source={id:'original',text:'첫 번째 문장\n두 번째 문장\n마지막 인사',start:1.2,end:19.794,font:'Nanum Pen Script',size:64,color:'#ffffff',bgOpacity:0,customY:.62,sourceStart:3,sourceEnd:40.188};
const original=JSON.stringify(source);const lines=C.splitCueByLines(source);
assert.equal(lines.length,3);assert.equal(lines[0].start,source.start);assert.equal(lines.at(-1).end,source.end);
for(let i=0;i<lines.length;i++){
 assert.ok(lines[i].end-lines[i].start>=.1-1e-8);assert.equal(lines[i].bgOpacity,0);assert.equal(lines[i].font,source.font);assert.equal(lines[i].customY,.62);
 if(i){assert.equal(lines[i].start,lines[i-1].end);assert.equal(lines[i].sourceStart,lines[i-1].sourceEnd);}
}
assert.equal(JSON.stringify(source),original,'split must not mutate source');
assert.equal(lines.map(c=>c.text).join(''),source.text.replace(/\n/g,''));
const cursor=C.splitCueAt({...source,text:'앞 문장 뒤 문장'},4,{atTime:8.125});assert.equal(cursor[0].end,8.125);assert.equal(cursor[1].start,8.125);assert.equal(cursor[0].text,'앞 문장');assert.equal(cursor[1].text,'뒤 문장');
assert.throws(()=>C.splitCueAt(source,0));assert.throws(()=>C.splitCueAt(source,source.text.length));
assert.throws(()=>C.splitCueAt(source,4,{atTime:source.start}));assert.throws(()=>C.splitCueAt(source,4,{atTime:NaN}));
assert.throws(()=>C.splitCueByLines({...source,text:'\n한 문단\n\n'}));
assert.throws(()=>C.splitCueByLines({...source,text:'가\n나\n다',start:0,end:.2}));
const tiny=C.splitCueByLines({...source,text:'가\n나\n다',start:0,end:.3});assert.equal(tiny.length,3);assert.equal(tiny.at(-1).end,.3);
const unbalanced=C.splitCueByLines({...source,text:'가'.repeat(1000)+'\n나\n다',start:0,end:1});assert.ok(unbalanced.every(c=>c.end-c.start>=.1-1e-8));
const emoji='가👨‍👩‍👧‍👦나';const unicode=C.splitCueAt({...source,text:emoji},4);assert.equal(unicode.map(c=>c.text).join(''),emoji);assert.equal(unicode[1].text,'👨‍👩‍👧‍👦나');
const crlf=C.splitCueByLines({...source,text:'  첫 줄\r\n\r\n 둘째 줄 \r\n'});assert.equal(crlf.length,2);assert.deepEqual(crlf.map(c=>c.text),['첫 줄','둘째 줄']);
assert.equal(C.parseSrt(C.toSrt(lines)).length,3);
console.log('PASS multiline and cursor split, chosen timing, style/opacity, Unicode, short ranges, SRT and source preservation');

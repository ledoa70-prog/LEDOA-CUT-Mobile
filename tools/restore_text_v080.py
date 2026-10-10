#!/usr/bin/env python3
"""Restore v0.6.4 Korean font and zero-opacity semantics on v0.7.9 source.
Intentionally avoid touching any video decoder, media, timeline or export logic.
"""
import pathlib,re,sys
app=pathlib.Path(sys.argv[1]); css=pathlib.Path(sys.argv[2]); html=pathlib.Path(sys.argv[3])
s=app.read_text(encoding="utf-8"); style=css.read_text(encoding="utf-8")
assert "__LEDOA_PREVIEW_WATCHDOG_V079__" in s, "Preview watchdog must remain in place"
pattern=r"\(\+\$\('textBgOpacity'\)\?\.value\|\|(\d+)\)"
s,n=re.subn(pattern,lambda m:"Number($('textBgOpacity')?.value ?? "+m.group(1)+")",s)
assert n>=5, f"expected five or more faulty opacity paths, got {n}"
assert "bgOpacity:clamp((+$('textBgOpacity')" not in s
s=s.replace("글자 배경 투명도 <span id=\"textBgOpacityLabel\">100%</span><input id=\"textBgOpacity\" type=\"range\" min=\"0\" max=\"100\" step=\"1\" value=\"100\">",
'''글자 배경 농도 <span id="textBgOpacityLabel">100%</span><input id="textBgOpacity" type="range" min="0" max="100" step="1" value="100"><small>0%로 설정하면 배경 없이 글자만 표시됩니다.</small>''')
style=style.replace("/* v0.8.0 Korean font restoration */","")
style='''/* v0.8.0 Korean font restoration: original v0.6.4 bundled font rules */
@font-face{font-family:'Gowun Dodum';font-style:normal;font-weight:400;font-display:block;src:url('./fonts/GowunDodum-Regular.ttf') format('truetype')}
@font-face{font-family:'Nanum Pen Script';font-style:normal;font-weight:400;font-display:block;src:url('./fonts/NanumPenScript-Regular.ttf') format('truetype')}
'''+style
s += r'''
// v0.8.0 restored from v0.6.4: preload both locally bundled Korean fonts
// before allowing an export to capture missing-font fallback text.
(function restoreBundledKoreanFontsV080(){
  let promise=null;
  function ready(){
    if(!document.fonts?.load)return Promise.resolve();
    if(!promise)promise=Promise.all(['Gowun Dodum','Nanum Pen Script'].map(async family=>{
      const list=await document.fonts.load('400 52px "'+family+'"','한글 가나다 가나다라마바사');
      if(!list.length||list.some(f=>f.status!=='loaded'))throw Error('Font unavailable: '+family);
    })).catch(e=>{promise=null;throw e;});
    return promise;
  }
  ready().then(()=>{if(!state.playing&&!state.exporting)renderFrame(state.currentTime,false);})
    .catch(e=>{console.warn('LEDOA font loading',e);toast('글꼴 로드 실패 · 앱을 다시 열어주세요.');});
  const oldExport=exportVideo;let pending=false;
  exportVideo=async function(){
    if(state.exporting||pending)return;
    if(!state.clips.length)return oldExport.apply(this,arguments);
    pending=true;
    const btn=$('startExportBtn'),status=$('exportStatus');
    if(btn)btn.disabled=true;
    if(status)status.textContent='한글 글꼴 준비 중';
    try{await ready();return await oldExport.apply(this,arguments);}
    catch(e){console.warn('LEDOA font preparation',e);toast('글꼴을 준비하지 못해 내보내기를 중단했습니다.');}
    finally{pending=false;if(btn&&!state.exporting)btn.disabled=false;}
  };
  if($('startExportBtn'))$('startExportBtn').onclick=exportVideo;
})();
'''
h=html.read_text(encoding="utf-8")
h=h.replace('SHORTS · v0.7.9','SHORTS · v0.8.0')
assert 'SHORTS · v0.8.0' in h
app.write_text(s,encoding="utf-8")
css.write_text(style,encoding="utf-8")
html.write_text(h,encoding="utf-8")
print(f"Restored {n} opacity paths, two font-face rules and font preload/export guard.")

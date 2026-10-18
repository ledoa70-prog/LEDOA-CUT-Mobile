"""Prepare reproducible acoustic test data; input WAVs remain local test fixtures only."""
import argparse, json, math, struct, time
from pathlib import Path
import numpy as np
import onnxruntime as ort
import soundfile as sf

p=argparse.ArgumentParser();p.add_argument('--deps',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
vocab=json.loads((a.deps/'vocab.json').read_text())
options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1;options.enable_cpu_mem_arena=False;options.enable_mem_pattern=False
session=ort.InferenceSession(str(a.deps/'model.android.onnx'),options,providers=['CPUExecutionProvider'])
audio=[];texts=[]
for line in (a.deps/'test-audio/trans.txt').read_text().strip().splitlines():
 name,text=line.split(' ',1);x,rate=sf.read(a.deps/'test-audio'/name,dtype='float32');assert rate==16000
 audio.append(x);texts.append(text)
cases=[(str(i),x,[texts[i]],[(0,len(x)/16000)]) for i,x in enumerate(audio)]
lead=2.;gap=1.5;tail=2.5;second=lead+len(audio[0])/16000+gap
x=np.concatenate([np.zeros(int(lead*16000),np.float32),audio[0],np.zeros(int(gap*16000),np.float32),audio[3],np.zeros(int(tail*16000),np.float32)])
cases.append(('two_paragraphs_silence_and_chunk_boundary',x,[texts[0],texts[3]],[(lead,lead+len(audio[0])/16000),(second,second+len(audio[3])/16000)]))
def infer(x):
 frames=math.ceil(len(x)/320);full=[]
 for begin in range(0,frames,400):
  finish=min(frames,begin+400);left=max(0,begin-40);right=min(len(x),(finish+40)*320)
  part=x[left*320:right].copy();part=(part-part.mean())/np.sqrt(part.var()+1e-7);part=np.pad(part,(0,1600))
  y=session.run(None,{'input_values':part[None]})[0][0];assert finish-left<len(y)-1
  y=y[begin-left:finish-left];y-=y.max(1,keepdims=True);y-=np.log(np.exp(y).sum(1,keepdims=True));full.append(y)
 return np.concatenate(full)
def utf(out,value):
 data=value.encode('utf8');out.write(struct.pack('>H',len(data)));out.write(data)
a.output.parent.mkdir(parents=True,exist_ok=True)
with a.output.open('wb') as out:
 out.write(struct.pack('>i',len(vocab)))
 for k,v in vocab.items():utf(out,k);out.write(struct.pack('>i',v))
 out.write(struct.pack('>i',len(cases)))
 for name,x,rows,bounds in cases:
  began=time.monotonic();y=infer(x);print(name,round(time.monotonic()-began,2),y.shape,flush=True)
  utf(out,name);out.write(struct.pack('>di',len(x)/16000,len(rows)))
  for row,(start,end) in zip(rows,bounds):utf(out,row);out.write(struct.pack('>dd',start,end))
  out.write(struct.pack('>ii',*y.shape));out.write(y.astype('>f4').tobytes())

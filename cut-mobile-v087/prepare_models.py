"""Fetch pinned public models; preserve v0.8.6 Korean alignment byte for byte."""
from pathlib import Path
import hashlib, urllib.request
ROOT = Path(__file__).resolve().parent
def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
    return h.hexdigest()
def fetch(url,path,sha):
    if path.exists() and digest(path)==sha: return
    path.parent.mkdir(parents=True,exist_ok=True)
    part=path.with_suffix('.download')
    with urllib.request.urlopen(url,timeout=120) as src,part.open('wb') as out:
        import shutil
        shutil.copyfileobj(src,out)
    assert digest(part)==sha, 'Pinned model hash mismatch: '+str(path)
    part.replace(path)
def main():
    import json
    for font in json.loads((ROOT/'assets/fonts/SOURCES.json').read_text()):
        fetch(font['source'],ROOT/'assets/fonts'/font['name'],font['sha256'])
    fetch('https://media.githubusercontent.com/media/opencv/opencv_zoo/47534e27c9851bb1128ccc0102f1145e27f23f98/models/face_recognition_sface/face_recognition_sface_2021dec_int8.onnx',ROOT/'assets/sface_int8.onnx','2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a')
    output=ROOT/'assets/alignment/model.onnx'
    expected='0f1aa81911a6dc8fe61ae0c13f4bbebd37145c5ad69c83da24d9256bb4e21623'
    if not output.exists() or digest(output)!=expected:
        raw=ROOT/'build/model.original.onnx'
        fetch('https://huggingface.co/smartkdy7/movie-cut-w2v2-ko-onnx/resolve/096ef0952e7e76eaa971db66010766416a7dbf8e/model.int8.onnx',raw,'e0c2a3da47fd6f20148e05d5440e78298c7b9ed272e2f034be753012d7bcd5a8')
        import onnx, numpy as np
        from onnx import numpy_helper
        model=onnx.load(str(raw)); indexes={v.name:i for i,v in enumerate(model.graph.initializer)}
        names={name for node in model.graph.node if node.op_type=='ConvInteger' for name in [node.input[1],node.input[3]]}
        for name in names:
            index=indexes[name]; tensor=model.graph.initializer[index]; values=numpy_helper.to_array(tensor)
            if values.dtype==np.int8:
                model.graph.initializer[index].CopyFrom(numpy_helper.from_array((values.astype(np.int16)+128).astype(np.uint8),name))
        onnx.save(model,str(output))
        assert digest(output)==expected,'Converted alignment model differs from v0.8.6'
    print('Pinned face and caption alignment model hashes verified')
if __name__=='__main__': main()

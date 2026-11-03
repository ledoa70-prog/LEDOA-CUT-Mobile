"""Build unreleased P0/rollback candidates from the preserved v0.8.11 APK.

Only the manifest and explicitly listed web assets are replaced. The existing
certificate, package, native classes, native libraries and models are retained.
This tool verifies package bytes/signatures, not execution on an Android device.
"""
from pathlib import Path
import argparse,copy,hashlib,json,struct,subprocess,sys,zipfile
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization

parser=argparse.ArgumentParser()
for name in ['base-apk','assets','key','certificate','apksigner','out']:
    parser.add_argument('--'+name,type=Path,required=True)
parser.add_argument('--rollback',action='store_true')
args=parser.parse_args()
sys.path.insert(0,str(args.assets.parent))
from axml_utils import pool_strings,string_pool

FINGERPRINT='bd7dad9ddae23ee1086bdbf5d0b578f45348036456deab7363b75dddd86bf9e8'
BASE_HASH='d1c97b5d7ad3bf082556bf8d2698fb9051492230ccd8597bc65b6eadc8f287a4'
with args.base_apk.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==BASE_HASH
cert=x509.load_pem_x509_certificate(args.certificate.read_bytes())
key=serialization.load_der_private_key(args.key.read_bytes(),None)
assert cert.fingerprint(hashes.SHA256()).hex()==FINGERPRINT
assert key.public_key().public_numbers()==cert.public_key().public_numbers()
version_code=813 if args.rollback else 812
version_name='0.8.11-rollback-813' if args.rollback else '0.8.12-p0-candidate'

def verify(apk):
    result=subprocess.run(['java','-jar',str(args.apksigner),'verify','--verbose','--print-certs',str(apk)],capture_output=True,text=True,check=True)
    assert FINGERPRINT in result.stdout
    return result.stdout

def manifest(data):
    chunks=[];strings=None;offset=8;changed_code=False;changed_name=False
    while offset<len(data):
        kind,header,size=struct.unpack_from('<HHI',data,offset);chunk=bytearray(data[offset:offset+size])
        if kind==1:
            strings=pool_strings(chunk)
            assert 'kr.ledoa.cut.captionbeta' in strings and '0.8.11-viewportfix' in strings
            chunk=string_pool(chunk,{'0.8.11-viewportfix':version_name});changed_name=True
        elif kind==0x102:
            _,name,attrs,attr_size,count,*_=struct.unpack_from('<IIHHHHHH',chunk,header)
            for i in range(count):
                pos=header+attrs+i*attr_size;index=struct.unpack_from('<I',chunk,pos+4)[0]
                if strings[index]=='versionCode':
                    assert struct.unpack_from('<I',chunk,pos+16)[0]==811
                    struct.pack_into('<I',chunk,pos+16,version_code);changed_code=True
        chunks.append(chunk);offset+=size
    assert changed_code and changed_name
    body=b''.join(chunks)
    return struct.pack('<HHI',3,8,len(body)+8)+body

def compressed_bytes(apk_file,info):
    apk_file.seek(info.header_offset+26);n,e=struct.unpack('<HH',apk_file.read(4))
    apk_file.seek(info.header_offset+30+n+e)
    remaining=info.compress_size
    while remaining:
        data=apk_file.read(min(4*1024*1024,remaining));assert data
        remaining-=len(data);yield data

verify(args.base_apk);args.out.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(args.base_apk) as old:
    changes={'AndroidManifest.xml':manifest(old.read('AndroidManifest.xml'))}
    if not args.rollback:
        for name in ['app.js','index.html','sw.js']:changes['assets/'+name]=(args.assets/name).read_bytes()
unsigned=args.out/('unsigned-rollback.apk' if args.rollback else 'unsigned-candidate.apk')
with zipfile.ZipFile(args.base_apk) as source,args.base_apk.open('rb') as raw,zipfile.ZipFile(unsigned,'w') as target:
    for original in source.infolist():
        name=original.filename
        if name.startswith('META-INF/') and name.upper().endswith(('.RSA','.DSA','.EC','.SF','MANIFEST.MF')):continue
        info=copy.copy(original);info.flag_bits&=~8;info.header_offset=target.fp.tell()
        if info.compress_type==zipfile.ZIP_STORED:
            alignment=16384 if name.startswith('lib/') and name.endswith('.so') else 4
            header=info.FileHeader(False);padding=-(info.header_offset+len(header)+4)%alignment
            if (info.header_offset+len(header))%alignment:info.extra+=struct.pack('<HH',0xd935,padding)+b'\0'*padding
        if name in changes:target.writestr(info,changes[name])
        else:
            target._writecheck(info);target._didModify=True;target.fp.write(info.FileHeader(False))
            for data in compressed_bytes(raw,original):target.fp.write(data)
            target.start_dir=target.fp.tell();target.filelist.append(info);target.NameToInfo[name]=info
apk=args.out/('LEDOA_CUT_Mobile_v0.8.11_ROLLBACK_code813.apk' if args.rollback else 'LEDOA_CUT_Mobile_v0.8.12_P0_CANDIDATE.apk')
subprocess.run(['java','-jar',str(args.apksigner),'sign','--key',str(args.key),'--cert',str(args.certificate),'--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','false','--v4-signing-enabled','false','--out',str(apk),str(unsigned)],check=True)
signature=verify(apk);preserved=[]
with zipfile.ZipFile(args.base_apk) as old,zipfile.ZipFile(apk) as new,args.base_apk.open('rb') as old_raw,apk.open('rb') as new_raw:
    assert set(old.namelist())==set(new.namelist())
    for name in old.namelist():
        a,b=old.getinfo(name),new.getinfo(name)
        if name in changes:assert new.read(name)==changes[name]
        else:
            assert (a.CRC,a.file_size,a.compress_size)==(b.CRC,b.file_size,b.compress_size),name
            old_hash=hashlib.sha256();new_hash=hashlib.sha256()
            for data in compressed_bytes(old_raw,a):old_hash.update(data)
            for data in compressed_bytes(new_raw,b):new_hash.update(data)
            assert old_hash.digest()==new_hash.digest(),name
            preserved.append(name)
        if b.compress_type==zipfile.ZIP_STORED:
            new_raw.seek(b.header_offset+26);n,e=struct.unpack('<HH',new_raw.read(4))
            alignment=16384 if name.startswith('lib/') and name.endswith('.so') else 4
            assert (b.header_offset+30+n+e)%alignment==0,name
    assert new.testzip() is None
with apk.open('rb') as f:sha=hashlib.file_digest(f,'sha256').hexdigest()
report={'status':'UNRELEASED_CANDIDATE','releaseApproved':False,'package':'kr.ledoa.cut.captionbeta','versionName':version_name,'versionCode':version_code,'sameSigningCertificate':True,'certificateSha256':FINGERPRINT,'signatureVerified':True,'changedEntries':list(changes),'preservedEntries':len(preserved),'allOtherCompressedEntrySha256Identical':True,'projectSchemaChanged':False,'databaseNameChanged':False,'nativeCodeChanged':False,'androidInstallLaunchTest':False,'nativeProjectRestoreTest':False,'nativeMp4ExportTest':False,'bytes':apk.stat().st_size,'sha256':sha}
(args.out/('rollback-verification.json' if args.rollback else 'candidate-verification.json')).write_text(json.dumps(report,indent=2)+'\n')
(args.out/('rollback-signature.txt' if args.rollback else 'candidate-signature.txt')).write_text(signature)
unsigned.unlink();print(json.dumps(report))

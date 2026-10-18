"""Android installation packaging gates that APK signature checks do not cover."""
from pathlib import Path
import hashlib,struct,zipfile,zlib

def validate_apk(path):
 path=Path(path)
 with zipfile.ZipFile(path) as archive,path.open('rb') as raw:
  assert archive.testzip() is None,'APK ZIP CRC failure'
  assert len(archive.namelist())==len(set(archive.namelist())),'Duplicate APK entries'
  table=archive.getinfo('resources.arsc')
  assert table.compress_type==zipfile.ZIP_STORED,'INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED: resources.arsc must be stored uncompressed'
  raw.seek(table.header_offset);header=raw.read(30);names,extras=struct.unpack_from('<HH',header,26)
  offset=table.header_offset+30+names+extras
  assert offset%4==0,'INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED: resources.arsc is not 4-byte aligned'
  descriptors=set();dex_count=0
  for item in archive.infolist():
   if not item.filename.endswith('.dex'):continue
   data=archive.read(item);dex_count+=1
   assert data[:4]==b'dex\n','Invalid DEX magic'
   assert struct.unpack_from('<I',data,8)[0]==zlib.adler32(data[12:])&0xffffffff,'DEX checksum mismatch'
   assert data[12:32]==hashlib.sha1(data[32:]).digest(),'DEX SHA1 mismatch'
   assert struct.unpack_from('<I',data,32)[0]==len(data),'DEX length mismatch'
   _,string_offset=struct.unpack_from('<II',data,56)
   _,type_offset=struct.unpack_from('<II',data,64)
   count,class_offset=struct.unpack_from('<II',data,96)
   for i in range(count):
    type_id=struct.unpack_from('<I',data,class_offset+i*32)[0]
    string_id=struct.unpack_from('<I',data,type_offset+type_id*4)[0]
    at=struct.unpack_from('<I',data,string_offset+string_id*4)[0]
    while data[at]&128:at+=1
    at+=1;name=data[at:data.index(b'\0',at)].decode('utf8')
    assert name not in descriptors,'Duplicate DEX class '+name
    descriptors.add(name)
  assert 'Lkr/ledoa/cut/CaptionActivity;' in descriptors,'Caption activity class absent'
  assert 'Lkr/ledoa/cut/MainActivity;' in descriptors,'Original editor superclass absent'
 return {'resources_arsc_uncompressed':True,'resources_arsc_4byte_aligned':True,'resources_arsc_data_offset':offset,'dex_integrity_verified':True,'dex_class_count':len(descriptors),'dex_count':dex_count}

if __name__=='__main__':
 import sys,json
 print(json.dumps(validate_apk(sys.argv[1]),indent=2))

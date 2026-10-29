"""Generate a tiny CFA DNG with no camera or personal metadata for host smoke tests."""
import struct
import sys
w,h=96,64
t=[]
def add(tag,typ,v):
 if typ==3: data=struct.pack('<'+'H'*len(v),*v)
 elif typ==4: data=struct.pack('<'+'I'*len(v),*v)
 elif typ in (5,10): data=b''.join(struct.pack('<ii',a,b) for a,b in v)
 else: data=v
 count=len(data)//{1:1,2:1,3:2,4:4,5:8,10:8}[typ];t.append([tag,typ,count,data])
add(256,4,[w]);add(257,4,[h]);add(258,3,[16]);add(259,3,[1]);add(262,3,[32803]);add(271,2,b'Loupe synthetic\0');add(272,2,b'Test CFA\0');add(273,4,[0]);add(274,3,[int(sys.argv[2]) if len(sys.argv)>2 else 1]);add(277,3,[1]);add(278,4,[h]);add(279,4,[w*h*2]);add(284,3,[1]);add(33421,3,[2,2]);add(33422,1,bytes([0,1,1,2]));add(50706,1,bytes([1,4,0,0]));add(50707,1,bytes([1,1,0,0]));add(50708,2,b'Loupe synthetic CFA\0');add(50714,4,[0]);add(50717,4,[65535]);add(50721,10,[(1 if i%4==0 else 0,1) for i in range(9)]);add(50728,5,[(1,1)]*3);add(50778,3,[21])
t.sort();base=8+2+12*len(t)+4;extra=bytearray();entries=[]
for tag,typ,count,data in t:
 if tag==273: entries.append((tag,typ,count,None));continue
 if len(data)<=4: val=data.ljust(4,b'\0')
 else:
  val=struct.pack('<I',base+len(extra));extra+=data
  if len(extra)%2: extra+=b'\0'
 entries.append((tag,typ,count,val))
pixels_offset=base+len(extra)
out=bytearray(b'II*\0'+struct.pack('<I',8)+struct.pack('<H',len(t)))
for tag,typ,count,val in entries:out+=struct.pack('<HHI',tag,typ,count)+(val if val else struct.pack('<I',pixels_offset))
out+=b'\0'*4+extra
for y in range(h):
 for x in range(w):out+=struct.pack('<H',1000+((x*541+y*331)%50000))
with open(sys.argv[1], 'xb') as destination:
 destination.write(out)

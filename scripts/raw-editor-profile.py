import subprocess,time,json,re,statistics,argparse,shlex
from pathlib import Path
parser=argparse.ArgumentParser(description="Profile loaded experimental RAW preview; no photo contents collected")
parser.add_argument('--serial',required=True)
parser.add_argument('--adb',default='/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
parser.add_argument('--output',required=True,type=Path,help='New output directory')
parser.add_argument('--uri-file',type=Path,help='Local file containing the already-authorized content URI; omit for synthetic')
args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=False)
adb=[args.adb,'-s',args.serial]
def shell(*command):return subprocess.run(adb+['shell',shlex.join(command)],capture_output=True,text=True,check=True).stdout
policy=shell('dumpsys','window','policy')
if 'showing=false' not in policy or 'showing=true' in policy:raise SystemExit('Device locked; stop')
shell('input','keyevent','127');shell('am','force-stop','com.google.android.youtube')
shell('am','force-stop','jp.nano.rawgallery.rawexperiment')
if args.uri_file:
 uri=args.uri_file.read_text().strip()
 if not uri.startswith('content://'):raise SystemExit('Expected content URI')
 shell('am','start','-n','jp.nano.rawgallery.rawexperiment/jp.nano.rawgallery.RawEditActivity','-d',uri,'--grant-read-uri-permission')
else:
 shell('am','start','-n','jp.nano.rawgallery.rawexperiment/jp.nano.rawgallery.RawEditActivity','--ez','rawEditSynthetic','true')
time.sleep(3)
shell('uiautomator','dump','/data/local/tmp/loupe53-ui.xml')
xml=shell('cat','/data/local/tmp/loupe53-ui.xml')

# Coordinates derive from UI bounds, not guessed layout.
import xml.etree.ElementTree as ET
nodes=ET.fromstring(xml).iter('node')
texture=next(n for n in nodes if n.attrib.get('text')=='質感')
b=list(map(int,re.findall(r'\d+',texture.attrib['bounds'])))
shell('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(1)
shell('uiautomator','dump','/data/local/tmp/loupe53-ui.xml');xml=shell('cat','/data/local/tmp/loupe53-ui.xml');
plane=next(n for n in ET.fromstring(xml).iter('node') if 'の面。' in n.attrib.get('content-desc',''))
b=list(map(int,re.findall(r'\d+',plane.attrib['bounds'])))
x1=int(b[0]+.2*(b[2]-b[0]));x2=int(b[0]+.8*(b[2]-b[0]));y1=int(b[1]+.7*(b[3]-b[1]));y2=int(b[1]+.2*(b[3]-b[1]))
with (args.output/'events.log').open('w') as log:
 collector=subprocess.Popen(adb+['logcat','-v','brief','-T','1','-s','LoupeRawEdit:I','*:S'],stdout=log)
 try:
  for _ in range(3):
   policy=shell('dumpsys','window','policy')
   if 'showing=false' not in policy or 'showing=true' in policy:raise SystemExit('Device locked; stop')
   shell('input','swipe',str(x1),str(y1),str(x2),str(y2),'1800');time.sleep(.6)
   shell('input','swipe',str(x2),str(y2),str(x1),str(y1),'1800');time.sleep(.6)
  time.sleep(1)
 finally:
  collector.terminate();collector.wait()
logs=(args.output/'events.log').read_text()
inputs={int(a):int(b) for a,b in re.findall(r'adjust.input ticket=(\d+) ns=(\d+)',logs)}
frames=[(int(a),int(b),int(c)) for a,b,c in re.findall(r'adjust.frame ticket=(\d+) input_ns=(\d+) commit_ns=(\d+)',logs)]
lat=sorted((c-b)/1e6 for a,b,c in frames)
inmotion=sum(any(ns>b and ns<c for ns in inputs.values()) for a,b,c in frames)
result={'input_requests':len(inputs),'committed_frames':len(frames),'commits_with_newer_input_during_latency':inmotion,'median_ms':statistics.median(lat) if lat else None,'p95_ms':lat[min(len(lat)-1,int(len(lat)*.95))] if lat else None,'render_count':len(re.findall(r'adjust.render ms=(\d+)',logs))}
groups=[]
for ticket,ns in inputs.items():
 if not groups or ns-groups[-1][-1][1]>100_000_000:groups.append([])
 groups[-1].append((ticket,ns))
result['drags']=[{'input_count':len(g),'duration_ms':(g[-1][1]-g[0][1])/1e6,'frames_while_dragging':sum(g[0][1]<=c<=g[-1][1] for a,b,c in frames),'final_ms':next(((c-g[-1][1])/1e6 for a,b,c in frames if a==g[-1][0]),None)} for g in groups]
result['source']='selected RAW' if args.uri_file else 'synthetic 1200x800'
result['boundary']='input to frame commit; not display scanout'
print(json.dumps(result));(args.output/'summary.json').write_text(json.dumps(result,indent=2))

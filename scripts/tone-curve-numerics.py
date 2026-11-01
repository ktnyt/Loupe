import math,random,json,struct
random.seed(63)
def pchip(points,x):
 n=len(points);h=[points[i+1][0]-points[i][0] for i in range(n-1)];d=[(points[i+1][1]-points[i][1])/h[i] for i in range(n-1)]
 if n==2:m=[d[0],d[0]]
 else:
  m=[0.]*n
  for i in range(1,n-1):
   if d[i-1]*d[i]>0:
    w1=2*h[i]+h[i-1];w2=h[i]+2*h[i-1];m[i]=(w1+w2)/(w1/d[i-1]+w2/d[i])
  def end(a,b,da,db):
   v=((2*a+b)*da-a*db)/(a+b)
   return 0 if v*da<=0 else min(v,3*da)
  m[0]=end(h[0],h[1],d[0],d[1]);m[-1]=end(h[-1],h[-2],d[-1],d[-2])
 if x<=points[0][0]:return points[0][1]
 if x>=points[-1][0]:return points[-1][1]
 i=next(i for i in range(n-1) if x<=points[i+1][0]);t=(x-points[i][0])/h[i]
 return (2*t**3-3*t*t+1)*points[i][1]+(t**3-2*t*t+t)*h[i]*m[i]+(-2*t**3+3*t*t)*points[i+1][1]+(t**3-t*t)*h[i]*m[i+1]
def shoulder(x,r):
 k=1-.3*r
 return min(x,1) if r==0 else x if x<=k else k+(1-k)*(-math.expm1(-(x-k)/(1-k)))
def rgb(l,a,b):
 x=(l+.3963377774*a+.2158037573*b)**3;y=(l-.1055613458*a-.0638541728*b)**3;z=(l-.0894841775*a-1.291485548*b)**3
 return (4.0767416621*x-3.3077115913*y+.2309699292*z,-1.2684380046*x+2.6097574011*y-.3413193965*z,-.0041960863*x-.7034186147*y+1.707614701*z)
def inside(c):return min(c)>=0 and max(c)<=1
worst=0;checks=0
for j in range(64):
 xs=sorted(random.sample(range(129),random.randint(2,16)));ys=sorted(random.random() for _ in xs);points=list(zip([i/128 for i in xs],ys))
 if j==0:points=[(0,0),(1/128,1),(1,1)]
 lut=[pchip(points,i/8192) for i in range(8193)];prev=-1
 for i in range(16385):
  x=i/16384;v=pchip(points,x);assert v>=prev-1e-12;prev=v
  q=x*8192;k=min(8191,int(q));v2=lut[k]+(lut[k+1]-lut[k])*(q-k);worst=max(worst,abs(v-v2));checks+=1
for r in [0,.01,.5,1]:
 vals=[shoulder(i/1000,r) for i in range(32001)];assert all(a<=b for a,b in zip(vals,vals[1:]));assert min(vals)>=0 and max(vals)<=1
max_violation=0;hue_error=0
for i in range(10000):
 l=random.random();a=random.uniform(-1,1);b=random.uniform(-1,1)
 if inside(rgb(l,a,b)):continue
 low=0;high=1
 for _ in range(16):
  t=(low+high)/2
  if inside(rgb(l,a*t,b*t)):low=t
  else:high=t
 c=rgb(l,a*low,b*low);max_violation=max(max_violation,-min(c),max(c)-1)
 if low>0:hue_error=max(hue_error,abs(math.atan2(b*low,a*low)-math.atan2(b,a)))
assert worst<.0003 and max_violation<1e-7
print(json.dumps({'curve_samples':checks,'LUT_samples':8193,'min_x_gap':1/128,'max_LUT_error':worst,'max_error_in_8bit_codes':worst*255,'rolloff_domain':[0,32],'gamut_samples':10000,'gamut_iterations':16,'max_out_of_gamut':max_violation,'max_hue_angle_error_radians':hue_error},indent=2))

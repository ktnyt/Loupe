package jp.nano.rawgallery;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class RawEditLookTest {
  static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
  static void near(double a,double b,double e,String m){check(Math.abs(a-b)<=e,m+": "+a+" / "+b);}
  static RawEditCube parse(String s)throws Exception{return RawEditCube.read(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));}
  static String identity(int size){StringBuilder s=new StringBuilder("TITLE \"test\"\nLUT_3D_SIZE "+size+"\n");for(int b=0;b<size;b++)for(int g=0;g<size;g++)for(int r=0;r<size;r++)s.append(r/(double)(size-1)).append(' ').append(g/(double)(size-1)).append(' ').append(b/(double)(size-1)).append('\n');return s.toString();}
  static void reject(String s)throws Exception{try{parse(s);throw new AssertionError("Invalid LUT accepted");}catch(IOException expected){}}
  /** Generates bounded whitespace or one enormous line without allocating the source body. */
  static final class SyntheticInput extends InputStream {
    final byte[] prefix;final long length;final boolean oneLine;long position;boolean closed;
    SyntheticInput(byte[] prefix,long length,boolean oneLine){this.prefix=prefix;this.length=length;this.oneLine=oneLine;}
    public int read(){if(position==length)return -1;long i=position++;return i<prefix.length?prefix[(int)i]&255:oneLine?'x':i%128==127?'\n':' ';}
    public int read(byte[] bytes,int offset,int count){if(count==0)return 0;if(position==length)return -1;int n=(int)Math.min(count,length-position);for(int i=0;i<n;i++)bytes[offset+i]=(byte)read();return n;}
    public void close(){closed=true;}
  }
  static void streamingLimits(String table)throws Exception {
    for(String endings:new String[]{"\n","\r\n","\r"})check(parse("\ufeff"+table.stripTrailing().replace("\n",endings)).size()==2,"BOM/newlines/EOF");
    check(parse(table.replace("TITLE \"test\"","TITLE \"hash # title\" # outside comment")).title().equals("hash # title"),"quoted comment marker");
    byte[] prefix=table.getBytes(StandardCharsets.UTF_8);
    SyntheticInput exact=new SyntheticInput(prefix,RawEditCube.MAX_TEXT_BYTES,false);check(RawEditCube.read(exact).size()==2&&exact.position==RawEditCube.MAX_TEXT_BYTES&&!exact.closed,"32 MiB accepted without closing caller stream");
    SyntheticInput excessive=new SyntheticInput(prefix,(long)RawEditCube.MAX_TEXT_BYTES+64_000,false);
    try{RawEditCube.read(excessive);throw new AssertionError("oversized stream accepted");}catch(IOException expected){check(expected.getMessage().contains("32 MiB"),"byte limit diagnostic");}
    check(excessive.position==RawEditCube.MAX_TEXT_BYTES+1L&&!excessive.closed,"byte cap bounded read and caller ownership on failure");
    SyntheticInput enormousLine=new SyntheticInput(new byte[0],Long.MAX_VALUE,true);
    try{RawEditCube.read(enormousLine);throw new AssertionError("unbounded line accepted");}catch(IOException expected){check(expected.getMessage().contains("line too long"),"line limit diagnostic");}
    check(enormousLine.position<=16384&&!enormousLine.closed,"long line rejected before retaining/consuming source");
    try{RawEditCube.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}));throw new AssertionError("malformed UTF-8 accepted");}catch(IOException expected){}
  }
  static void calibratedAnchors(){
    // Frozen RGB anchors from the measured 7^3 model, including pixel (0,0) of a 65^2 image.
    double[][] input={{0,0,0},{.18,.18,.18},{.5,.5,.5},{.9,.9,.9},{1,1,1},{.8,.2,.1},{.1,.7,.3}},center={{0,0,0},{.17347894085216523,.18607647852230072,.1818952338256836},{.5936646461486816,.6071571707725525,.5989733934402466},{.9200371122360229,.9301710448265075,.9260049095153808},{.9882687330245972,.9974678754806519,.9932222962379456},{.854177093029022,.2561441124677658,.22324134373664858},{.11555686318874357,.787363278388977,.41770423674583423}},corner={{0,0,0},{.12439399439578258,.13342712557955494,.1304289418991414},{.4343675950038363,.44423969284463144,.43825185492815505},{.8323814216409241,.8415498531142807,.8377806425173342},{.9310723750951195,.9397391143424331,.9357392493086957},{.6124922137598573,.18366949402798521,.1600763892451274},{.08506053555029708,.5795721715205571,.30746893865746383}};RawEditLook look=RawEditLook.cntLike();
    check(look.toneEnabled()&&!look.hasTone()&&look.lutEnabled()&&look.vignetteEnabled()&&look.vignetteStrength()==1&&!look.grainEnabled()&&look.grainStrength()==.18f,"measured default stages");
    for(int i=0;i<input.length;i++)for(int k=0;k<3;k++){near(look.applyAt(input[i],0,0,.5,.5)[k],center[i][k],2e-7,"frozen measured center RGB "+i+" channel "+k);near(look.applyAt(input[i],0,0,.5/65,.5/65)[k],corner[i][k],2e-7,"frozen measured corner RGB "+i+" channel "+k);}
    double[] previous={-1,-1,-1};for(int i=0;i<=4096;i++){double v=i/4096.;double[] rgb=look.applyAt(new double[]{v,v,v},0,0,.5,.5);for(int k=0;k<3;k++){check(Double.isFinite(rgb[k])&&rgb[k]>=0&&rgb[k]<=1&&rgb[k]>=previous[k]-1e-7,"measured gray ramp finite/gamut/monotonic");previous[k]=rgb[k];}}
  }
  static void spatial()throws Exception {
    RawEditLook neutral=RawEditLook.neutral(),look=neutral.withVignetteEnabled(true);
    double[] rgb={.8,.4,.2};check(!neutral.active()&&look.active(),"vignette participates in activity");
    check(Arrays.equals(rgb,look.applyAt(rgb,37,83,.5,.5)),"vignette center identity");
    check(Arrays.equals(rgb,look.applyAt(rgb,37,83,.8,.5)),"measured inner plateau");
    for(double u:new double[]{0,1})for(double v:new double[]{0,1})for(int k=0;k<3;k++)near(look.applyAt(rgb,37,83,u,v)[k],rgb[k]*(1-.2999377522080069),1e-15,"frozen corner gain");
    for(int[] dimensions:new int[][]{{1200,800},{800,1200}}){
      int w=dimensions[0],h=dimensions[1];double u=117.5/w,v=219.5/h;
      double[] full=look.applyAt(rgb,1,2,u,v),tile=look.applyAt(rgb,999,888,(100+17.5)/w,(200+19.5)/h);
      check(Arrays.equals(full,tile),"full-image position independent of tile/grain coordinates");
      for(int k=0;k<3;k++)near(full[k],look.applyAt(rgb,1,2,1-u,1-v)[k],1e-15,"portrait/landscape mirrored falloff");
    }
    check(Arrays.equals(rgb,look.withVignetteStrength(0).apply(rgb,1,2)),"zero vignette preserves legacy API");
    check(Arrays.equals(rgb,look.withVignetteEnabled(false).apply(rgb,1,2)),"disable vignette preserves pixels");
    try{look.apply(rgb,1,2);throw new AssertionError("spatial position silently omitted");}catch(IllegalStateException expected){}
    for(float invalid:new float[]{-.01f,1.01f,Float.NaN,Float.POSITIVE_INFINITY})try{look.withVignetteStrength(invalid);throw new AssertionError("invalid vignette strength");}catch(IllegalArgumentException expected){}
    for(double[] q:new double[][]{{-.01,0},{0,1.01},{Double.NaN,.5},{.5,Double.POSITIVE_INFINITY}})try{look.applyAt(rgb,1,2,q[0],q[1]);throw new AssertionError("invalid position");}catch(IllegalArgumentException expected){}
    RawEditLook changed=look.withVignetteStrength(.7f);
    for(RawEditLook edited:new RawEditLook[]{changed.withToneEnabled(true),changed.withLutEnabled(true),changed.withGrainEnabled(true),changed.withLutStrength(.3f),changed.withGrainStrength(.3f),changed.withGrainShadows(.3f),changed.withSeed(1),changed.withBasePreset(1),changed.withCube(RawEditCube.identity(2)),changed.withCurve(0,new RawEditCurve())})check(edited.vignetteEnabled()&&edited.vignetteStrength()==.7f,"immutable copies preserve vignette");
    check(!changed.equals(look)&&!changed.equals(changed.withVignetteEnabled(false)),"vignette equality");
    check(changed.withVignetteStrength(0).equals(changed.withVignetteStrength(-0f))&&changed.withVignetteStrength(0).hashCode()==changed.withVignetteStrength(-0f).hashCode(),"vignette signed zero hash");
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();changed.write(new DataOutputStream(bytes));RawEditLook restored=RawEditLook.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));check(changed.equals(restored)&&changed.hashCode()==restored.hashCode(),"spatial recipe round trip");
    byte[] invalidRecipe=bytes.toByteArray();java.nio.ByteBuffer.wrap(invalidRecipe).putFloat(28,Float.NaN);try{RawEditLook.read(new DataInputStream(new ByteArrayInputStream(invalidRecipe)));throw new AssertionError("nonfinite binary vignette accepted");}catch(IOException expected){}
    try{RawEditLook.read(new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(bytes.toByteArray(),30))));throw new AssertionError("truncated binary vignette accepted");}catch(IOException expected){}
    RawEditLook grain=neutral.withGrainEnabled(true).withGrainStrength(.7f);
    check(Arrays.equals(grain.apply(rgb,17.5,19.5,2,3),grain.applyAt(rgb,17.5,19.5,2,3,.1,.9)),"explicit position preserves existing grain seed/footprint");
    double[] white={1,1,1},dark={.4,.4,.4};double whiteRatio=look.applyAt(white,0,0,0,0)[0],darkRatio=look.applyAt(dark,0,0,0,0)[0]/.4;
    near(whiteRatio,.9412277242653875,1e-12,"frozen highlight-protected corner gain");check(whiteRatio>darkRatio,"highlight protection reduces vignette");
    near(look.withVignetteStrength(.5f).applyAt(dark,0,0,0,0)[0],(.4+look.applyAt(dark,0,0,0,0)[0])*.5,1e-15,"strength linearly mixes measured vignette");
    // LUT values above one must remain unbounded until vignette has multiplied them.
    StringBuilder cube=new StringBuilder("LUT_3D_SIZE 2\n");for(int i=0;i<8;i++)cube.append("1.1 1.1 1.1\n");
    RawEditLook unbounded=look.withCube(parse(cube.toString())).withLutEnabled(true);
    near(unbounded.applyAt(rgb,0,0,0,0)[0],1,0,"vignette follows unbounded LUT before final clamp");
  }
  public static void main(String[] args)throws Exception {
    if(args.length==1&&args[0].equals("--streaming-only")){streamingLimits(identity(2));System.out.println("PASS cube streaming: 32 MiB under 16 MiB heap, byte cap, bounded long line, caller ownership, BOM/CRLF/EOF, strict UTF-8");return;}
    spatial();calibratedAnchors();
    Random random=new Random(65);
    for(int size:new int[]{17,33,65}){RawEditCube cube=parse(identity(size));check(cube.size()==size,"size");for(int n=0;n<1500;n++){double[] c={random.nextDouble(),random.nextDouble(),random.nextDouble()},out=cube.apply(c);for(int k=0;k<3;k++)near(c[k],out[k],6e-8,"identity "+size);}double[] white=cube.apply(new double[]{1,1,1});for(double v:white)near(v,1,0,"upper boundary");}
    String table=identity(2);RawEditCube domain=parse(table.replace("LUT_3D_SIZE 2","DOMAIN_MIN -1 -2 -3\nDOMAIN_MAX 1 2 3\nLUT_3D_SIZE 2"));double[] mid=domain.apply(new double[]{0,0,0});for(double v:mid)near(v,.5,0,"DOMAIN center");near(domain.apply(new double[]{-5,0,0})[0],0,0,"DOMAIN clamp");
    // Nonseparable trilinear fixture distinguishes red-fastest ordering and all eight corners.
    StringBuilder nonlinear=new StringBuilder("LUT_3D_SIZE 2\n");for(int b=0;b<2;b++)for(int g=0;g<2;g++)for(int r=0;r<2;r++)nonlinear.append(r*g).append(' ').append(g*b).append(' ').append(b*r).append('\n');double[] n=parse(nonlinear.toString()).apply(new double[]{.2,.3,.7});near(n[0],.06,1e-12,"trilinear RG");near(n[1],.21,1e-12,"trilinear GB");near(n[2],.14,1e-12,"trilinear BR");
    reject(table+"0 0 0\n");reject(table.replaceFirst("0.0 0.0 0.0","NaN 0 0"));reject(table.replaceFirst("0.0 0.0 0.0","Infinity 0 0"));reject(table.replace("LUT_3D_SIZE 2","LUT_3D_SIZE 66"));reject(table.replace("LUT_3D_SIZE 2","LUT_1D_SIZE 2"));reject(table.replace("LUT_3D_SIZE 2","DOMAIN_MAX 0 1 1\nLUT_3D_SIZE 2"));reject(table+"FOO BAR\n");reject("LUT_3D_SIZE 2\n");reject(table.replace("LUT_3D_SIZE 2","LUT_3D_SIZE 2\nLUT_3D_SIZE 2"));
    for(String overlong:new String[]{"#"+"x".repeat(4096)+"\n"+table," ".repeat(4097)+"\n"+table,table.replaceFirst("0.0 0.0 0.0","0 0 0 #"+"x".repeat(4097))})reject(overlong);
    check(parse("#"+"x".repeat(4095)+"\n"+table).size()==2,"4096 raw line accepted");
    RawEditLook neutral=RawEditLook.neutral();for(int i=0;i<10000;i++){double[] c={random.nextDouble(),random.nextDouble(),random.nextDouble()};check(Arrays.equals(c,neutral.apply(c,1,2)),"Neutral exact identity");}
    RawEditLook positiveZero=neutral.withLutStrength(0).withGrainStrength(0).withGrainShadows(0),negativeZero=neutral.withLutStrength(-0f).withGrainStrength(-0f).withGrainShadows(-0f);
    check(positiveZero.equals(negativeZero)&&positiveZero.hashCode()==negativeZero.hashCode(),"signed-zero equality and hashCode");HashSet<RawEditLook> recipes=new HashSet<>();recipes.add(positiveZero);check(recipes.contains(negativeZero),"HashSet signed-zero lookup");
    RawEditLook preset=RawEditLook.cntLike();RawEditCurve c=preset.curve(0);near(c.at(0),0,0,"curve black");near(c.at(1),1,0,"identity preset curve white");double prev=-1;for(int i=0;i<=10000;i++){double v=c.at(i/10000f);check(v>=prev-1e-7&&v>=0&&v<=1,"monotone preset");prev=v;}
    RawEditLook grain=neutral.withGrainEnabled(true).withGrainStrength(.6f);double[] gray={.4,.4,.4},a=grain.apply(gray,124.25,701.5),b=grain.apply(gray,124.25,701.5);check(Arrays.equals(a,b),"fixed seed determinism");near(a[0],a[1],0,"no red/green noise");near(a[0],a[2],0,"no red/blue noise");check(!Arrays.equals(a,grain.withSeed(1).apply(gray,124.25,701.5)),"seed changes output");check(Arrays.equals(gray,grain.withGrainStrength(0).apply(gray,124.25,701.5)),"zero strength");
    double mean=0,square=0;for(int y=0;y<256;y++)for(int x=0;x<256;x++){double v=RawEditLook.field(x+.5,y+.5,65);mean+=v;square+=v*v;}mean/=65536;check(Math.abs(mean)<.003&&square/65536>.005,"grain distribution");
    RawEditLook identityLook=neutral.withCube(RawEditCube.identity(33)).withLutEnabled(true);for(int i=0;i<1000;i++){double[] v={random.nextDouble(),random.nextDouble(),random.nextDouble()},o=identityLook.apply(v,0,0);for(int k=0;k<3;k++)near(o[k],v[k],6e-8,"identity look");}
    RawEditLook off=preset.withToneEnabled(false).withLutEnabled(false).withGrainEnabled(false).withVignetteEnabled(false);check(Arrays.equals(gray,off.apply(gray,1,1)),"independent off");RawEditLook zero=neutral.withCube(preset.cube()).withLutEnabled(true).withLutStrength(0);check(Arrays.equals(gray,zero.apply(gray,0,0)),"zero LUT mix");
    // Float processing has many more levels than 8-bit before the final output conversion.
    Set<Long> levels=new HashSet<>();RawEditLook tone=preset.withLutEnabled(false).withGrainEnabled(false);for(int i=0;i<=4096;i++){double v=i/4096.;double[] o=tone.applyAt(new double[]{v,v,v},0,0,.5,.5);for(double value:o)check(value>=0&&value<=1&&Double.isFinite(value),"finite gradient");levels.add(Double.doubleToLongBits(o[0]));}check(levels.size()>4000,"no intermediate 8-bit quantization");
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();preset.write(new DataOutputStream(bytes));RawEditLook restored=RawEditLook.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));check(preset.equals(restored)&&preset.hashCode()==restored.hashCode(),"look recipe round trip");
    check(neutral.basePreset()==0&&preset.basePreset()==1,"preset reset origins");
    for(int base:new int[]{0,1}){
      RawEditLook origin=preset.withBasePreset(base);
      for(RawEditLook edited:new RawEditLook[]{origin.withToneEnabled(false),origin.withLutEnabled(false),origin.withGrainEnabled(false),origin.withLutStrength(.4f),origin.withGrainStrength(.3f),origin.withGrainShadows(.2f),origin.withSeed(987),origin.withCube(null),origin.withCurve(2,new RawEditCurve(new float[]{0,0,.5f,.4f,1,1}))})check(edited.basePreset()==base,"every edit preserves immutable reset origin");
      ByteArrayOutputStream encoded=new ByteArrayOutputStream();origin.write(new DataOutputStream(encoded));RawEditLook decoded=RawEditLook.read(new DataInputStream(new ByteArrayInputStream(encoded.toByteArray())));check(origin.equals(decoded)&&decoded.basePreset()==base,"binary reset origin round trip");
    }
    for(int invalid:new int[]{-1,2,Integer.MAX_VALUE})try{neutral.withBasePreset(invalid);throw new AssertionError("invalid reset origin accepted");}catch(IllegalArgumentException expected){}
    RawEditLook alteredOrigin=preset.withBasePreset(0);check(!preset.equals(alteredOrigin),"origin participates in equality");HashSet<RawEditLook> origins=new HashSet<>();origins.add(preset);origins.add(alteredOrigin);check(origins.size()==2&&origins.contains(preset.withBasePreset(0)),"origin participates in hash collection behavior");
    byte[] modern=bytes.toByteArray();check(java.nio.ByteBuffer.wrap(modern).getInt()==6503,"version 6503 emitted");int baseOffset=4+3+3*4+4;
    byte[] version6502=new byte[modern.length-5];System.arraycopy(modern,0,version6502,0,baseOffset+4);System.arraycopy(modern,baseOffset+9,version6502,baseOffset+4,modern.length-baseOffset-9);java.nio.ByteBuffer.wrap(version6502).putInt(6502);
    RawEditLook old6502=RawEditLook.read(new DataInputStream(new ByteArrayInputStream(version6502)));check(old6502.equals(preset.withVignetteEnabled(false))&&!old6502.vignetteEnabled(),"6502 retains pixels and defaults vignette off");
    byte[] legacy=new byte[version6502.length-4];System.arraycopy(version6502,0,legacy,0,baseOffset);System.arraycopy(version6502,baseOffset+4,legacy,baseOffset,version6502.length-baseOffset-4);java.nio.ByteBuffer.wrap(legacy).putInt(6501);
    RawEditLook old=RawEditLook.read(new DataInputStream(new ByteArrayInputStream(legacy)));check(old.basePreset()==0&&old.equals(preset.withBasePreset(0).withVignetteEnabled(false)),"legacy 6501 retains every value and defaults reset origin to Neutral");
    for(double[] color:new double[][]{{0,0,0},{.18,.5,.9},{1,1,1}})check(Arrays.equals(old6502.apply(color,71,83),preset.withVignetteEnabled(false).applyAt(color,71,83,0,0))&&Arrays.equals(old.apply(color,71,83),old6502.apply(color,71,83)),"historical formats preserve serialized pixels without spatial processing");
    byte[] invalidBase=modern.clone();java.nio.ByteBuffer.wrap(invalidBase).putInt(baseOffset,2);try{RawEditLook.read(new DataInputStream(new ByteArrayInputStream(invalidBase)));throw new AssertionError("binary invalid reset origin accepted");}catch(IOException expected){}
    try{RawEditLook.read(new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(modern,baseOffset+2))));throw new AssertionError("binary truncated reset origin accepted");}catch(IOException expected){}
    RawEditState state=new RawEditState().withLook(preset);check(state.with(RawEditState.EXPOSURE,1).look()==preset&&state.withCurve(0,new RawEditCurve()).look()==preset&&state.resetCurves().look()==preset,"edit operations preserve look");check(!state.equals(new RawEditState()),"look participates in state equality");check(new RawEditState(state.snapshot()).look().equals(neutral),"legacy snapshot separate look");check(RawEditState.neutral().get(RawEditState.ROLLOFF)==0,"neutral development");
    System.out.println("PASS look: 17/33/65 identity LUT, DOMAIN, trilinear/ordering, malformed LUT rejection, exact Neutral, PCHIP endpoints, independent toggles/mix, deterministic achromatic grain, float gradient, recipe/state preservation");
  }
}

package jp.nano.rawgallery;
import java.nio.*;
import java.util.Random;
public final class RawEditToneTest {
  static void require(boolean c,String why){if(!c)throw new AssertionError(why);}
  public static void main(String[] args){
    RawEditState neutral=new RawEditState();double worst=0;
    for(double roll:new double[]{0,.01,.5,1}){double previous=-1;for(int i=0;i<=32000;i++){double x=RawEditTone.shoulder(i/1000.,roll);require(x>=previous&&x>=0&&x<=1,"shoulder monotone");previous=x;}}
    for(int count=0;count<6;count++){
      RawEditState edit=neutral.with(RawEditState.SHADOWS,.5f).with(RawEditState.HIGHLIGHTS,-.7f).with(RawEditState.BLACKS,-.4f).withCurve(0,new RawEditCurve(new float[]{0,0,.2f,.15f,.5f,.5f,.8f,.86f,1,1}));
      double previous=-1;
      for(int i=0;i<=4096;i++){
        double t=i/4096.,v=count==0?t*.015:count==1?.7+t*.4:count==3?t:count==4?t*32:t;
        double[] source=count==2?new double[]{v,v*.04,v*.6}:new double[]{v,v,v};
        RawEditState active=count==5?new RawEditState(edit.snapshot()):edit;
        double[] out=RawEditTone.apply(active,source);for(double a:out)require(Double.isFinite(a)&&a>=0&&a<=1,"finite in gamut: "+count);
        if(count!=2){require(out[0]>=previous-1e-6,"no gray reversal: "+count);previous=out[0];}
      }
    }
    RawEditCurve c=new RawEditCurve(new float[]{0,0,1f/128,1,1,1});RawEditState s=neutral.withCurve(0,c);ByteBuffer lut=RawEditTone.lut(s).order(ByteOrder.nativeOrder());
    for(int i=0;i<=65536;i++){float x=i/65536f,q=x*8192;int k=Math.min(8191,(int)q);float a=lut.getFloat(k*16+4),b=lut.getFloat((k+1)*16+4);worst=Math.max(worst,Math.abs(c.at(x)-(a+(b-a)*(q-k))));}
    require(worst<.0003,"LUT error budget");
    Random r=new Random(63);double maxHue=0;
    for(int i=0;i<10000;i++){
      double[] lab={r.nextDouble(),r.nextDouble()*2-1,r.nextDouble()*2-1};double[] rgb=RawEditTone.gamut(lab);for(double v:rgb)require(v>=-1e-7&&v<=1+1e-7,"gamut limit");double[] mapped=RawEditTone.lab(rgb);
      if(Math.hypot(mapped[1],mapped[2])>1e-4){double e=Math.abs(Math.atan2(mapped[2],mapped[1])-Math.atan2(lab[2],lab[1]));e=Math.min(e,Math.PI*2-e);maxHue=Math.max(e,maxHue);}
    }
    require(maxHue<.003,"hue direction");
    double[] black=RawEditTone.apply(neutral.withCurve(0,new RawEditCurve(new float[]{0,.1f,1,1})),new double[3]);require(black[0]>.01&&Math.abs(black[0]-black[1])<1e-5,"lift exact black neutrally");
    RawEditHistory history=new RawEditHistory();history.push(neutral,s);require(history.undo(s).equals(neutral)&&history.redo(neutral).equals(s),"undo redo");history.undo(s);history.push(neutral,neutral.with(RawEditState.EXPOSURE,1));require(!history.canRedo(),"new edit drops redo");
    System.out.println("PASS 6 tone fixtures; rolloff [0,32]; 10000 gamut colors; hue error="+maxHue+"; LUT max error="+worst+"; undo/redo");
  }
}

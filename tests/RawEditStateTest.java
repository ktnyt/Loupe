import jp.nano.rawgallery.RawEditState;
import jp.nano.rawgallery.RawEditCurve;
import java.util.Random;
public final class RawEditStateTest {
  private static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
  public static void main(String[] args){
    RawEditState initial=new RawEditState();RawEditCurve identity=new RawEditCurve();
    for(int i=0;i<=10000;i++)check(Math.abs(identity.at(i/10000f)-i/10000f)<1e-7,"identity");
    Random r=new Random(63);
    for(int k=0;k<128;k++){
      float[] p={0,r.nextFloat()*.1f,.2f,.1f+r.nextFloat()*.2f,.5f,.4f+r.nextFloat()*.2f,.8f,.7f+r.nextFloat()*.2f,1,.9f+r.nextFloat()*.1f};
      RawEditCurve c=new RawEditCurve(p);float prev=-1;
      for(int i=0;i<=10000;i++){float v=c.at(i/10000f);check(v>=prev-2e-7&&v>=0&&v<=1,"monotone and bounded");prev=v;}
      for(int i=0;i<c.size();i++)check(c.at(c.x(i))==c.y(i),"interpolates knot");
    }
    RawEditCurve c=identity.add(.5f,.4f).move(1,1,-1);check(c.x(1)==1-RawEditCurve.GAP&&c.y(1)==0,"point cannot cross neighbors");
    check(c.delete(0).equals(c)&&c.delete(2).equals(c),"endpoints cannot be deleted");
    c=c.move(0,.1f,.2f); // constrained by next point's y=0
    check(c.at(-1)==c.y(0)&&c.at(2)==1,"constant endpoints outside domain");
    RawEditState state=initial.with(RawEditState.EXPOSURE,2).withCurve(0,identity.add(.3f,.4f)).withCurve(4,identity.add(.8f,.7f));
    float[] snap=state.snapshot();check(new RawEditState(snap).equals(state),"all banks round trip");snap[1]=100;check(state.get(0)==0,"snapshot defensive");
    check(state.resetCurves().get(RawEditState.EXPOSURE)==2&&state.resetCurves().curve(0).identity(),"curve reset preserves exposure");
    for(float[] bad:new float[][]{new float[16],new float[17],new float[0]})try{new RawEditState(bad);throw new AssertionError("legacy accepted");}catch(IllegalArgumentException expected){}
    float[] bad=state.snapshot();bad[18]=Float.NaN;try{new RawEditState(bad);throw new AssertionError("NaN accepted");}catch(IllegalArgumentException expected){}
    for(int i=0;i<RawEditState.COUNT;i++){check(initial.with(i,100).get(i)==RawEditState.max(i),"upper");check(initial.with(i,-100).get(i)==RawEditState.min(i),"lower");}
    for(float[] invalid:new float[][]{{0,0,0,1},{0,.5f,1,.4f},{Float.NaN,0,1,1},{0,0,.001f,1}})try{new RawEditCurve(invalid);throw new AssertionError("invalid knots");}catch(IllegalArgumentException expected){}
    System.out.println("PASS PCHIP: 128 curves/1.28M samples, point constraints, endpoints, all banks/recipe round trip, legacy reset");
  }
}

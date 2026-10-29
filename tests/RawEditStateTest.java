import jp.nano.rawgallery.RawEditState;

public final class RawEditStateTest {
  private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  public static void main(String[] args){
    RawEditState initial=new RawEditState();
    for(int i=0;i<=1000;i++)check(Math.abs(initial.curve(i/1000f)-i/1000f)<.000001,"neutral curve");
    for(float x:new float[]{.05f,.2f,.5f,.8f,.95f})for(float y:new float[]{0,.1f,.5f,.9f,1}){
      RawEditState s=initial.pair(5,6,x,y);float previous=-1;
      for(int i=0;i<=1000;i++){float v=s.curve(i/1000f);check(Float.isFinite(v)&&v>=-.000001&&v<=1.000001,"bounded finite curve");check(v>=previous-.000001,"monotonic curve");previous=v;}
      check(Math.abs(s.curve(x)-y)<.000001,"curve passes through selected point");
      check(s.curve(0)==0&&s.curve(1)==1,"fixed endpoints");
    }
    check(new RawEditState(new float[]{0,0,0,0,0,.5f,.5f,0,0,0,0,0,0,0,0,0}).get(RawEditState.MONO)==0,"legacy state defaults to color");
    check(initial.with(RawEditState.MONO,1).with(14,.3f).with(RawEditState.MONO,0).get(14)==.3f,"Mono preserves color edits");
    RawEditState edited=initial.with(2,.33f).with(3,-.52f).with(7,1.2f).with(14,.4f);
    check(edited.with(2,.8f).get(3)==edited.get(3),"single axis independent");
    check(edited.with(2,.8f).with(3,.1f).equals(edited.with(3,.1f).with(2,.8f)),"operation order does not change state");
    check(edited.reset(2,3,4).get(7)==1.2f&&edited.reset(2,3,4).get(14)==.4f,"reset only selected tab");
    float[] copy=edited.snapshot();copy[2]=1;check(edited.get(2)==.33f,"defensive snapshot");
    float[] source=edited.snapshot();RawEditState loaded=new RawEditState(source);source[2]=-1;check(loaded.equals(edited),"defensive constructor");
    for(int key=0;key<RawEditState.COUNT;key++){
      check(initial.with(key,100).get(key)==RawEditState.max(key),"upper bound");
      check(initial.with(key,-100).get(key)==RawEditState.min(key),"lower bound");
      try{initial.with(key,Float.NaN);throw new AssertionError("NaN accepted");}catch(IllegalArgumentException expected){}
    }
    try{new RawEditState(new float[3]);throw new AssertionError("invalid restore accepted");}catch(IllegalArgumentException expected){}
    System.out.println("PASS raw edit state: curve constraints, independent axes, order, reset, finite bounds, snapshots");
  }
}

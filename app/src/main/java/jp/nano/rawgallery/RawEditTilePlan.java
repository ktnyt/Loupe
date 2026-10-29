package jp.nano.rawgallery;

import android.graphics.RectF;
import java.util.*;

/** Output partition whose corresponding native input, including filter halo, stays bounded. */
final class RawEditTilePlan implements Iterable<RawEditTilePlan.Tile> {
  static final class Tile {
    final int x,y,width,height;final RectF source;
    Tile(int x,int y,int w,int h,RectF source){this.x=x;this.y=y;width=w;height=h;this.source=source;}
  }
  private final List<Tile> tiles=new ArrayList<>();
  RawEditTilePlan(RectF sourceRegion,int outputWidth,int outputHeight,int fullWidth,int fullHeight){
    if(sourceRegion==null||outputWidth<1||outputHeight<1||fullWidth<1||fullHeight<1
        ||!Float.isFinite(sourceRegion.left)||!Float.isFinite(sourceRegion.top)
        ||!Float.isFinite(sourceRegion.right)||!Float.isFinite(sourceRegion.bottom)
        ||sourceRegion.left<0||sourceRegion.top<0||sourceRegion.right>fullWidth||sourceRegion.bottom>fullHeight
        ||sourceRegion.width()<=0||sourceRegion.height()<=0)throw new IllegalArgumentException("Invalid tile region");
    float halo=(float)Math.ceil(20f*Math.max(fullWidth,fullHeight)/Math.min(2048,Math.max(fullWidth,fullHeight)))+1;
    int interior=Math.max(1,(int)(RawEditFullSource.MAX_REGION_SIDE-2*halo-2));
    int stepX=Math.max(1,Math.min(512,(int)Math.floor(interior*outputWidth/sourceRegion.width())));
    int stepY=Math.max(1,Math.min(512,(int)Math.floor(interior*outputHeight/sourceRegion.height())));
    for(int y=0;y<outputHeight;y+=stepY)for(int x=0;x<outputWidth;x+=stepX){
      int w=Math.min(stepX,outputWidth-x),h=Math.min(stepY,outputHeight-y);
      RectF source=new RectF(sourceRegion.left+sourceRegion.width()*x/outputWidth,
          sourceRegion.top+sourceRegion.height()*y/outputHeight,
          sourceRegion.left+sourceRegion.width()*(x+w)/outputWidth,
          sourceRegion.top+sourceRegion.height()*(y+h)/outputHeight);
      tiles.add(new Tile(x,y,w,h,source));
    }
  }
  public Iterator<Tile> iterator(){return Collections.unmodifiableList(tiles).iterator();}
}

package jp.nano.rawgallery;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** The result-open and cloud-entry symbols, in a 24-unit drawing box. */
final class AffordanceIcon extends Drawable {
  private final int kind, color, size;
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
  AffordanceIcon(int kind, int color) { this(kind,color,24); }
  AffordanceIcon(int kind, int color,int size) { this.kind=kind; this.color=color;this.size=size; }
  public int getIntrinsicWidth(){return size;}
  public int getIntrinsicHeight(){return size;}
  private void path(Canvas c, float... points) {
    Path path=new Path();path.moveTo(points[0],points[1]);
    for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,p);
  }
  public void draw(Canvas c) {
    int save=c.save();Rect b=getBounds();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);
    boolean enabled=false;for(int state:getState())if(state==android.R.attr.state_enabled)enabled=true;
    p.setColor(kind>=2&&!enabled?0xff626b72:color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
    switch(kind) {
      case 0: c.drawCircle(10.5f,10.5f,6,p);path(c,15,15,20,20);path(c,10.5f,8,10.5f,13);path(c,8,10.5f,13,10.5f);break;
      case 1: c.drawRect(4,5,20,19,p);path(c,4,15,8,11,11,14);path(c,14,9,12,12,14,13,13,16);break;
      case 2: c.drawCircle(9,8,3.5f,p);Path person=new Path();person.moveTo(3,19);person.cubicTo(3.8f,15.8f,6.2f,14,9,14);c.drawPath(person,p);path(c,14,13,21,13);path(c,18,10,21,13,18,16);break;
      case 3: c.drawArc(4.5f,4.5f,19.5f,19.5f,210,123,false,p);path(c,19,4,19,8.5f,14.5f,8.5f);c.drawArc(4.5f,4.5f,19.5f,19.5f,30,123,false,p);path(c,5,20,5,15.5f,9.5f,15.5f);break;
      case 4: Path cloud=new Path();cloud.moveTo(7,18.5f);cloud.lineTo(17.5f,18.5f);cloud.cubicTo(22,18.5f,22,11.5f,17.8f,11.5f);cloud.cubicTo(17,5,8,5,7.2f,10);cloud.cubicTo(1.5f,10,1.5f,18.5f,7,18.5f);c.drawPath(cloud,p);path(c,9.5f,14,11.5f,16,15,12.2f);break;
    }
    c.restoreToCount(save);
  }
  public boolean isStateful(){return kind>=2;}
  protected boolean onStateChange(int[] state){invalidateSelf();return true;}
  public void setAlpha(int alpha) { p.setAlpha(alpha); }
  public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter); }
  public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

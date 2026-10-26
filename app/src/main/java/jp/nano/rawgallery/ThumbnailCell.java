package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.widget.*;

/** Square photo tile. Metadata occupies only small overlays, never a separate row. */
final class ThumbnailCell extends FrameLayout {
  final ImageView image;
  final Badges badges;
  int fixedHeight;

  ThumbnailCell(Context context) {
    super(context);
    setBackgroundColor(0xff22272a);
    image = new DiagnosticImageView(context);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(image, new LayoutParams(-1, -1));
    badges = new Badges(context);
    badges.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(badges, new LayoutParams(-1, -1));
  }

  protected void onMeasure(int width, int height) {
    int side = MeasureSpec.getSize(width);
    super.onMeasure(
        MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(fixedHeight>0?fixedHeight:side, MeasureSpec.EXACTLY));
  }

  private final Path selectedClip=new Path();
  @Override protected boolean drawChild(Canvas canvas,View child,long time){
    if(child==image&&badges.photoSelection&&badges.selected){int save=canvas.save();float inset=GalleryUi.dp(getContext(),10),radius=GalleryUi.dp(getContext(),6);selectedClip.reset();selectedClip.addRoundRect(inset,inset,getWidth()-inset,getHeight()-inset,radius,radius,Path.Direction.CW);canvas.clipPath(selectedClip);boolean drawn=super.drawChild(canvas,child,time);canvas.restoreToCount(save);return drawn;}
    return super.drawChild(canvas,child,time);
  }

  static final class Badges extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    String camera = "", rank = "";
    int count;
    StatusView.Symbol tagState;
    boolean cloud, failed, rankFailed, selectionMode, selected, partial, tagAtRight, photoSelection;

    Badges(Context context) {
      super(context);
      density = getResources().getDisplayMetrics().density;
    }

    protected void onDraw(Canvas canvas) {
      canvas.save();
      canvas.scale(density, density);
      float w = getWidth() / density, h = getHeight() / density;
      paint.setTextSize(10);
      paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
      if (!camera.isEmpty() && !camera.equals("その他")) {
        box(canvas, 5, 5, paint.measureText(camera) + 12, 21);
        paint.setColor(Color.WHITE);
        canvas.drawText(camera, 11, 19, paint);
      }
      if (cloud && !selectionMode) {
        box(canvas, w - 29, h - 26, 24, 21);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(w - 21, h - 14, 3, paint);
        canvas.drawCircle(w - 16, h - 17, 5, paint);
        canvas.drawCircle(w - 11, h - 14, 3, paint);
        canvas.drawRect(w - 21, h - 15, w - 11, h - 11, paint);
      }
      boolean selectionStyle=photoSelection&&selectionMode;
      float edge=selectionStyle?(selected?14:4):5;
      if(selectionStyle)paint.setTextSize(12);
      if (count > 1) {
        String number = Integer.toString(count);
        float width = 26 + paint.measureText(number), left = w - edge - width;
        if(selectionStyle){paint.setStyle(Paint.Style.FILL);paint.setColor(0x9e000000);canvas.drawRoundRect(left,edge,left+width,edge+20,10,10,paint);}else box(canvas,left,edge,width,21);
        paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.3f);
        canvas.drawRoundRect(left+5,edge+5,left+13,edge+13,1,1,paint);canvas.drawRoundRect(left+8,edge+8,left+16,edge+16,1,1,paint);paint.setStyle(Paint.Style.FILL);canvas.drawText(number,left+21,edge+14,paint);
      }
      if (!rank.isEmpty()) {
        if(selectionStyle&&rank.equals("1位")){
          paint.setStyle(Paint.Style.FILL);paint.setColor(0xfff5b947);float top=h-edge-20;canvas.drawRoundRect(edge,top,edge+20,top+20,5,5,paint);paint.setColor(0xff241800);Path star=new Path();for(int i=0;i<10;i++){double angle=-Math.PI/2+i*Math.PI/5;float radius=i%2==0?6:2.8f;float x=edge+10+(float)Math.cos(angle)*radius,y=top+10+(float)Math.sin(angle)*radius;if(i==0)star.moveTo(x,y);else star.lineTo(x,y);}star.close();canvas.drawPath(star,paint);
        }else{box(canvas,edge,h-edge-21,paint.measureText(rank)+12,21);paint.setColor(0xfff5b947);canvas.drawText(rank,edge+6,h-edge-7,paint);}
      }
      if(tagState!=null){if(tagAtRight)box(canvas,w-30,h-26,24,20);StatusView.glyph(canvas,paint,tagState,tagAtRight?w-28:5,h-26,20,GalleryUi.TEXT,0);}
      if(rankFailed)StatusView.glyph(canvas,paint,StatusView.Symbol.FAILED,5,h-26,20,0xffff8a7a,0);
      if (selectionMode) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(selected ? GalleryUi.ACCENT : 0xaa141719);
        canvas.drawCircle(17, 17, 11, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setColor(selected && !photoSelection ? GalleryUi.BG : Color.WHITE);
        if (selected) {
          Path tick = new Path();
          tick.moveTo(12, 17);
          tick.lineTo(16, 21);
          tick.lineTo(23, 13);
          canvas.drawPath(tick, paint);
          if(!photoSelection){paint.setColor(GalleryUi.ACCENT);canvas.drawRect(1,1,w-1,h-1,paint);}
        } else {
          canvas.drawCircle(17, 17, 10, paint);
          if (partial) canvas.drawLine(12, 17, 22, 17, paint);
        }
        paint.setStyle(Paint.Style.FILL);
      }
      if (failed) {
        box(canvas, w / 2 - 12, h / 2 - 12, 24, 24);
        paint.setColor(Color.WHITE);
        StatusView.glyph(canvas,paint,StatusView.Symbol.FAILED,w/2-10,h/2-10,20,0xffff8a7a,0);
      }
      canvas.restore();
    }

    private void box(Canvas canvas, float x, float y, float width, float height) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(0xaa141719);
      canvas.drawRoundRect(x, y, x + width, y + height, 5, 5, paint);
    }
  }
}

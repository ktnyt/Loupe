package jp.nano.rawgallery;

import android.content.Context;
import android.widget.TextView;

/** Shared photo-library typography; hierarchy and state belong to each screen. */
final class PhotoListUi {
  static TextView text(Context c,String value,int size,int line,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineHeight(Math.round(t.getTextSize()*line/size));return t;}
}

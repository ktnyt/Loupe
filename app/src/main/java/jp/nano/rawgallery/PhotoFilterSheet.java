package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/** Draft choices remain local until Apply; late count results cannot replace newer choices. */
final class PhotoFilterSheet extends BottomSheetDialog {
  interface Count { void request(String camera,int rank,IntConsumer result); }
  static final String[] CAMERAS={"すべて","GR","OM-1","E-P7","Leitz","その他"};
  static final String[] RANKS={"すべて","1位","順位が未決定","順位決定済み"};
  private final List<MaterialButton> ranks=new ArrayList<>(),cameras=new ArrayList<>();
  private final MaterialButton apply;
  private final Count count;
  private int rank,request;
  private String camera;
  PhotoFilterSheet(Context c,String camera,int rank,Count count,BiConsumer<String,Integer> committed) {
    super(c);this.camera=camera;this.rank=rank;this.count=count;
    ScrollView scroll=new ScrollView(c);scroll.setFillViewport(false);
    LinearLayout body=new LinearLayout(c);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(12),dp(20),dp(24));scroll.addView(body);
    LinearLayout title=LibraryUi.toolbar(c,"絞り込み",LibraryUi.icon(c,R.drawable.ic_close,"閉じる",this::dismiss));
    title.setPadding(0,0,0,0);body.addView(title);
    body.addView(label(c,"順位"));
    GridLayout rankGrid=new GridLayout(c);rankGrid.setColumnCount(2);rankGrid.setBackground(GalleryUi.shape(c,GalleryUi.PANEL,24));
    for(int i=0;i<RANKS.length;i++) {
      final int value=i;MaterialButton b=choice(c,new String[]{"すべて","1位","未決定","決定済み"}[i],RANKS[i],()->{this.rank=value;update();});
      ranks.add(b);GridLayout.LayoutParams p=new GridLayout.LayoutParams(GridLayout.spec(i/2),GridLayout.spec(i%2,1f));p.width=0;p.height=-2;p.setMargins(dp(2),dp(2),dp(2),dp(2));rankGrid.addView(b,p);
    }
    body.addView(rankGrid,new LinearLayout.LayoutParams(-1,-2));
    body.addView(label(c,"撮影元"));
    GridLayout cameraGrid=new GridLayout(c);cameraGrid.setColumnCount(3);
    for(int i=0;i<CAMERAS.length;i++) {
      final String value=CAMERAS[i];MaterialButton b=choice(c,value,value,()->{this.camera=value;update();});cameras.add(b);
      GridLayout.LayoutParams p=new GridLayout.LayoutParams(GridLayout.spec(i/3),GridLayout.spec(i%3,1f));p.width=0;p.height=-2;p.setMargins(dp(2),dp(2),dp(2),dp(2));cameraGrid.addView(b,p);
    }
    body.addView(cameraGrid,new LinearLayout.LayoutParams(-1,-2));
    LinearLayout footer=new LinearLayout(c);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(0,dp(20),0,0);
    footer.addView(LibraryUi.text(c,"リセット",()->{this.camera="すべて";this.rank=0;update();}));
    apply=LibraryUi.text(c,"読み込み中",()->{String chosen=this.camera;int chosenRank=this.rank;dismiss();committed.accept(chosen,chosenRank);});
    apply.setTag("photo-filter-apply");MaterialUi.filled(apply);footer.addView(apply,new LinearLayout.LayoutParams(0,-2,1));body.addView(footer);
    setContentView(scroll);MaterialUi.sheet(this);getBehavior().setDraggable(true);
    setOnShowListener(dialog->update());setOnDismissListener(dialog->request++);
  }
  private void update() {
    for(int i=0;i<ranks.size();i++)style(ranks.get(i),i==rank);
    for(int i=0;i<cameras.size();i++)style(cameras.get(i),CAMERAS[i].equals(camera));
    int token=++request;apply.setEnabled(false);apply.setText("読み込み中");
    count.request(camera,rank,value->{if(!isShowing()||token!=request)return;apply.setText(value<0?"適用（件数不明）":value+"件を表示");apply.setEnabled(true);});
  }
  private void style(MaterialButton b,boolean selected){
    b.setEnabled(true);MaterialUi.choice(b,selected);
    if(!selected){MaterialUi.text(b);b.setBackgroundTintList(ColorStateList.valueOf(GalleryUi.PANEL));}
    b.setStateDescription(selected?"選択中":"未選択");
  }
  private MaterialButton choice(Context c,String label,String spoken,Runnable action){
    MaterialButton b=LibraryUi.text(c,label,action);b.setContentDescription(spoken);b.setCornerRadius(dp(20));return b;
  }
  private TextView label(Context c,String name){TextView t=PhotoListUi.text(c,name,14,20,GalleryUi.TEXT);t.setPadding(0,dp(20),0,dp(12));t.setAccessibilityHeading(true);return t;}
  private int dp(int v){return GalleryUi.dp(getContext(),v);}
}

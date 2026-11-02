package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.graphics.*;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/** Opt-in private, fixed-name JPEG evidence. Never reads a library URI or publishes to MediaStore. */
final class RawEditPhotoLookChecks {
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  static void run(Instrumentation instrumentation)throws Exception {
    File files=instrumentation.getTargetContext().getFilesDir().getCanonicalFile();
    File[] fixtures=new File[3];byte[][] hashes=new byte[3][];
    // Preflight all three inputs before producing any outputs. No argument can supply another path.
    for(int i=0;i<3;i++){
      File input=new File(files,"cnt-reference-input-"+i+".jpg");
      require(input.getAbsoluteFile().equals(input.getCanonicalFile())&&input.isFile()&&input.length()>0&&input.length()<=16L*1024*1024,"private JPEG fixture unavailable index="+i);
      try(InputStream stream=new FileInputStream(input)){require(stream.read()==255&&stream.read()==216&&stream.read()==255,"private fixture must be JPEG index="+i);}
      fixtures[i]=input;hashes[i]=hash(input);
    }
    for(int i=0;i<3;i++)runOne(instrumentation,files,fixtures[i],hashes[i],i);
  }
  private static void runOne(Instrumentation instrumentation,File files,File fixture,byte[] beforeHash,int index)throws Exception {
    File folder=new File(instrumentation.getTargetContext().getCacheDir(),"cnt-reference-"+java.util.UUID.randomUUID());
    require(folder.mkdir(),"create isolated reference cache");
    File copy=new File(folder,"input.jpg"),cache=new File(folder,"full.ppm");
    long started=SystemClock.elapsedRealtime();
    try {
      try(InputStream in=new FileInputStream(fixture);OutputStream out=new FileOutputStream(copy)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
      require(Arrays.equals(beforeHash,hash(copy)),"private copy byte identity index="+index);
      // Ownership transfers here; the fixed input fixture itself is never handed to RawEditSource.
      try(RawEditSource source=RawEditSource.fromPrivateCopy(copy)){
        require(!source.raw&&Math.max(source.originalWidth(),source.originalHeight())<=1024,"reference fixture must be JPEG within 1024 pixels index="+index);
        require(source.width==source.originalWidth()&&source.height==source.originalHeight(),"reference preview retains full dimensions index="+index);
        RawEditState neutral=RawEditState.neutral(),cnt=neutral.withLook(RawEditLook.cntLike());
        long loadMs=SystemClock.elapsedRealtime()-started,previewStarted=SystemClock.elapsedRealtime();
        // Render the actual initial JPEG preview before preparation releases its pixel buffer.
        try(RawEditRenderer previewRenderer=new RawEditRenderer(source.width,source.height,source.pixels)){
          previewRenderer.grainDimensions(source.originalWidth(),source.originalHeight());
          Bitmap preview=previewRenderer.render(cnt,false,false,1024),neutralPreview=previewRenderer.render(neutral,false,false,1024);
          try {
            require(preview.getWidth()==source.originalWidth()&&preview.getHeight()==source.originalHeight(),"CNT preview dimensions index="+index);
            png(preview,new File(files,"cnt-reference-preview-"+index+".png"));png(neutralPreview,new File(files,"cnt-reference-neutral-"+index+".png"));
            long previewMs=SystemClock.elapsedRealtime()-previewStarted,prepareStarted=SystemClock.elapsedRealtime();
            source.prepareFull(cache,new NativeRaw.Cancellation());
            long prepareMs=SystemClock.elapsedRealtime()-prepareStarted;
            try(RawEditFullSource full=RawEditFullSource.open(cache);RawEditRenderer fullRenderer=new RawEditRenderer(full.width,full.height,full.region(new Rect(0,0,full.width,full.height)));RawEditRenderer tiles=new RawEditRenderer()){
              require(full.width==preview.getWidth()&&full.height==preview.getHeight(),"full cache retains upright dimensions index="+index);
              fullRenderer.grainDimensions(full.width,full.height);
              Bitmap expected=fullRenderer.render(cnt,false,false,1024);
              try {
                Error previewError=error(preview,expected);require(previewError.max<=2,"initial/prepared GPU preview differs index="+index);
                long exportStarted=SystemClock.elapsedRealtime();File jpeg=new File(files,"cnt-reference-export-"+index+".jpg");
                export(full,tiles,cnt,jpeg);long exportMs=SystemClock.elapsedRealtime()-exportStarted;
                BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);
                Bitmap decoded=BitmapFactory.decodeFile(jpeg.getAbsolutePath(),options);require(decoded!=null,"export JPEG decodes index="+index);
                try {
                  require("image/jpeg".equals(options.outMimeType)&&decoded.getWidth()==full.width&&decoded.getHeight()==full.height,"export JPEG dimensions/format index="+index);
                  Error jpegError=error(decoded,expected);
                  // Same quality/subsampling tolerance as synthetic publish checks; aggregate bias stays bounded.
                  require(jpegError.mae<=4&&jpegError.rms<=9&&jpegError.maxBias<=1.5,"native JPEG GPU comparison index="+index+" mae="+jpegError.mae+" rms="+jpegError.rms+" bias="+jpegError.maxBias);
                  boolean unchanged=Arrays.equals(beforeHash,hash(fixture));require(unchanged,"input bytes unchanged index="+index);
                  android.util.Log.i("LoupeRawEdit","look.reference index="+index+" width="+full.width+" height="+full.height+" preview_mae_code="+previewError.mae+" preview_max_code="+previewError.max+" jpeg_mae_code="+jpegError.mae+" jpeg_rms_code="+jpegError.rms+" jpeg_max_bias_code="+jpegError.maxBias+" hash_unchanged="+unchanged+" load_ms="+loadMs+" preview_ms="+previewMs+" prepare_ms="+prepareMs+" export_ms="+exportMs);
                }finally{decoded.recycle();}
              }finally{expected.recycle();}
            }
          }finally{preview.recycle();neutralPreview.recycle();}
        }
      }
    }finally{
      boolean unchanged=Arrays.equals(beforeHash,hash(fixture));
      boolean copyRemoved=!copy.exists()||copy.delete(),cacheRemoved=!cache.exists()||cache.delete();
      boolean folderRemoved=folder.delete();
      require(copyRemoved&&cacheRemoved&&folderRemoved,"private reference cache cleanup index="+index);
      require(unchanged,"input bytes unchanged after cleanup index="+index);
    }
  }
  private static void png(Bitmap bitmap,File output)throws IOException {
    try(OutputStream stream=new FileOutputStream(output)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("Private preview encoding failed");}
  }
  /** Same RGB8 strip assembly, tile plan and native quality-95 encoder as RawEditFullSession. */
  private static void export(RawEditFullSource full,RawEditRenderer renderer,RawEditState state,File output)throws IOException {
    boolean complete=false;
    try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE|ParcelFileDescriptor.MODE_READ_WRITE);NativeJpeg jpeg=NativeJpeg.open(fd.getFd(),full.width,full.height,95)){
      ByteBuffer strip=ByteBuffer.allocateDirect(full.width*Math.min(128,full.height)*3);
      for(int y=0;y<full.height;y+=128){
        int rows=Math.min(128,full.height-y);strip.clear();strip.limit(full.width*rows*3);
        for(RawEditTilePlan.Tile tile:new RawEditTilePlan(new RectF(0,y,full.width,y+rows),full.width,rows,full.width,full.height)){
          ByteBuffer bytes=renderer.renderRegionBytes(full,state,false,false,tile.source,tile.width,tile.height);
          for(int j=0;j<tile.height;j++){strip.position(((tile.y+j)*full.width+tile.x)*3);for(int x=0;x<tile.width;x++){strip.put(bytes.get());strip.put(bytes.get());strip.put(bytes.get());bytes.get();}}
        }
        strip.position(0);strip.limit(full.width*rows*3);jpeg.write(strip,rows);
      }
      jpeg.finish();complete=true;
    }finally{if(!complete&&output.exists())require(output.delete(),"partial private JPEG cleanup");}
  }
  private static byte[] hash(File file)throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream stream=new FileInputStream(file)){byte[] buffer=new byte[32768];int n;while((n=stream.read(buffer))!=-1)digest.update(buffer,0,n);}return digest.digest();
  }
  private static final class Error {double mae,rms,maxBias;int max;}
  private static Error error(Bitmap actual,Bitmap expected){
    require(actual.getWidth()==expected.getWidth()&&actual.getHeight()==expected.getHeight(),"equal reference dimensions");
    double absolute=0,squared=0;double[] bias=new double[3];int count=actual.getWidth()*actual.getHeight();Error error=new Error();
    for(int y=0;y<actual.getHeight();y++)for(int x=0;x<actual.getWidth();x++){int a=actual.getPixel(x,y),e=expected.getPixel(x,y);for(int c=0;c<3;c++){int shift=16-c*8,d=(a>>shift&255)-(e>>shift&255);absolute+=Math.abs(d);squared+=d*d;bias[c]+=d;error.max=Math.max(error.max,Math.abs(d));}}
    error.mae=absolute/(count*3.);error.rms=Math.sqrt(squared/(count*3.));for(double value:bias)error.maxBias=Math.max(error.maxBias,Math.abs(value/count));return error;
  }
}

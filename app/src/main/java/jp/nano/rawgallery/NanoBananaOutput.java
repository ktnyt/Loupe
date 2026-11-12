package jp.nano.rawgallery;

import java.io.IOException;
import java.util.List;
import org.json.JSONObject;

/** Validated immutable output conditions captured when a generation starts. */
final class NanoBananaOutput {
  static final List<String> ASPECTS=List.of("auto","1:1","1:4","1:8","2:3","3:2","3:4","4:1","4:3","4:5","5:4","8:1","9:16","16:9","21:9");
  static final List<String> RESOLUTIONS=List.of("1K","2K","4K");
  static final NanoBananaOutput DEFAULT=new NanoBananaOutput("auto","1K");
  final String aspectRatio,resolution;
  private NanoBananaOutput(String aspectRatio,String resolution){this.aspectRatio=aspectRatio;this.resolution=resolution;}
  static NanoBananaOutput of(String aspectRatio,String resolution) throws IOException {
    if(aspectRatio==null||resolution==null||!ASPECTS.contains(aspectRatio)||!RESOLUTIONS.contains(resolution))
      throw new IOException("出力の比率と解像度を選び直してください");
    return new NanoBananaOutput(aspectRatio,resolution);
  }
  static NanoBananaOutput read(JSONObject value) throws Exception {
    return of(value.getString("aspectRatio"),value.getString("resolution"));
  }
  static NanoBananaOutput fromJob(JSONObject job) throws Exception {
    return job.has("output")?read(job.getJSONObject("output")):DEFAULT;
  }
  JSONObject json() throws Exception {return new JSONObject().put("aspectRatio",aspectRatio).put("resolution",resolution);}
  String aspectLabel(){return aspectRatio.equals("auto")?"自動":aspectRatio;}
}

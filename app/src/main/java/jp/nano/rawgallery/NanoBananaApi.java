package jp.nano.rawgallery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** NanoBananaAPI.ai only. Generation POSTs are never automatically retried. */
final class NanoBananaApi {
  private static final String ROOT="https://api.nanobananaapi.ai/api/v1/nanobanana/";
  private final String key;
  NanoBananaApi(String key) { this.key=key; }
  static JSONObject request(String prompt,String imageUrl,NanoBananaOutput output) throws Exception {
    output=NanoBananaOutput.of(output.aspectRatio,output.resolution);
    return new JSONObject().put("prompt",prompt).put("imageUrls",new JSONArray().put(imageUrl))
        .put("aspectRatio",output.aspectRatio).put("resolution",output.resolution).put("googleSearch",false).put("outputFormat","jpg");
  }
  String generate(String prompt,String imageUrl,NanoBananaOutput output) throws Exception {
    JSONObject result=call("generate-2",request(prompt,imageUrl,output));
    String id=result.getString("taskId");
    if(!id.matches("[A-Za-z0-9_-]{1,256}"))throw new IOException("生成タスクの応答を確認できませんでした");
    return id;
  }
  JSONObject status(String task) throws Exception {
    return call("record-info?taskId="+URLEncoder.encode(task,StandardCharsets.UTF_8.name()),null);
  }
  private JSONObject call(String route,JSONObject body) throws Exception {
    HttpsURLConnection connection=(HttpsURLConnection)new URL(ROOT+route).openConnection();
    connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
    connection.setConnectTimeout(15000);connection.setReadTimeout(45000);
    connection.setRequestProperty("Authorization","Bearer "+key);
    connection.setRequestProperty("Accept-Encoding","identity");
    try {
      if(body!=null) {
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);
        connection.setRequestProperty("Content-Type","application/json");
        try(java.io.OutputStream out=connection.getOutputStream()){out.write(bytes);}
      }
      int code=connection.getResponseCode();
      if(code!=200)throw new IOException(code==401||code==403?"APIキーまたは利用権限を確認してください":
          code==429?"APIの利用上限に達しました。時間をおいて状態を確認してください":"APIとの通信に失敗しました (HTTP "+code+")");
      JSONObject value;
      try(InputStream in=connection.getInputStream()) {value=new JSONObject(new String(read(in,1024*1024),StandardCharsets.UTF_8));}
      if(value.optInt("code")!=200)throw new IOException("APIが処理を受け付けませんでした。キーと残高を確認してください");
      return value.getJSONObject("data");
    } catch(Exception error) {throw new IOException("APIとの通信に失敗しました。APIキー・残高・通信を確認し、状態を再確認してください");}
    finally {connection.disconnect();}
  }
  static byte[] read(InputStream in,int max) throws IOException {
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];
    long deadline=android.os.SystemClock.elapsedRealtime()+120000;
    for(int n;(n=in.read(buffer))!=-1;) {
      if(Thread.currentThread().isInterrupted()||android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("通信を中断しました");
      if(out.size()+n>max)throw new IOException("応答サイズが上限を超えました");out.write(buffer,0,n);
    }
    return out.toByteArray();
  }
  /** Result CDN hosts used by the provider's public Playground. No arbitrary host or redirect. */
  static URL resultUrl(String value) throws Exception {
    URL url=new URL(value);
    if(!url.getHost().equals("tempfile.aiquickdraw.com")&&!url.getHost().equals("file.aiquickdraw.com"))
      throw new IOException("結果画像の接続先に対応していません");
    if(!url.getProtocol().equals("https")||url.getUserInfo()!=null||url.getPort()!=-1&&url.getPort()!=443)
      throw new IOException("結果画像のURLを確認できませんでした");
    return url;
  }
  static byte[] download(String value) throws Exception {
    URL url=resultUrl(value);
    if(!url.getProtocol().equals("https")||url.getUserInfo()!=null||url.getPort()!=-1&&url.getPort()!=443)
      throw new IOException("結果画像のURLを確認できませんでした");
    for(InetAddress address:InetAddress.getAllByName(url.getHost()))
      if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())
        throw new IOException("結果画像の接続先を確認できませんでした");
    HttpsURLConnection connection=(HttpsURLConnection)url.openConnection();
    connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
    connection.setConnectTimeout(15000);connection.setReadTimeout(45000);
    // Never attach API or e2 credentials to the result host.
    try {
      if(connection.getResponseCode()!=200)throw new IOException("結果画像を取得できませんでした");
      try(InputStream in=connection.getInputStream()){return read(in,20*1024*1024);}
    }catch(Exception error){throw new IOException("結果画像を取得できませんでした。状態を再確認してください");}
    finally{connection.disconnect();}
  }
}

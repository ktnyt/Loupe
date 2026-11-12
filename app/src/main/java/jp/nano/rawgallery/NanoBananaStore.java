package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local configuration; API credentials are encrypted with a non-exportable Android Keystore key. */
final class NanoBananaStore {
  static final Object LOCK=new Object();
  private static final String ALIAS = "loupe.nano-banana.v1";
  private final SharedPreferences prefs;
  static final class Prompt {
    final String id, name, text;
    Prompt(String id, String name, String text) { this.id=id; this.name=name; this.text=text; }
  }
  NanoBananaStore(Context context) { this(context,"nano-banana"); }
  NanoBananaStore(Context context,String name) { this(context.getSharedPreferences(name, Context.MODE_PRIVATE)); }
  NanoBananaStore(SharedPreferences prefs) {this.prefs=prefs;}
  NanoBananaOutput output() throws IOException {
    synchronized(LOCK) {
      try {
        String value=prefs.getString("output",null);
        return value==null?NanoBananaOutput.DEFAULT:NanoBananaOutput.read(new JSONObject(value));
      }catch(Exception e){throw new IOException("出力設定を読み込めませんでした。比率と解像度を選び直してください");}
    }
  }
  void setOutput(String aspectRatio,String resolution) throws IOException {
    NanoBananaOutput value=NanoBananaOutput.of(aspectRatio,resolution);
    synchronized(LOCK) {
      Object previous=prefs.getAll().get("output");
      try {
        if(prefs.edit().putString("output",value.json().toString()).commit())return;
      }catch(Exception e){/* Restore the in-memory preference as well as the durable value. */}
      SharedPreferences.Editor rollback=prefs.edit();
      if(previous instanceof String)rollback.putString("output",(String)previous);
      else if(previous instanceof Integer)rollback.putInt("output",(Integer)previous);
      else if(previous instanceof Long)rollback.putLong("output",(Long)previous);
      else if(previous instanceof Float)rollback.putFloat("output",(Float)previous);
      else if(previous instanceof Boolean)rollback.putBoolean("output",(Boolean)previous);
      else if(previous instanceof java.util.Set)rollback.putStringSet("output",new java.util.HashSet<>((java.util.Set<String>)previous));
      else rollback.remove("output");
      rollback.commit();
      throw new IOException("出力設定を保存できませんでした。もう一度保存してください");
    }
  }
  boolean hasKey() { return prefs.contains("credential"); }
  private static SecretKey key() throws Exception {
    KeyStore keys=KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
    if(keys.containsAlias(ALIAS)) return (SecretKey)keys.getKey(ALIAS,null);
    KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return generator.generateKey();
  }
  void setKey(String value) throws IOException {
    synchronized(LOCK) {
    if(NanoBananaJob.BUSY.get())throw new IOException("実行が終わってから変更してください");
    if(value==null || !value.matches("[\\x21-\\x7E]{1,512}")) throw new IOException("APIキーを確認してください");
    try {
      saveSecret("credential",value);
    } catch(Exception error) { throw new IOException("APIキーを保存できませんでした"); }
      }
  }
  private void saveSecret(String field,String value) throws Exception {
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
      String encrypted=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+
          Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
      if(!prefs.edit().putString(field,encrypted).commit()) throw new IOException();
  }
  String apiKey() throws IOException { return secret("credential"); }
  private String secret(String field) throws IOException {
    try {
      String[] parts=prefs.getString(field,"").split(":",-1);
      if(parts.length!=2) throw new IOException();
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
      return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    } catch(Exception error) { throw new IOException("APIキーを登録し直してください"); }
  }
  void deleteKey() throws IOException {
    synchronized(LOCK) {
    if(NanoBananaJob.BUSY.get()||job()!=null)throw new IOException("進行中の処理を終了してから削除してください");
    if(!prefs.edit().remove("credential").commit()) throw new IOException("APIキーを削除できませんでした");
      }
  }
  boolean hasE2() { return prefs.contains("e2"); }
  void setE2(String bucket,String access,String secret) throws IOException {
    synchronized(LOCK) {
    try {
      if(NanoBananaJob.BUSY.get())throw new IOException("実行が終わってから変更してください");
      JSONObject pending=job();
      if(pending!=null&&!pending.optString("stage").equals("complete")&&hasE2()
          &&!new JSONObject(secret("e2")).getString("bucket").equals(bucket))
        throw new IOException("一時画像の削除が終わるまでバケットは変更できません。キーとシークレットは修正できます");
      new NanoBananaE2(bucket,access,secret);
      saveSecret("e2",new JSONObject().put("bucket",bucket).put("access",access).put("secret",secret).toString());
    } catch(Exception error) { throw new IOException("IDrive e2の設定を保存できません。実行中は変更できず、処理が残っている間は同じバケットのキーのみ変更できます。入力も確認してください"); }
      }
  }
  NanoBananaE2 e2() throws Exception {
    JSONObject value=new JSONObject(secret("e2"));
    return new NanoBananaE2(value.getString("bucket"),value.getString("access"),value.getString("secret"));
  }
  void deleteE2() throws IOException {
    synchronized(LOCK) {
    if(NanoBananaJob.BUSY.get()||job()!=null) throw new IOException("進行中の処理を終了してから削除してください");
    if(!prefs.edit().remove("e2").commit()) throw new IOException("設定を削除できませんでした");
      }
  }
  synchronized JSONObject job() throws IOException {
    String value=prefs.getString("job",null);
    if(value==null)return null;
    try{return new JSONObject(value);}catch(Exception e){throw new IOException("処理記録を読み込めませんでした");}
  }
  synchronized void job(JSONObject value) throws IOException {
    SharedPreferences.Editor editor=prefs.edit();
    if(value==null)editor.remove("job");else editor.putString("job",value.toString());
    if(!editor.commit())throw new IOException("処理記録を保存できませんでした");
  }
  List<Prompt> prompts() throws IOException {
    try {
      JSONArray array=new JSONArray(prefs.getString("prompts","[]")); List<Prompt> result=new ArrayList<>();
      for(int i=0;i<array.length();i++) {
        JSONObject item=array.getJSONObject(i); result.add(new Prompt(item.getString("id"),item.getString("name"),item.getString("text")));
      }
      return result;
    } catch(Exception error) { throw new IOException("プロンプトを読み込めませんでした"); }
  }
  void savePrompt(String id,String name,String text) throws IOException {
    name=name.trim(); text=text.trim();
    if(name.isEmpty()||name.length()>100||text.isEmpty()||text.length()>10000)
      throw new IOException("名前は1〜100字、プロンプトは1〜10000字で入力してください");
    List<Prompt> values=prompts(); int found=-1;
    for(int i=0;i<values.size();i++) {
      Prompt p=values.get(i);
      if(p.id.equals(id)) found=i;
      else if(p.name.equals(name)) throw new IOException("同じ名前のプロンプトがあります");
    }
    if(found<0&&values.size()>=30) throw new IOException("プロンプトは30件まで登録できます");
    Prompt p=new Prompt(id==null?UUID.randomUUID().toString():id,name,text);
    if(found<0) values.add(p); else values.set(found,p);
    write(values);
  }
  void deletePrompt(String id) throws IOException {
    List<Prompt> values=prompts(); values.removeIf(p->p.id.equals(id)); write(values);
  }
  private void write(List<Prompt> values) throws IOException {
    try {
      JSONArray array=new JSONArray();
      for(Prompt p:values) array.put(new JSONObject().put("id",p.id).put("name",p.name).put("text",p.text));
      if(!prefs.edit().putString("prompts",array.toString()).commit()) throw new IOException();
    } catch(Exception error) { throw new IOException("プロンプトを保存できませんでした"); }
  }
}

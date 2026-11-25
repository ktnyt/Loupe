package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import jp.nano.rawgallery.security.EncryptedSecretStore;
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
      try {
        saveSetting("output", value.json().toString());
      } catch (Exception failure) {
        throw new IOException("出力設定を保存できませんでした。もう一度保存してください");
      }
    }
  }

  /** Caller holds LOCK. A failed commit may already have changed SharedPreferences in memory. */
  private void saveSetting(String field, String value) throws IOException {
    Object previous = prefs.getAll().get(field);
    try {
      SharedPreferences.Editor edit = prefs.edit();
      if (value == null) edit.remove(field);
      else edit.putString(field, value);
      if (edit.commit()) return;
    } catch (RuntimeException failure) {
      DiagnosticLog.exception("nano.settings_commit", "failed", failure);
    }
    try {
      restoreSetting(field, previous);
    } catch (RuntimeException failure) {
      DiagnosticLog.exception("nano.settings_restore", "failed", failure);
    }
    throw new IOException();
  }

  @SuppressWarnings("unchecked") // SharedPreferences supports only sets of strings.
  private void restoreSetting(String field, Object previous) {
    SharedPreferences.Editor rollback = prefs.edit();
    if (previous instanceof String) rollback.putString(field, (String) previous);
    else if (previous instanceof Integer) rollback.putInt(field, (Integer) previous);
    else if (previous instanceof Long) rollback.putLong(field, (Long) previous);
    else if (previous instanceof Float) rollback.putFloat(field, (Float) previous);
    else if (previous instanceof Boolean) rollback.putBoolean(field, (Boolean) previous);
    else if (previous instanceof java.util.Set) rollback.putStringSet(field,
        new java.util.HashSet<>((java.util.Set<String>) previous));
    else rollback.remove(field);
    if (!rollback.commit()) DiagnosticLog.exception("nano.settings_restore", "failed", new IOException());
  }

  boolean hasKey() {
    synchronized (LOCK) { return prefs.contains("credential"); }
  }
  void setKey(String value) throws IOException {
    synchronized(LOCK) {
    if(NanoBananaJob.BUSY.get())throw new IOException("実行が終わってから変更してください");
    if(value==null || !value.matches("[\\x21-\\x7E]{1,512}")) throw new IOException("APIキーを確認してください");
    try {
      saveSecret("credential",value);
    } catch (Exception error) {
      DiagnosticLog.exception("nano.credentials_api_save", "failed", error);
      throw new IOException("APIキーを保存できませんでした");
    }
      }
  }
  private void saveSecret(String field,String value) throws Exception {
      Cipher cipher = EncryptedSecretStore.encryptionCipher(ALIAS);
      String encrypted=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+
          Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
      saveSetting(field, encrypted);
  }
  String apiKey() throws IOException {
    synchronized (LOCK) {
      try {
        return secret("credential");
      } catch (Exception error) {
        DiagnosticLog.exception("nano.credentials_api_read", "failed", error);
        throw new IOException("APIキーを登録し直してください");
      }
    }
  }
  private String secret(String field) throws Exception {
    String[] parts = prefs.getString(field, "").split(":", -1);
    if (parts.length != 2) throw new IOException();
    Cipher cipher = EncryptedSecretStore.decryptionCipher(ALIAS,
        () -> new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        "暗号鍵を読み込めませんでした");
    return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }
  void deleteKey() throws IOException {
    synchronized (LOCK) {
      if (NanoBananaJob.BUSY.get() || job() != null)
        throw new IOException("進行中の処理を終了してから削除してください");
      try { saveSetting("credential", null); }
      catch (IOException error) { throw new IOException("APIキーを削除できませんでした"); }
    }
  }
  boolean hasE2() {
    synchronized (LOCK) { return prefs.contains("e2"); }
  }
  void setE2(String bucket, String access, String secret) throws IOException {
    synchronized (LOCK) {
      if (NanoBananaJob.BUSY.get()) throw new IOException("実行が終わってから変更してください");
      try {
        new NanoBananaE2(bucket, access, secret);
      } catch (IllegalArgumentException invalid) {
        throw new IOException("IDrive e2のバケット名、キー、シークレットを確認してください");
      }
      JSONObject pending = job();
      if (pending != null && !pending.optString("stage").equals("complete") && hasE2()
          && !e2Configuration().optString("bucket").equals(bucket))
        throw new IOException("一時画像の削除が終わるまでバケットは変更できません。キーとシークレットは修正できます");
      try {
        saveSecret("e2", new JSONObject().put("bucket", bucket).put("access", access)
            .put("secret", secret).toString());
      } catch (Exception error) {
        DiagnosticLog.exception("nano.credentials_e2_save", "failed", error);
        throw new IOException("IDrive e2の設定を保存できませんでした。もう一度保存してください");
      }
    }
  }
  private JSONObject e2Configuration() throws IOException {
    try {
      return new JSONObject(secret("e2"));
    } catch (Exception error) {
      DiagnosticLog.exception("nano.credentials_e2_read", "failed", error);
      throw new IOException("IDrive e2の設定を読み込めませんでした。接続設定を確認してください");
    }
  }
  NanoBananaE2 e2() throws IOException {
    synchronized (LOCK) {
      JSONObject value = e2Configuration();
      try {
        return new NanoBananaE2(value.getString("bucket"), value.getString("access"), value.getString("secret"));
      } catch (Exception error) {
        DiagnosticLog.exception("nano.credentials_e2_read", "failed", error);
        throw new IOException("IDrive e2の設定を読み込めませんでした。接続設定を確認してください");
      }
    }
  }
  void deleteE2() throws IOException {
    synchronized (LOCK) {
      if (NanoBananaJob.BUSY.get() || job() != null)
        throw new IOException("進行中の処理を終了してから削除してください");
      try { saveSetting("e2", null); }
      catch (IOException error) { throw new IOException("設定を削除できませんでした"); }
    }
  }
  JSONObject job() throws IOException {
    synchronized (LOCK) {
      String value = prefs.getString("job", null);
      if (value == null) return null;
      try { return new JSONObject(value); }
      catch (Exception e) {
        DiagnosticLog.exception("nano.job_read", "failed", e);
        throw new IOException("処理記録を読み込めませんでした");
      }
    }
  }
  void job(JSONObject value) throws IOException {
    synchronized (LOCK) {
      SharedPreferences.Editor editor = prefs.edit();
      if (value == null) editor.remove("job");
      else editor.putString("job", value.toString());
      if (!editor.commit()) throw new IOException("処理記録を保存できませんでした");
    }
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

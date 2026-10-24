package jp.nano.rawgallery;

import android.content.*;
import java.util.*;
import org.json.*;

public final class ShareTargets {
  public static final class Target {
    public final String component, label;
    public boolean raw;

    Target(String c, String l, boolean r) {
      component = c;
      label = l;
      raw = r;
    }
  }

  public static List<Target> load(Context c) {
    List<Target> out = new ArrayList<>();
    try {
      JSONArray a = new JSONArray(c.getSharedPreferences("settings", 0).getString("targets", "[]"));
      for (int i = 0; i < a.length(); i++) {
        JSONObject j = a.getJSONObject(i);
        out.add(new Target(j.getString("component"), j.getString("label"), j.optBoolean("raw")));
      }
    } catch (JSONException ignored) {
    }
    return out;
  }

  public static void save(Context c, List<Target> targets) {
    JSONArray a = new JSONArray();
    try {
      for (Target t : targets) {
        JSONObject j = new JSONObject();
        j.put("component", t.component);
        j.put("label", t.label);
        j.put("raw", t.raw);
        a.put(j);
      }
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    c.getSharedPreferences("settings", 0).edit().putString("targets", a.toString()).apply();
  }
}

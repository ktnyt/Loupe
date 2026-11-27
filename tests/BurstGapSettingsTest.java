package jp.nano.rawgallery;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class BurstGapSettingsTest {
  public static void main(String[] args) {
    var fixture = new Preferences();
    SharedPreferences settings = fixture.settings();
    check(BurstGapSettings.readMillis(settings) == 1000, "default");
    for (int seconds = 0; seconds <= 3; seconds++) {
      fixture.values.put(BurstGapSettings.LEGACY_SECONDS, seconds);
      check(BurstGapSettings.readMillis(settings) == seconds * 1000, "legacy " + seconds);
      check(!fixture.values.containsKey(BurstGapSettings.MILLIS), "legacy read has no write");
    }
    fixture.values.put(BurstGapSettings.LEGACY_SECONDS, Integer.MAX_VALUE);
    check(BurstGapSettings.readMillis(settings) == 1000, "invalid legacy uses default");
    fixture.values.put(BurstGapSettings.LEGACY_SECONDS, 2);

    fixture.commitSucceeds = false;
    check(!BurstGapSettings.saveMillis(settings, 350), "failed first save reported");
    check(!fixture.values.containsKey(BurstGapSettings.MILLIS), "failed first save removes draft");
    check(BurstGapSettings.readMillis(settings) == 2000, "failed first save retains legacy");
    fixture.commitSucceeds = true;
    check(BurstGapSettings.saveMillis(settings, 350), "exact millisecond save");
    check(BurstGapSettings.readMillis(settings) == 350, "canonical overrides legacy");
    fixture.commitSucceeds = false;
    check(!BurstGapSettings.saveMillis(settings, 0), "failed preset save reported");
    check(BurstGapSettings.readMillis(settings) == 350, "failed change restores canonical value");
    fixture.commitSucceeds = true;
    check(BurstGapSettings.saveMillis(settings, 0), "off preset save");
    check(BurstGapSettings.readMillis(settings) == 0, "canonical off overrides legacy");
    check(fixture.values.get(BurstGapSettings.LEGACY_SECONDS) == 2, "legacy key retained");

    check(BurstGapSettings.parseCustomMillis("1") == 1, "lowest valid input");
    check(BurstGapSettings.parseCustomMillis(" 0350\t") == 350, "trim and leading zero");
    check(BurstGapSettings.parseCustomMillis("2147483647") == Integer.MAX_VALUE, "upper limit");
    for (String value :
        new String[] {
          "",
          " ",
          "0",
          "000",
          "+1",
          "-1",
          "1.5",
          "350 ms",
          "3 5",
          "1e3",
          "３５０",
          "2147483648",
          "9000000000000000000000"
        }) {
      check(BurstGapSettings.parseCustomMillis(value) == 0, "reject invalid input: " + value);
    }
    System.out.println("Burst settings: migration, preferences rollback and custom parsing passed");
  }

  private static void check(boolean condition, String description) {
    if (!condition) throw new AssertionError(description);
  }

  private static final class Preferences {
    final Map<String, Integer> values = new HashMap<>();
    boolean commitSucceeds = true;

    SharedPreferences settings() {
      return (SharedPreferences)
          Proxy.newProxyInstance(
              SharedPreferences.class.getClassLoader(),
              new Class<?>[] {SharedPreferences.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "getInt" -> values.getOrDefault((String) args[0], (Integer) args[1]);
                    case "edit" -> editor();
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }

    SharedPreferences.Editor editor() {
      Map<String, Integer> changes = new HashMap<>();
      return (SharedPreferences.Editor)
          Proxy.newProxyInstance(
              SharedPreferences.Editor.class.getClassLoader(),
              new Class<?>[] {SharedPreferences.Editor.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "putInt":
                    changes.put((String) args[0], (Integer) args[1]);
                    return proxy;
                  case "remove":
                    changes.put((String) args[0], null);
                    return proxy;
                  case "commit":
                  case "apply":
                    // The real Android implementation mutates memory even if commit fails.
                    changes.forEach(
                        (key, value) -> {
                          if (value == null) values.remove(key);
                          else values.put(key, value);
                        });
                    return method.getName().equals("commit") ? commitSucceeds : null;
                  default:
                    throw new UnsupportedOperationException(method.getName());
                }
              });
    }
  }
}

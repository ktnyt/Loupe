package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import java.lang.reflect.*;

/** Exercises actual Activity analysis without enabling automatic writes or changing human tags. */
final class TagLifecycleChecks {
  static Object field(TagActivity a, String name) throws Exception {
    Field f = TagActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(a);
  }
  static void invoke(TagActivity a, String name, Class<?>[] types, Object... args) {
    try { Method m = TagActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(a, args); }
    catch (Exception e) { throw new RuntimeException(e); }
  }
  static String run(Instrumentation test) throws Exception {
    TagActivity activity = (TagActivity) test.startActivitySync(new Intent(test.getTargetContext(), TagActivity.class)
        .putExtra("folder", "DCIM/OMDS/").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      TagStore.Tag tag;
      try (TagStore store = new TagStore(test.getTargetContext())) { tag = store.tags().get(0); }
      test.runOnMainSync(() -> invoke(activity, "openTag", new Class<?>[]{TagStore.Tag.class}, tag));
      waitFor(test, activity, "photos", false);
      test.runOnMainSync(() -> invoke(activity, "startRelated", new Class<?>[]{}));
      waitFor(test, activity, "analyzed", false);
      test.runOnMainSync(() -> activity.onStop());
      waitFor(test, activity, "analysisActive", true);
      int[] before = new int[1], after = new int[1];
      test.runOnMainSync(() -> {
        try {
          before[0] = (Integer) field(activity, "analyzed");
          activity.onRestart(); after[0] = (Integer) field(activity, "analyzed");
        } catch (Exception e) { throw new RuntimeException(e); }
      });
      if (before[0] == 0 || before[0] != after[0]) throw new AssertionError("Progress reset on restart");
      waitFor(test, activity, "analysisFinished", false);
      return "Tag lifecycle passed: pause at image boundary, progress retained on restart, analysis completed; automatic writes disabled\n";
    } finally { test.runOnMainSync(activity::finish); }
  }
  static void waitFor(Instrumentation test, TagActivity a, String name, boolean expectFalse) throws Exception {
    long deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      boolean[] done = new boolean[1];
      test.runOnMainSync(() -> {
        try {
          Object v = field(a, name);
          done[0] = v instanceof Boolean ? ((Boolean)v != expectFalse)
              : v instanceof Integer ? (Integer)v > 0 : !((java.util.List<?>)v).isEmpty();
        } catch (Exception e) { throw new RuntimeException(e); }
      });
      if (done[0]) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Timed out: " + name);
  }
}

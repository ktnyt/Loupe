package jp.nano.rawgallery;

import static jp.nano.rawgallery.TagDetailChecks.require;

import android.content.Context;
import android.os.Process;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/** Two invocations using a generated database name. Production history is never opened. */
final class ShareHistoryRestartChecks {
  private static final long FIRST_TIME = 1792298040000L;
  private static final long SECOND_TIME = FIRST_TIME + 7000;
  private static final String FIRST_PACKAGE = "fixture.restart.first";
  private static final String SECOND_PACKAGE = "fixture.restart.second";
  private static final String FIRST_LABEL = "合成の共有先 1";
  private static final String SECOND_LABEL = "合成の共有先 2";

  static String seed(Context context) throws Exception {
    Path marker = marker(context);
    String id = UUID.randomUUID().toString();
    String database = database(id);
    require(!context.getDatabasePath(database).exists(), "Synthetic restart database starts absent");
    // CREATE_NEW reserves this test slot without overwriting a previous fixture.
    Files.write(marker, List.of(id, Integer.toString(Process.myPid())), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    boolean seeded = false;
    try {
      try (ShareHistoryStore store = new ShareHistoryStore(context, database)) {
        List<String> photos = List.of(key(id, 1), key(id, 2));
        String first = store.begin(photos, FIRST_TIME);
        require(store.complete(first, FIRST_PACKAGE, FIRST_LABEL, FIRST_TIME), "First synthetic handoff");
        String second = store.begin(photos, SECOND_TIME);
        require(store.complete(second, SECOND_PACKAGE, SECOND_LABEL, SECOND_TIME), "Second synthetic handoff");
      }
      seeded = true;
      return "PASS share-history restart seed: saved two destinations for two synthetic photos; pid="
          + Process.myPid() + "\n";
    } finally {
      if (!seeded) {
        context.deleteDatabase(database);
        Files.deleteIfExists(marker);
      }
    }
  }

  static String verify(Context context) throws Exception {
    Path marker = marker(context);
    List<String> fixture = Files.readAllLines(marker, StandardCharsets.UTF_8);
    require(fixture.size() == 2 && fixture.get(0).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        && fixture.get(1).matches("[1-9][0-9]*"), "Valid synthetic restart fixture marker");
    String id = fixture.get(0);
    int seedPid = Integer.parseInt(fixture.get(1));
    int verifyPid = Process.myPid();
    require(seedPid != verifyPid, "Verification runs in another process; fixture retained for retry");
    String database = database(id);
    try {
      require(context.getDatabasePath(database).isFile(), "Previously seeded database exists");
      try (ShareHistoryStore store = new ShareHistoryStore(context, database)) {
        for (int photo : new int[] {1, 2}) {
          List<ShareHistoryStore.Entry> rows = store.history(key(id, photo));
          require(rows.size() == 2, "Two destinations survived the process restart");
          check(rows.get(0), SECOND_PACKAGE, SECOND_LABEL, SECOND_TIME);
          check(rows.get(1), FIRST_PACKAGE, FIRST_LABEL, FIRST_TIME);
        }
        require(store.history(key(id, 3)).isEmpty(), "Unshared synthetic photo has no history");
      }
    } finally {
      if (context.getDatabasePath(database).exists())
        require(context.deleteDatabase(database), "Delete the generated restart database");
      Files.delete(marker);
    }
    return "PASS share-history restart: seedPid=" + seedPid + " verifyPid=" + verifyPid
        + ", exact destination IDs/labels/timestamps for two photos, third empty, fixture cleaned.\n";
  }

  private static void check(ShareHistoryStore.Entry row, String packageName, String label, long time) {
    require(row.packageName.equals(packageName) && row.label.equals(label) && row.handedOffAt == time,
        "Saved destination ID, name, and handoff timestamp match");
  }

  private static Path marker(Context context) {
    return new File(context.getCacheDir(), "share-history-restart-test.marker").toPath();
  }

  private static String database(String id) { return "share-history-restart-test-" + id + ".db"; }

  private static String key(String id, int index) { return "share-history-restart-" + id + "-" + index; }
}

package jp.nano.rawgallery;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Opt-in transfer of the two samples staged in the debug app's private cache. */
final class E2DeviceChecks {
  static String run(Context context, String runId) throws Exception {
    if (runId == null || !runId.matches("[0-9a-f]{32}"))
      throw new IOException("試行IDの形式が不正です");
    File directory = new File(context.getCacheDir(), "e2-pilot");
    File credentials = new File(directory, "credentials");
    List<String> config;
    try {
      if (credentials.length() > 512) throw new IOException("試行設定のサイズが不正です");
      config = Files.readAllLines(credentials.toPath());
    } finally {
      Files.deleteIfExists(credentials.toPath());
    }
    if (config.size() != 3) throw new IOException("試行設定が不足しています");
    String[] formats = {"orf", "jpg"};
    File[] sources = new File[2];
    for (int i = 0; i < formats.length; i++) {
      sources[i] = new File(directory, "sample." + formats[i]);
      if (!sources[i].isFile() || sources[i].length() <= 0 || sources[i].length() > 128L * 1048576)
        throw new IOException("試行原本が不足しているか、サイズ上限を超えています");
    }
    String database = "e2-pilot-" + runId + ".db";
    StringBuilder result = new StringBuilder("PASS e2-archive");
    for (int i = 0; i < formats.length; i++) {
      File source = sources[i];
      String id = UUID.nameUUIDFromBytes((runId + formats[i]).getBytes(StandardCharsets.UTF_8))
          .toString().replace("-", "");
      long put, get;
      try (ArchiveStore archive = new ArchiveStore(context, database)) {
        put = new E2Archive(archive, config.get(0), config.get(1), config.get(2)).save(id, source);
      }
      File restored = new File(directory, "restore-" + id + ".part");
      try (ArchiveStore archive = new ArchiveStore(context, database)) {
        get = new E2Archive(archive, config.get(0), config.get(1), config.get(2))
            .restore(id, restored);
        if (restored.length() != source.length()) throw new IOException("復元サイズが一致しません");
      } finally {
        Files.deleteIfExists(restored.toPath());
      }
      result.append(String.format(Locale.ROOT, "\n%s bytes=%d put_ms=%d get_ms=%d id=%s",
          formats[i], source.length(), put, get, id));
    }
    return result.toString();
  }
}

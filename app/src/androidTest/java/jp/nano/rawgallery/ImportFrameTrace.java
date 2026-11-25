package jp.nano.rawgallery;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Matches a frame of this import even when its request and commit straddle log rotation. */
final class ImportFrameTrace {
  private ImportFrameTrace() {}

  static long firstFrame(File directory, long publishing, Set<Long> photos) throws IOException {
    String previous, current;
    synchronized (DiagnosticLog.FILES) {
      previous = read(new File(directory, "previous.jsonl"));
      current = read(new File(directory, "events.jsonl"));
    }
    return firstFrame(publishing, photos, previous, current);
  }

  private static String read(File file) throws IOException {
    return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
  }

  private record Request(String session, long id) {}

  static long firstFrame(long publishing, Set<Long> photos, String... generations) {
    var requests = new HashSet<Request>();
    for (String generation : generations) {
      for (String line : generation.split("\n")) {
        if (!line.endsWith("}")
            || (!line.contains("image.request") && !line.contains("image.frame_commit"))) continue;
        JSONObject event;
        try {
          event = new JSONObject(line);
        } catch (JSONException partial) {
          continue;
        }
        long time = event.optLong("ns");
        String session = event.optString("session");
        long id = event.optLong("request");
        if (time < publishing || session.isEmpty() || id <= 0) continue;
        Request request = new Request(session, id);
        if (event.optString("event").equals("image.request") && photos.contains(event.optLong("photo"))) {
          requests.add(request);
        } else if (event.optString("event").equals("image.frame_commit") && requests.contains(request)) {
          return time;
        }
      }
    }
    return 0;
  }
}

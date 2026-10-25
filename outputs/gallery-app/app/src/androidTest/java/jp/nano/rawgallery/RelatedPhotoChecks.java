package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.*;

/** Local inference on existing photos; human tags are read only. */
final class RelatedPhotoChecks {
  static String run(Context context) throws Exception {
    long started = System.currentTimeMillis();
    String cache = "feature-test-" + UUID.randomUUID() + ".db";
    try (PhotoFeatures extractor = new PhotoFeatures(context, cache);
        TagStore tags = new TagStore(context); GalleryIndex index = new GalleryIndex(context)) {
      Bitmap bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888);
      for (int y = 0; y < 224; y++) for (int x = 0; x < 224; x++)
        bitmap.setPixel(x, y, Color.rgb(x, y, (x + y) % 256));
      RelatedPhotos.Vector first;
      try {
        first = extractor.embed(bitmap);
        check(first.values.length == 1024, "Unexpected dimension");
        check(first.similarity(extractor.embed(bitmap)) > .999, "Inference not repeatable");
      } finally { bitmap.recycle(); }
      TagJudgments judgments = tags.load();
      int before = judgments.history.size();
      Map<String, String> ids = tags.photoIds();
      List<PhotoRepository.Photo> all = index.load(null, "すべて").photos;
      TagStore.Tag chosen = null;
      String folder = null;
      for (TagStore.Tag tag : tags.tags()) {
        for (PhotoRepository.Photo photo : all) {
          String id = ids.get(TagStore.source(photo));
          if ("content".equals(photo.uri.getScheme()) && id != null
              && judgments.value(tag.id, id) == TagJudgments.Value.ASSIGNED) {
            chosen = tag; folder = photo.folder; break;
          }
        }
        if (chosen != null) break;
      }
      if (chosen == null) return "Model inference passed; no accessible user exemplar for retrieval check\n";
      List<PhotoRepository.Photo> sources = new ArrayList<>();
      List<TagJudgments.Value> labels = new ArrayList<>();
      // Include all exemplars in this folder, then at most 48 other photos.
      for (PhotoRepository.Photo photo : all) {
        if (!"content".equals(photo.uri.getScheme()) || !photo.folder.equals(folder)) continue;
        String id = ids.get(TagStore.source(photo));
        TagJudgments.Value value = id == null ? TagJudgments.Value.UNDECIDED : judgments.value(chosen.id, id);
        if (value != TagJudgments.Value.UNDECIDED) { sources.add(photo); labels.add(value); }
      }
      int examples = sources.size();
      for (PhotoRepository.Photo photo : all) {
        if (sources.size() >= examples + 48) break;
        if (!"content".equals(photo.uri.getScheme()) || !photo.folder.equals(folder) || sources.contains(photo)) continue;
        sources.add(photo); labels.add(TagJudgments.Value.UNDECIDED);
      }
      Map<Integer, RelatedPhotos.Vector> vectors = new HashMap<>();
      int failures = 0;
      for (int i = 0; i < sources.size(); i++) {
        try { vectors.put(i, extractor.obtain(sources.get(i))); }
        catch (Exception e) { failures++; }
      }
      RelatedPhotos.Candidate candidate = RelatedPhotos.next(vectors, labels, Set.of(), 0);
      check(candidate != null, "No related candidate from user's exemplars");
      check(labels.get(candidate.photo) == TagJudgments.Value.UNDECIDED, "Already judged candidate");
      check(vectors.get(candidate.photo).similarity(extractor.cached(sources.get(candidate.photo))) > .999, "Cache mismatch");
      try (PhotoFeatures reopened = new PhotoFeatures(context, cache)) {
        check(reopened.cached(sources.get(candidate.photo)) != null, "Cache did not survive reopen");
      }
      check(tags.load().history.size() == before, "User history changed");
      return "Local model/retrieval passed: dimension=1024, analyzed=" + vectors.size() + ", failed=" + failures
          + ", examples=" + examples + ", candidate=unjudged, cache=reopened, user_history=unchanged, elapsed_ms="
          + (System.currentTimeMillis() - started) + "\n";
    } finally { context.deleteDatabase(cache); }
  }
  private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

package jp.nano.rawgallery;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/** Bounded, recoverable originals. Durable proxies are stored elsewhere. */
final class OriginalCache {
  private static final long LIMIT = 512L * 1024 * 1024;

  static synchronized PhotoRepository.Photo obtain(Context c, PhotoRepository.Photo p)
      throws Exception {
    File dir = new File(c.getCacheDir(), "originals");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("原本用の空き領域を作れません");
    String key = ArchiveStore.key(p);
    File target =
        new File(
            dir,
            key + "." + RawFormats.originalExtension(p.name));
    JSONObject record = null;
    if ("archive".equals(p.uri.getScheme())) {
      try (ArchiveStore store = new ArchiveStore(c)) {
        record = store.get(p.uri.getHost());
      }
      if (record == null || !record.has("sha")) throw new IOException("原本の保存記録がありません");
    }
    if (target.isFile()
        && target.length() == p.size
        && (record == null || record.getString("sha").equals(hash(target)))) {
      target.setLastModified(System.currentTimeMillis());
      trim(dir, target, 0);
      return local(p, target);
    }
    target.delete();
    if (p.size <= 0 || p.size > LIMIT) throw new IOException("原本の一時保存上限は512 MiBです");
    trim(dir, null, p.size);
    File partial = new File(dir, key + ".part");
    try {
      if (record != null) {
        String cookies =
            CookieManager.getInstance().getCookie(CloudVerifier.ORIGIN + "/drive/v1/nodes");
        if (cookies == null || cookies.isBlank()) throw new IOException("Amazonへの再ログインが必要です");
        new CloudVerifier(
                cookies, c.getSharedPreferences("settings", 0).getString("cloudUserAgent", ""))
            .download(record, partial);
      } else {
        try (InputStream in = PhotoRepository.original(c, p);
            FileOutputStream out = new FileOutputStream(partial)) {
          byte[] buffer = new byte[65536];
          long count = 0;
          int n;
          while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("原本の取得を中断しました");
            count += n;
            if (count > p.size) throw new IOException("原本のサイズが変わりました");
            out.write(buffer, 0, n);
          }
          if (count != p.size) throw new IOException("原本のサイズが変わりました");
          out.getFD().sync();
        }
      }
      Files.move(
          partial.toPath(),
          target.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      return local(p, target);
    } finally {
      partial.delete();
    }
  }

  private static PhotoRepository.Photo local(PhotoRepository.Photo p, File f) {
    return new PhotoRepository.Photo(
        Uri.fromFile(f), p.name, p.folder, p.size, p.modified, p.taken, p.added, p.created);
  }

  private static String hash(File f) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(f)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) digest.update(b, 0, n);
    }
    StringBuilder value = new StringBuilder();
    for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b));
    return value.toString();
  }

  private static void trim(File dir, File keep, long reserve) throws IOException {
    File[] files = dir.listFiles();
    if (files == null) throw new IOException("原本の一時領域を読めません");
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    long total = reserve;
    for (File f : files) total += f.length();
    for (File f : files)
      if (!f.equals(keep)
          && (total > LIMIT
              || f.lastModified() < System.currentTimeMillis() - 86400000L
              || f.getName().endsWith(".part"))) {
        long bytes = f.length();
        if (f.delete()) total -= bytes;
      }
    if (total > LIMIT) throw new IOException("原本の一時保存容量が足りません");
  }
}

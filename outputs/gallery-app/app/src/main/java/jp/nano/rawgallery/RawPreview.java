package jp.nano.rawgallery;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads only declared TIFF/ORF JPEG ranges. Never decodes or changes the raw sensor data. */
public final class RawPreview {
  public interface Source {
    long length() throws IOException;

    void read(long offset, byte[] data) throws IOException;
  }

  public static final class FileSource implements Source, AutoCloseable {
    private final RandomAccessFile file;

    public FileSource(File path) throws IOException {
      file = new RandomAccessFile(path, "r");
    }

    public long length() throws IOException {
      return file.length();
    }

    public void read(long offset, byte[] data) throws IOException {
      file.seek(offset);
      file.readFully(data);
    }

    public void close() throws IOException {
      file.close();
    }
  }

  public static final class Result {
    public final long offset;
    public final int length, width, height, orientation;
    public final String model;
    public final long taken;

    Result(long o, int n, int w, int h, int rotation, String camera) {
      this(o, n, w, h, rotation, camera, 0);
    }

    Result(long o, int n, int w, int h, int rotation, String camera, long capturedAt) {
      taken = capturedAt;
      offset = o;
      length = n;
      width = w;
      height = h;
      orientation = rotation;
      model = camera;
    }

    public byte[] jpeg(Source source) throws IOException {
      byte[] data = new byte[length];
      source.read(offset, data);
      return data;
    }
  }

  private final Source source;
  private final long size;
  private boolean little;
  private int orientation = 1;
  private String model = "不明なカメラ", capturedText = "", capturedOffset = "";
  private final Set<String> visited = new HashSet<>();
  private final List<Result> candidates = new ArrayList<>();
  private static final int MAX_JPEG = 32 * 1024 * 1024;

  private RawPreview(Source s) throws IOException {
    source = s;
    size = s.length();
  }

  public static Result inspect(Source source) throws IOException {
    RawPreview p = new RawPreview(source);
    byte[] h = p.bytes(0, 8);
    if (h[0] == 'I' && h[1] == 'I') p.little = true;
    else if (h[0] == 'M' && h[1] == 'M') p.little = false;
    else throw new IOException("TIFF/ORF形式ではありません");
    int magic = p.u16(h, 2);
    if (magic != 42 && magic != 0x4f52 && magic != 0x5352) throw new IOException("未対応のRAW形式です");
    p.directory(p.u32(h, 4), 0, 0, 0);
    Result best =
        p.candidates.stream()
            .max(Comparator.comparingLong(r -> (long) r.width * r.height))
            .orElseThrow(() -> new IOException("埋め込みJPEGが見つかりません"));
    return new Result(
        best.offset,
        best.length,
        best.width,
        best.height,
        p.orientation,
        p.model,
        captureTime(p.capturedText, p.capturedOffset));
  }

  static long captureTime(String text, String offset) {
    try {
      java.time.LocalDateTime time =
          java.time.LocalDateTime.parse(
              text,
              java.time.format.DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss")
                  .withResolverStyle(java.time.format.ResolverStyle.STRICT));
      return offset != null && offset.matches("[+-][0-9]{2}:[0-9]{2}")
          ? time.toInstant(java.time.ZoneOffset.of(offset)).toEpochMilli()
          : time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    } catch (Exception e) {
      return 0;
    }
  }

  private byte[] bytes(long offset, int n) throws IOException {
    if (offset < 0 || n < 0 || offset > size || n > size - offset)
      throw new IOException("RAW内の参照範囲が不正です");
    byte[] b = new byte[n];
    source.read(offset, b);
    return b;
  }

  private int u16(byte[] b, int i) {
    return little ? (b[i] & 255) | ((b[i + 1] & 255) << 8) : ((b[i] & 255) << 8) | (b[i + 1] & 255);
  }

  private long u32(byte[] b, int i) {
    return little
        ? (b[i] & 255L)
            | ((b[i + 1] & 255L) << 8)
            | ((b[i + 2] & 255L) << 16)
            | ((b[i + 3] & 255L) << 24)
        : ((b[i] & 255L) << 24)
            | ((b[i + 1] & 255L) << 16)
            | ((b[i + 2] & 255L) << 8)
            | (b[i + 3] & 255L);
  }

  private final class Entry {
    final int type;
    final long count, value, slot, base;

    Entry(byte[] e, long pos, long origin) {
      type = u16(e, 2);
      count = u32(e, 4);
      value = u32(e, 8);
      slot = pos + 8;
      base = origin;
    }

    int unit() {
      return switch (type) {
        case 1, 2, 7 -> 1;
        case 3 -> 2;
        case 4, 13 -> 4;
        default -> 0;
      };
    }

    long location() throws IOException {
      int unit = unit();
      if (unit == 0 || count > Integer.MAX_VALUE / unit) throw new IOException("不正なTIFF値です");
      return count * unit <= 4 ? slot : base + value;
    }

    long[] numbers() throws IOException {
      if (count > 1024 || (type != 3 && type != 4 && type != 13))
        throw new IOException("不正なTIFF配列です");
      byte[] b = bytes(location(), (int) count * unit());
      long[] v = new long[(int) count];
      for (int i = 0; i < v.length; i++) v[i] = type == 3 ? u16(b, i * 2) : u32(b, i * 4);
      return v;
    }

    long number() throws IOException {
      long[] n = numbers();
      return n.length == 0 ? 0 : n[0];
    }

    String text() throws IOException {
      if (type != 2 || count > 512) return "";
      return new String(bytes(location(), (int) count), StandardCharsets.US_ASCII)
          .replace("\0", "")
          .trim();
    }
  }

  private long num(Map<Integer, Entry> entries, int tag) throws IOException {
    Entry e = entries.get(tag);
    return e == null ? 0 : e.number();
  }

  // kind: 0 = TIFF, 1 = Olympus maker directory, 2 = Olympus camera settings.
  private void directory(long offset, long base, int kind, int depth) throws IOException {
    if (offset == 0) return;
    if (depth > 12 || visited.size() > 64 || !visited.add(base + ":" + offset + ":" + kind))
      throw new IOException("RAWの循環参照または過剰な階層です");
    int count = u16(bytes(offset, 2), 0);
    if (count > 512) throw new IOException("RAWの項目数が過大です");
    byte[] block = bytes(offset + 2, count * 12 + 4);
    Map<Integer, Entry> entries = new HashMap<>();
    for (int i = 0; i < count; i++) {
      byte[] e = Arrays.copyOfRange(block, i * 12, i * 12 + 12);
      entries.put(u16(e, 0), new Entry(e, offset + 2 + i * 12, base));
    }
    if (kind == 0) {
      if (entries.containsKey(36867)) capturedText = entries.get(36867).text();
      if (entries.containsKey(36881)) capturedOffset = entries.get(36881).text();
      if (entries.containsKey(272)) {
        String v = entries.get(272).text();
        if (!v.isEmpty()) model = v;
      }
      if (depth == 0 && entries.containsKey(274)) {
        int o = (int) num(entries, 274);
        if (o >= 1 && o <= 8) orientation = o;
      }
      if (entries.containsKey(513) && entries.containsKey(514))
        candidate(base + num(entries, 513), num(entries, 514));
      if (num(entries, 259) == 7
          && (num(entries, 262) == 6 || num(entries, 262) == 2)
          && entries.containsKey(273)
          && entries.containsKey(279)) {
        long[] offsets = entries.get(273).numbers(), lengths = entries.get(279).numbers();
        if (offsets.length == 1 && lengths.length == 1) candidate(base + offsets[0], lengths[0]);
      }
      if (entries.containsKey(330))
        for (long sub : entries.get(330).numbers()) directory(base + sub, base, 0, depth + 1);
      if (entries.containsKey(34665)) directory(base + num(entries, 34665), base, 0, depth + 1);
      Entry maker = entries.get(37500);
      if (maker != null && maker.count >= 12) {
        long start = maker.location();
        byte[] header = bytes(start, (int) Math.min(maker.count, 16));
        int headerLength =
            new String(header, 0, 8, StandardCharsets.US_ASCII).equals("OLYMPUS\0")
                    && header[8] == 'I'
                    && header[9] == 'I'
                ? 12
                : header.length >= 16
                        && new String(header, 0, 9, StandardCharsets.US_ASCII).equals("OM SYSTEM")
                        && header[12] == 'I'
                        && header[13] == 'I'
                    ? 16
                    : 0;
        if (headerLength > 0) {
          boolean old = little;
          little = true;
          try {
            directory(start + headerLength, start, 1, depth + 1);
          } finally {
            little = old;
          }
        }
      }
      long next = u32(block, count * 12);
      if (next != 0) directory(base + next, base, 0, depth + 1);
    } else if (kind == 1) {
      if (entries.containsKey(0x2020)) directory(base + num(entries, 0x2020), base, 2, depth + 1);
    } else if (num(entries, 0x100) == 1) candidate(base + num(entries, 0x101), num(entries, 0x102));
  }

  private void candidate(long offset, long length) throws IOException {
    if (length < 16 || length > MAX_JPEG) return;
    byte[] h = bytes(offset, (int) Math.min(length, 262144));
    if ((h[0] & 255) != 255 || (h[1] & 255) != 216) return;
    int p = 2;
    while (p + 4 <= h.length) {
      if ((h[p++] & 255) != 255) return;
      while (p < h.length && (h[p] & 255) == 255) p++;
      if (p >= h.length) return;
      int marker = h[p++] & 255;
      if (marker == 0xda || marker == 0xd9) return;
      if (marker == 1 || (marker >= 0xd0 && marker <= 0xd7)) continue;
      if (p + 2 > h.length) return;
      int n = ((h[p] & 255) << 8) | (h[p + 1] & 255);
      if (n < 2 || p + n > h.length) return;
      if (marker == 0xc0 || marker == 0xc1 || marker == 0xc2) {
        if (n < 8 || (h[p + 2] & 255) != 8) return;
        int height = ((h[p + 3] & 255) << 8) | (h[p + 4] & 255),
            width = ((h[p + 5] & 255) << 8) | (h[p + 6] & 255),
            components = h[p + 7] & 255;
        if (width < 1
            || height < 1
            || (long) width * height > 120_000_000L
            || (components != 1 && components != 3)) return;
        byte[] end = bytes(offset + length - 2, 2);
        if ((end[0] & 255) != 255 || (end[1] & 255) != 217) return;
        candidates.add(new Result(offset, (int) length, width, height, orientation, model));
        return;
      }
      p += n;
    }
  }
}

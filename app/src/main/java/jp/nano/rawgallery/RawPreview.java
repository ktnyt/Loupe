package jp.nano.rawgallery;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Extracts complete embedded JPEG streams without decoding or changing raw sensor data. */
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
    public final boolean repairedHeader;

    Result(long o, int n, int w, int h, int rotation, String camera) {
      this(o, n, w, h, rotation, camera, 0);
    }

    Result(long o, int n, int w, int h, int rotation, String camera, long capturedAt) {
      this(o, n, w, h, rotation, camera, capturedAt, false);
    }

    Result(long o, int n, int w, int h, int rotation, String camera, long capturedAt,
        boolean repaired) {
      repairedHeader = repaired;
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
      if (repairedHeader) data[0] = (byte) 255;
      return data;
    }
  }

  private final Source source;
  private final long size;
  private boolean little;
  private int orientation = 1;
  private boolean orientationKnown, repairPreviewHeader;
  private String model = "不明なカメラ", capturedText = "", capturedOffset = "";
  private final Set<String> visited = new HashSet<>();
  private final List<Result> candidates = new ArrayList<>();
  private static final int MAX_JPEG = 32 * 1024 * 1024;

  private RawPreview(Source s) throws IOException {
    source = s;
    size = s.length();
    if (size < 0) throw new IOException("RAWのサイズを取得できません");
  }

  public static Result inspect(Source source) throws IOException {
    RawPreview p = new RawPreview(source);
    // Metadata is optional: proprietary or damaged directories must not hide a valid JPEG.
    try {
      byte[] h = p.bytes(0, 8);
      p.repairPreviewHeader = h[0] == 0 && h[1] == 'M' && h[2] == 'R' && h[3] == 'M';
      if ((h[0] == 'I' && h[1] == 'I') || (h[0] == 'M' && h[1] == 'M')) {
        p.little = h[0] == 'I';
        int magic = p.u16(h, 2);
        if (magic == 42 || magic == 0x4f52 || magic == 0x5352 || magic == 85)
          p.directory(p.u32(h, 4), 0, 0, 0);
      }
    } catch (IOException ignored) {
      // The stream scan below still propagates source I/O failures and cancellation.
    }
    p.scan();
    Result best =
        p.candidates.stream()
            .max(Comparator.comparingLong(r -> (long) r.width * r.height))
            .orElseThrow(() -> new IOException("埋め込みJPEGが見つかりません"));
    p.jpegMetadata(best);
    return new Result(
        best.offset,
        best.length,
        best.width,
        best.height,
        p.orientation,
        p.model,
        captureTime(p.capturedText, p.capturedOffset), best.repairedHeader);
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
      if (entries.containsKey(271)) {
        String make = entries.get(271).text().toUpperCase(Locale.ROOT);
        if (make.contains("EPSON") || make.contains("MINOLTA")) repairPreviewHeader = true;
      }
      if (entries.containsKey(36867)) capturedText = entries.get(36867).text();
      if (entries.containsKey(36881)) capturedOffset = entries.get(36881).text();
      if (entries.containsKey(272)) {
        String v = entries.get(272).text();
        if (!v.isEmpty()) model = v;
      }
      if (depth == 0 && entries.containsKey(274)) {
        int o = (int) num(entries, 274);
        if (o >= 1 && o <= 8) { orientation = o; orientationKnown = true; }
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
    if (offset < 0 || length < 16 || length > MAX_JPEG || offset > size - length) return;
    // All candidates, including declared TIFF ranges, use the same complete-stream validator.
    Result r = new JpegReader().parse(offset, offset + length);
    if (r != null) candidates.add(r);
  }

  private void scan() throws IOException {
    candidates.clear();
    JpegReader reader = new JpegReader();
    long pos = 0;
    int previous = -1;
    while (pos < size) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("RAW抽出を中断しました");
      byte[] block = bytes(pos, (int) Math.min(65536, size - pos));
      boolean found = false;
      for (int i = 0; i < block.length; i++) {
        int value = block[i] & 255;
        int before = i == 0 ? previous : block[i - 1] & 255;
        if (value != 216 || before < 0 || (before != 255 && !repairPreviewHeader)) continue;
        long start = pos + i - 1;
        Result r = reader.parse(start, start + Math.min(size - start, MAX_JPEG));
        if (r != null) {
          // Retain only the largest complete stream, preserving first-on-tie ordering.
          if (candidates.isEmpty()
              || (long) r.width * r.height > (long) candidates.get(0).width * candidates.get(0).height) {
            candidates.clear();
            candidates.add(r);
          }
          pos = r.offset + r.length;
          previous = 217; // The validated stream ends at EOI; resume after its payload.
          found = true;
          break;
        }
      }
      if (!found) {
        previous = block[block.length - 1] & 255;
        pos += block.length;
      }
    }
  }

  /** Buffered random access; JPEG payloads are never allocated during inspection. */
  private final class JpegReader {
    private byte[] buffer = new byte[0];
    private long start = -1;
    // Overlapping malformed candidates must not turn a linear scan into unbounded re-parsing.
    private long budget = size > Long.MAX_VALUE / 8 ? Long.MAX_VALUE : Math.max(1_048_576, size * 8);

    int at(long offset) throws IOException {
      if (--budget < 0) throw new IOException("JPEG探索の処理量上限を超えました");
      if (offset < 0 || offset >= size) throw new EOFException();
      if (start < 0 || offset < start || offset - start >= buffer.length) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("RAW抽出を中断しました");
        start = offset;
        buffer = bytes(start, (int) Math.min(65536, size - start));
      }
      return buffer[(int) (offset - start)] & 255;
    }

    int word(long offset) throws IOException {
      return (at(offset) << 8) | at(offset + 1);
    }

    Result parse(long offset, long end) throws IOException {
      if (end - offset < 4 || at(offset + 1) != 216) return null;
      boolean repair = at(offset) != 255;
      if (repair && !(repairPreviewHeader && at(offset + 2) == 255 && at(offset + 3) == 219)) return null;
      long pos = offset + 2;
      int width = 0, height = 0, components = 0;
      boolean entropy = false, scan = false, payload = false;
      while (pos < end) {
        int value = at(pos++);
        if (value != 255) {
          if (!entropy) return null;
          payload = true;
          continue;
        }
        while (pos < end && at(pos) == 255) pos++;
        if (pos >= end) return null;
        int marker = at(pos++);
        if (entropy && marker == 0) { payload = true; continue; }
        if (entropy && marker >= 0xd0 && marker <= 0xd7) continue;
        if (marker == 0xd9) {
          return width > 0 && scan && payload
              ? new Result(offset, (int) (pos - offset), width, height, orientation, model, 0, repair) : null;
        }
        entropy = false;
        if (marker == 0 || marker == 0xd8 || (marker >= 0xd0 && marker <= 0xd7)) return null;
        if (marker == 1) continue;
        if (pos + 2 > end) return null;
        int length = word(pos);
        if (length < 2 || length > end - pos) return null;
        if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
          // Lossless sensor JPEG (SOF3 etc.) is not a display preview.
          if (marker != 0xc0 && marker != 0xc1 && marker != 0xc2) return null;
          if (width != 0 || length < 8 || at(pos + 2) != 8) return null;
          height = word(pos + 3);
          width = word(pos + 5);
          components = at(pos + 7);
          if (width == 0 || height == 0 || (long) width * height > 120_000_000L
              || (components != 1 && components != 3) || length != 8 + 3 * components) return null;
        }
        if (marker == 0xda) {
          if (width == 0 || length < 6) return null;
          int count = at(pos + 2);
          if (count < 1 || count > components || length != 6 + 2 * count) return null;
          entropy = true;
          scan = true;
        }
        pos += length;
      }
      return null;
    }
  }

  private void jpegMetadata(Result result) throws IOException {
    // RAF/RW2/CR3/X3F often keep orientation and capture time in the selected JPEG's EXIF.
    long pos = result.offset + 2, end = result.offset + result.length;
    JpegReader reader = new JpegReader();
    while (pos + 4 <= end && reader.at(pos++) == 255) {
      int marker = reader.at(pos++);
      if (marker == 0xda || marker == 0xd9) break;
      if (marker == 1) continue;
      int length = reader.word(pos);
      if (length < 2 || length > end - pos) break;
      if (marker == 0xe1 && length >= 16
          && Arrays.equals(bytes(pos + 2, 6), new byte[] {'E', 'x', 'i', 'f', 0, 0})) {
        final long base = pos + 8, limit = pos + length - base;
        RawPreview exif = new RawPreview(new Source() {
          public long length() { return limit; }
          public void read(long offset, byte[] data) throws IOException { source.read(base + offset, data); }
        });
        try {
          byte[] h = exif.bytes(0, 8);
          if (!((h[0] == 'I' && h[1] == 'I') || (h[0] == 'M' && h[1] == 'M'))) break;
          exif.little = h[0] == 'I';
          if (exif.u16(h, 2) != 42) break;
          exif.directory(exif.u32(h, 4), 0, 0, 0);
        } catch (IOException ignored) { /* Optional EXIF may be incomplete. */ }
        if (!orientationKnown) orientation = exif.orientation;
        if (model.equals("不明なカメラ")) model = exif.model;
        if (capturedText.isEmpty()) capturedText = exif.capturedText;
        if (capturedOffset.isEmpty()) capturedOffset = exif.capturedOffset;
        break;
      }
      pos += length;
    }
  }
}

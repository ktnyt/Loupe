package jp.nano.rawgallery;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class BurstFrameTest {
  public static void main(String[] args) throws Exception {
    byte[] modern = BurstMakerNoteFixture.makerNote(true, 3, 1, 2, 0, 0, 4, 0x49);
    byte[] legacy = BurstMakerNoteFixture.makerNote(false, 3, 1, 1);
    var number = RawPreview.readBurstFrame(modern);
    check(number.number() == 2 && number.mode() > 0, "OM SYSTEM ProCap SH2");
    var legacyFrame = RawPreview.readBurstFrame(legacy);
    check(
        legacyFrame.number() == 1 && legacyFrame.mode() > 0 && legacyFrame.mode() != number.mode(),
        "legacy inline count and independent mode");
    check(number.mode() != read(1, 2, 0, 0, 4, 0x48).mode(), "different modern modes");
    check(number.mode() != read(1, 2, 0, 0, 2, 0x49).mode(), "different shutters");
    for (int mode :
        new int[] {
          0x02, 0x03, 0x07, 0x12, 0x13, 0x17, 0x22, 0x23, 0x27, 0x28, 0x29, 0x41, 0x42, 0x43, 0x48,
          0x49
        }) {
      check(read(1, 65535, 0, 0, 4, mode).number() == 65535, "modern supported mode " + mode);
    }
    for (int[] absent :
        new int[][] {
          {0, 0, 0, 0, 0, 1},
          {0, 2},
          {5, 2, 1, 0, 4, 0x49},
          {1, 0},
          {1, 2, 0, 0},
          {1, 2, 0, 0, 4, 0x30},
          {1, 2, 0, 0, 4, 0},
          {1, 2, 0, 0, 4, 0x99}
        }) {
      check(
          read(absent).equals(RawPreview.BurstFrame.NONE),
          "unsupported " + Arrays.toString(absent));
    }
    check(
        RawPreview.readBurstFrame(BurstMakerNoteFixture.makerNote(false, 4, 1, 2))
            .equals(RawPreview.BurstFrame.NONE),
        "wrong TIFF type");
    check(
        RawPreview.readBurstFrame(Arrays.copyOf(modern, 67)).equals(RawPreview.BurstFrame.NONE),
        "truncated and out of note range");
    byte[] bad = modern.clone();
    ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(26, 0x7fff_ffff);
    check(
        RawPreview.readBurstFrame(bad).equals(RawPreview.BurstFrame.NONE), "invalid IFD reference");
    byte[] raw = BurstMakerNoteFixture.tiff(modern, true);
    check(
        RawPreview.readBurstFrame(
                new RawPreview.Source() {
                  public long length() {
                    return 200_000_000;
                  }

                  public void read(long offset, byte[] data) throws IOException {
                    if (offset + data.length > raw.length)
                      throw new IOException("unexpected payload read");
                    System.arraycopy(raw, (int) offset, data, 0, data.length);
                  }
                })
            .equals(number),
        "ORF TIFF and MakerNote only, no RAW or JPEG payload access");
    try {
      RawPreview.readBurstFrame(
          new RawPreview.Source() {
            public long length() {
              return raw.length;
            }

            public void read(long offset, byte[] data) throws IOException {
              throw new IOException("read failed");
            }
          });
      throw new AssertionError("source I/O failure must remain retryable");
    } catch (IOException expected) {
      check(expected.getMessage().equals("read failed"), "underlying I/O failure propagated");
    }
    for (int i = 0; i < args.length; i++) {
      try (RawPreview.FileSource source = new RawPreview.FileSource(new File(args[i]))) {
        var frame = RawPreview.readBurstFrame(source);
        check(
            i < 3
                ? frame.number() == i + 1 && frame.mode() == number.mode()
                : frame.equals(RawPreview.BurstFrame.NONE),
            "ORF sample " + (i + 1));
      }
    }
    System.out.println(
        "Burst frame metadata: synthetic structure, modes, malformed fields, retry, "
            + "metadata-only read and "
            + args.length
            + " optional ORF samples passed");
  }

  static RawPreview.BurstFrame read(int... values) throws IOException {
    return RawPreview.readBurstFrame(BurstMakerNoteFixture.makerNote(true, 3, values));
  }

  static void check(boolean passed, String label) {
    if (!passed) throw new AssertionError(label);
  }
}

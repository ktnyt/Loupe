package jp.nano.rawgallery;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Synthetic EXIF and Olympus relative IFDs with no camera or personal identifiers. */
final class BurstMakerNoteFixture {
  static byte[] makerNote(boolean omSystem, int type, int... driveMode) {
    ByteBuffer note = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
    note.put(
        (omSystem ? "OM SYSTEM\0\0\0II\0\0" : "OLYMPUS\0II\0\0")
            .getBytes(StandardCharsets.US_ASCII));
    note.putShort((short) 1);
    item(note, 0x2020, 13, 1, 32);
    note.position(32);
    note.putShort((short) 1);
    item(note, 0x0600, type, driveMode.length, 64);
    // A TIFF value no larger than 4 bytes lives in the IFD entry slot.
    note.position(type == 3 && driveMode.length <= 2 ? 42 : 64);
    for (int value : driveMode) note.putShort((short) value);
    return note.array();
  }

  static byte[] tiff(byte[] note, boolean orf) {
    return tiff(note, orf, "2026:11:24 12:34:56");
  }

  static byte[] tiff(byte[] note, boolean orf, String dateTime) {
    ByteBuffer tiff = ByteBuffer.allocate(96 + note.length).order(ByteOrder.LITTLE_ENDIAN);
    tiff.put((byte) 'I').put((byte) 'I').putShort((short) (orf ? 0x4f52 : 42)).putInt(8);
    tiff.putShort((short) 2);
    item(tiff, 272, 2, 14, 40);
    item(tiff, 34665, 4, 1, 56);
    tiff.position(40);
    tiff.put("Loupe Fixture\0".getBytes(StandardCharsets.US_ASCII));
    tiff.position(56);
    tiff.putShort((short) 2);
    item(tiff, 36867, 2, 20, 96 + note.length);
    item(tiff, 37500, 7, note.length, 96);
    tiff.position(96);
    tiff.put(note);
    byte[] withTime = java.util.Arrays.copyOf(tiff.array(), tiff.capacity() + 20);
    System.arraycopy(
        (dateTime + "\0").getBytes(StandardCharsets.US_ASCII), 0, withTime, tiff.capacity(), 20);
    return withTime;
  }

  static byte[] jpeg(byte[] pixels, byte[] tiff) {
    ByteBuffer jpeg = ByteBuffer.allocate(pixels.length + 10 + tiff.length);
    jpeg.put(pixels, 0, 2).putShort((short) 0xffe1).putShort((short) (8 + tiff.length));
    jpeg.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII)).put(tiff);
    jpeg.put(pixels, 2, pixels.length - 2);
    return jpeg.array();
  }

  private static void item(ByteBuffer bytes, int tag, int type, int count, int value) {
    bytes.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
  }
}

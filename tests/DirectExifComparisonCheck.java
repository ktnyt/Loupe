package jp.nano.rawgallery.camera;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class DirectExifComparisonCheck {
    private static final int OFFSET_ENTRY = 40;
    private static final int OFFSET_DATA = 100;
    private static final int OFFSET_BYTES = 7;
    private static final int PADDING = 0xEA1C;
    private static final String BROKEN = "   :  \0";

    public static void main(String[] args) {
        for (ByteOrder order : new ByteOrder[] { ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN }) {
            byte[] source = tiff(order, BROKEN, "-03:30\0", BROKEN);
            byte[] saved = tiff(order, "+09:00\0", "-03:30\0", "-04:00\0");
            byte[] savedBefore = saved.clone();
            match(source, source.clone(), true, "exact prefix");
            match(source, saved, true, "only broken source offsets changed");
            match(jpeg(source), jpeg(saved), true, "JPEG APP1 offset repair");
            require(Arrays.equals(source, tiff(order, BROKEN, "-03:30\0", BROKEN))
                    && Arrays.equals(saved, savedBefore), "input prefixes were changed");

            byte[] unrelatedChange = saved.clone();
            unrelatedChange[unrelatedChange.length - 1] ^= 1;
            match(source, unrelatedChange, false, "unrelated byte changed");
            byte[] validChanged = tiff(order, "+09:00\0", "+00:00\0", "-04:00\0");
            match(source, validChanged, false, "already-valid source offset changed");
            match(saved, source, false, "originally valid offsets must survive");
            byte[] differentlyBroken = tiff(order, "abc:de\0", "-03:30\0", BROKEN);
            match(source, differentlyBroken, false, "unverified broken saved offsets cannot collapse to +00:00");

            byte[] blank = tiff(order, BROKEN, BROKEN, BROKEN);
            byte[] legacy = blank.clone();
            ByteBuffer table = ByteBuffer.wrap(legacy).order(order);
            for (int i = 0; i < 3; i++) table.putShort(OFFSET_ENTRY + i * 12, (short) PADDING);
            match(blank, legacy, true, "legacy blank OffsetTime stored as padding");
            match(saved, legacy, false, "legacy padding cannot hide a valid source offset");

            int truncated = OFFSET_DATA + 2 * OFFSET_BYTES + 3;
            match(Arrays.copyOf(source, truncated), Arrays.copyOf(saved, truncated), false,
                    "incomplete last OffsetTime value");
            match(source, Arrays.copyOf(saved, saved.length - 1), false, "unequal prefix lengths");
        }
        match(new byte[0], new byte[0], false, "missing prefixes");
        System.out.println("PASS bounded EXIF comparison for repaired and legacy padded offsets");
    }

    private static void match(byte[] source, byte[] saved, boolean expected, String scenario) {
        require(ExifOffsetFix.sameAfterOffsetRepair(source, saved) == expected, scenario);
    }

    private static void require(boolean condition, String scenario) {
        if (!condition) throw new AssertionError(scenario);
    }

    private static byte[] tiff(ByteOrder order, String... offsets) {
        ByteBuffer bytes = ByteBuffer.allocate(128).order(order);
        bytes.putShort(order == ByteOrder.LITTLE_ENDIAN ? (short) 0x4949 : (short) 0x4D4D);
        bytes.putShort((short) 42).putInt(8);
        bytes.putShort((short) 1);
        entry(bytes, 0x8769, 4, 1, 26);
        bytes.putInt(0);
        bytes.putShort((short) 4);
        entry(bytes, 0x9003, 2, 20, 1_000_000);
        for (int i = 0; i < offsets.length; i++) {
            entry(bytes, 0x9010 + i, 2, OFFSET_BYTES, OFFSET_DATA + i * OFFSET_BYTES);
        }
        bytes.position(OFFSET_DATA);
        for (String offset : offsets) bytes.put(offset.getBytes(StandardCharsets.US_ASCII));
        return bytes.array();
    }

    private static void entry(ByteBuffer bytes, int tag, int type, int count, int value) {
        bytes.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
    }

    private static byte[] jpeg(byte[] tiff) {
        return ByteBuffer.allocate(12 + tiff.length + 2)
                .putShort((short) 0xFFD8).putShort((short) 0xFFE1).putShort((short) (8 + tiff.length))
                .put("Exif\0\0".getBytes(StandardCharsets.US_ASCII)).put(tiff).putShort((short) 0xFFD9)
                .array();
    }
}

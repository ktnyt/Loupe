package jp.nano.rawgallery.camera;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable metadata for one received camera file; source identity is not content identity. */
public final class CameraImportFile {
    public static final long MAX_BYTES = 512L * 1024L * 1024L;

    private static final int MAX_SOURCE_ID_BYTES = 512;
    private static final int MAX_PATH_BYTES = 4096;
    private static final int MAX_STAMP_BYTES = 512;
    private static final byte[] SOURCE_KEY_DOMAIN =
            "Loupe.CameraImportFile.sourceKey.v1".getBytes(StandardCharsets.US_ASCII);

    public final String sourceId;
    public final String relativePath;
    public final String name;
    public final long bytes;
    public final String sourceStamp;
    public final long captureSeconds;

    public CameraImportFile(
            String sourceId,
            String relativePath,
            long bytes,
            String sourceStamp,
            long captureSeconds) {
        this.sourceId = boundedText(sourceId, "sourceId", MAX_SOURCE_ID_BYTES, false);
        this.relativePath = validatePath(relativePath);
        this.name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        if (bytes <= 0 || bytes > MAX_BYTES) {
            throw new IllegalArgumentException("bytes must be between 1 and 512 MiB");
        }
        this.bytes = bytes;
        this.sourceStamp = boundedText(sourceStamp, "sourceStamp", MAX_STAMP_BYTES, true);
        if (captureSeconds < 0) throw new IllegalArgumentException("captureSeconds must not be negative");
        this.captureSeconds = captureSeconds;
    }

    /** Hashes the transport receipt tuple, not the bytes of the file. */
    public String sourceKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateField(digest, SOURCE_KEY_DOMAIN);
            updateField(digest, sourceId.getBytes(StandardCharsets.UTF_8));
            updateField(digest, relativePath.getBytes(StandardCharsets.UTF_8));
            updateField(digest, ByteBuffer.allocate(Long.BYTES).putLong(bytes).array());
            updateField(digest, sourceStamp.getBytes(StandardCharsets.UTF_8));
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static String boundedText(String value, String field, int maxBytes, boolean allowEmpty) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if ((!allowEmpty && value.isEmpty()) || encoded.length > maxBytes) {
            throw new IllegalArgumentException(field + " is empty or too long");
        }
        rejectNulOrControl(value, field);
        return value;
    }

    private static String validatePath(String path) {
        String value = boundedText(path, "relativePath", MAX_PATH_BYTES, false);
        if (value.startsWith("/") || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("relativePath must be relative and use forward slashes");
        }
        if (value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':') {
            throw new IllegalArgumentException("relativePath must not contain a drive prefix");
        }
        String[] components = value.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException("relativePath contains an empty or traversal component");
            }
        }
        return value;
    }

    private static void rejectNulOrControl(String value, String field) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\0' || Character.isISOControl(c)) {
                throw new IllegalArgumentException(field + " contains a control character");
            }
        }
    }

    private static void updateField(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String hex(byte[] digest) {
        char[] result = new char[digest.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }
}

package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Fresh offset-dependent bytes connecting one Android reservation to raw storage. */
final class DirectReservationMarker {
    private static final int BLOCK_BYTES = 16;
    private final SecretKeySpec key;

    DirectReservationMarker() {
        byte[] secret = new byte[BLOCK_BYTES];
        new SecureRandom().nextBytes(secret);
        key = new SecretKeySpec(secret, "AES");
        Arrays.fill(secret, (byte) 0);
    }

    void fill(byte[] buffer, int start, int count, long offset) throws IOException {
        Arrays.fill(buffer, start, start + count, (byte) 0);
        apply(buffer, start, count, offset);
    }

    /** Consumes the proof in place. Callers must preserve any bytes beyond count. */
    void requireMatch(byte[] buffer, int start, int count, long offset) throws IOException {
        apply(buffer, start, count, offset);
        int aggregate = 0;
        for (int i = start, end = start + count; i < end; i++) aggregate |= buffer[i];
        if (aggregate != 0) throw new IOException("保存先に確保した領域を照合できませんでした。");
    }

    private void apply(byte[] buffer, int start, int count, long offset) throws IOException {
        if (offset < 0 || offset % BLOCK_BYTES != 0 || start < 0 || count <= 0
                || count > buffer.length - start) throw new IllegalArgumentException("Invalid marker range");
        try {
            // CTR encryption and decryption are the same transformation. Every call starts
            // at the counter for its logical file offset, including an unaligned final length.
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            byte[] iv = ByteBuffer.allocate(BLOCK_BYTES).putLong(8, offset / BLOCK_BYTES).array();
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            if (cipher.doFinal(buffer, start, count, buffer, start) != count)
                throw new IOException("保存先の照合データを作成できませんでした。");
        } catch (GeneralSecurityException failure) {
            throw new IOException("保存先の照合データを作成できませんでした。", failure);
        }
    }
}

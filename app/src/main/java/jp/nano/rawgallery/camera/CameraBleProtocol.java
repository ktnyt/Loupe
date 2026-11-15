package jp.nano.rawgallery.camera;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Facts recovered from OM Image Share 1.5.1; camera/firmware compatibility is experimental. */
public final class CameraBleProtocol {
    public static final UUID SERVICE = UUID.fromString("ADC505F9-4E58-4B71-B8CA-983BB8C73E4F");
    public static final UUID COMMAND = UUID.fromString("82F949B4-F5DC-4CF3-AB3C-FD9FD4017B68");
    public static final UUID RESPONSE = UUID.fromString("B7A8015C-CB94-4EFA-BDA2-B7921FA9951F");
    public static final UUID EVENT = UUID.fromString("05A02050-0860-4919-8ADD-9801FBA8B6ED");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    public static final int ATT_BYTES = 20;
    public enum Operation {
        AUTHENTICATE(0x0c, 0x02), TRANSFER_WAKE(0x0f, 0x01);
        final int family, command;
        Operation(int family, int command) { this.family = family; this.command = command; }
    }
    public static final class Response {
        public final int sequence, status;
        private Response(int sequence, int status) { this.sequence = sequence; this.status = status; }
        public boolean accepted(Operation operation) {
            return status == 0 || (operation == Operation.TRANSFER_WAKE && status == 1);
        }
        public byte[] acknowledgement() { return new byte[]{2, (byte) sequence, 0, 0, 0}; }
    }
    private CameraBleProtocol() { }

    /** Caller retains ownership of chars. Temporary UTF-8 storage is erased; returned frame is sensitive. */
    public static byte[] authenticate(int sequence, char[] passcode) {
        if (passcode == null || passcode.length == 0 || passcode.length > ATT_BYTES - 8)
            throw new IllegalArgumentException("Invalid passcode length");
        ByteBuffer encoded = null;
        byte[] payload = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(passcode));
            if (encoded.remaining() > ATT_BYTES - 8) throw new IllegalArgumentException("Passcode exceeds packet limit");
            payload = new byte[encoded.remaining()]; encoded.get(payload);
            return command(Operation.AUTHENTICATE, sequence, payload);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid passcode encoding");
        } finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
            if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }
    public static byte[] transferWake(int sequence) {
        return command(Operation.TRANSFER_WAKE, sequence, new byte[]{2});
    }
    private static byte[] command(Operation operation, int sequence, byte[] payload) {
        if (sequence < 0 || sequence > 255) throw new IllegalArgumentException("Invalid sequence");
        byte[] frame = new byte[payload.length + 8];
        frame[0] = 1; frame[1] = (byte) sequence; frame[2] = (byte) (payload.length + 3);
        frame[3] = (byte) operation.family; frame[4] = 1; frame[5] = (byte) operation.command;
        System.arraycopy(payload, 0, frame, 6, payload.length);
        int checksum = operation.family + 1 + operation.command;
        for (byte value : payload) checksum += value & 255;
        frame[frame.length - 2] = (byte) checksum;
        return frame;
    }
    /** Validates our outgoing format only. Response length/checksum semantics are not established. */
    public static boolean validCommand(byte[] frame, Operation operation) {
        if (frame == null || operation == null || frame.length < 8 || frame.length > ATT_BYTES
                || frame[0] != 1 || (frame[2] & 255) != frame.length - 5
                || (frame[3] & 255) != operation.family || frame[4] != 1
                || (frame[5] & 255) != operation.command || frame[frame.length - 1] != 0) return false;
        int sum = 0;
        for (int i = 3; i < frame.length - 2; i++) sum += frame[i] & 255;
        return (frame[frame.length - 2] & 255) == (sum & 255);
    }
    /** Bounds are defensive; byte 2/trailer and request/response sequence equality remain unverified. */
    public static Response response(byte[] frame, Operation expected) {
        if (expected == null || frame == null || frame.length < 7 || frame.length > ATT_BYTES
                || frame[0] != 4 || (frame[3] & 255) != expected.family
                || (frame[5] & 255) != expected.command)
            throw new IllegalArgumentException("Unexpected camera response");
        return new Response(frame[1] & 255, frame[6] & 255);
    }
    public static boolean receipt(byte[] frame) {
        return frame != null && frame.length >= 1 && frame.length <= ATT_BYTES && frame[0] == 5;
    }
    /** Data excludes the little-endian manufacturer ID, as Android ScanRecord does. */
    public static boolean manufacturer(int company, byte[] data) {
        return (company == 0x04d0 || company == 0x09f1) && data != null && data.length == 6
                && data[0] == 1 && data[1] == 0;
    }
    public static int advertisementFlags(byte[] manufacturerData) {
        if (manufacturerData == null || manufacturerData.length != 6)
            throw new IllegalArgumentException("Invalid manufacturer data");
        return manufacturerData[3] & 255;
    }
    public static boolean requiresWakeCommand(int flags) { return (flags & 0x28) == 0; }
}

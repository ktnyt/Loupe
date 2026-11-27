import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import jp.nano.rawgallery.camera.CameraBleProtocol;
import jp.nano.rawgallery.camera.CameraBleProtocol.Operation;

public final class CameraBleProtocolTest {
  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void rejects(Runnable action) {
    try {
      action.run();
      throw new AssertionError("expected rejection");
    } catch (IllegalArgumentException expected) {
    }
  }

  private static byte[] response(int family, int command, int status) {
    // Only proven fields are populated. Byte 2 and trailer semantics are not asserted.
    return new byte[] {4, 9, 0, (byte) family, 0, (byte) command, (byte) status};
  }

  public static void main(String[] args) {
    byte[] wake = CameraBleProtocol.transferWake(2);
    require(
        Arrays.equals(wake, new byte[] {1, 2, 4, 15, 1, 1, 2, 19, 0}), "documented wake vector");
    require(CameraBleProtocol.validCommand(wake, Operation.TRANSFER_WAKE), "wake frame validity");
    byte[] wifi = CameraBleProtocol.wifiStart(2);
    require(Arrays.equals(wifi, new byte[] {1, 2, 4, 29, 1, 1, 2, 33, 0}), "Wi-Fi start vector");
    require(CameraBleProtocol.validCommand(wifi, Operation.WIFI_START), "Wi-Fi start checksum");
    require(
        CameraBleProtocol.response(response(29, 1, 0), Operation.WIFI_START)
            .accepted(Operation.WIFI_START),
        "Wi-Fi status zero");
    require(
        !CameraBleProtocol.response(response(29, 1, 1), Operation.WIFI_START)
            .accepted(Operation.WIFI_START),
        "Wi-Fi status one rejected");
    byte[] corrupt = wake.clone();
    corrupt[7] ^= 1;
    require(
        !CameraBleProtocol.validCommand(corrupt, Operation.TRANSFER_WAKE), "checksum corruption");
    corrupt = wake.clone();
    corrupt[2]++;
    require(!CameraBleProtocol.validCommand(corrupt, Operation.TRANSFER_WAKE), "length mismatch");
    require(!CameraBleProtocol.validCommand(wake, Operation.AUTHENTICATE), "wrong command family");
    require(
        !CameraBleProtocol.validCommand(new byte[21], Operation.TRANSFER_WAKE), "oversized frame");
    require(!CameraBleProtocol.validCommand(null, Operation.TRANSFER_WAKE), "null frame");
    rejects(() -> CameraBleProtocol.transferWake(256));
    rejects(() -> CameraBleProtocol.transferWake(-1));

    char[] synthetic = {'1', '2', '3', '4', '5', '6'};
    byte[] auth = CameraBleProtocol.authenticate(1, synthetic);
    require(
        Arrays.equals(auth, new byte[] {1, 1, 9, 12, 1, 2, 49, 50, 51, 52, 53, 54, 68, 0}),
        "synthetic auth vector");
    require(
        CameraBleProtocol.validCommand(auth, Operation.AUTHENTICATE),
        "auth checksum wraps to low byte");
    require(
        Arrays.equals(synthetic, new char[] {'1', '2', '3', '4', '5', '6'}),
        "caller retains char array ownership");
    byte[] utf8 = CameraBleProtocol.authenticate(255, new char[] {'あ', 'い', 'う', 'え'});
    require(utf8.length == 20 && (utf8[1] & 255) == 255, "UTF-8 byte limit and unsigned sequence");
    require(
        Arrays.equals(Arrays.copyOfRange(utf8, 6, 18), "あいうえ".getBytes(StandardCharsets.UTF_8)),
        "UTF-8 encoding");
    rejects(() -> CameraBleProtocol.authenticate(1, new char[] {'あ', 'い', 'う', 'え', 'お'}));
    rejects(() -> CameraBleProtocol.authenticate(1, new char[] {'\ud800'}));
    rejects(() -> CameraBleProtocol.authenticate(1, new char[0]));
    rejects(() -> CameraBleProtocol.authenticate(1, new char[13]));
    rejects(() -> CameraBleProtocol.authenticate(1, null));
    Arrays.fill(auth, (byte) 0);
    Arrays.fill(utf8, (byte) 0);
    Arrays.fill(synthetic, '\0');

    var accepted = CameraBleProtocol.response(response(12, 2, 0), Operation.AUTHENTICATE);
    for (int flags : new int[] {0, 1})
      require(
          CameraBleProtocol.afterAuthentication(flags, accepted) == Operation.TRANSFER_WAKE,
          "normal wake regression");
    for (int flags : new int[] {8, 9, 40})
      require(
          CameraBleProtocol.afterAuthentication(flags, accepted) == Operation.WIFI_START,
          "Bluetooth flag selects Wi-Fi start");
    require(
        CameraBleProtocol.afterAuthentication(32, accepted) == null,
        "location-only has no verified command");
    var denied = CameraBleProtocol.response(response(12, 2, 1), Operation.AUTHENTICATE);
    for (int flags : new int[] {0, 1, 8, 32, 40})
      require(
          CameraBleProtocol.afterAuthentication(flags, denied) == null,
          "auth rejection sends no command");
    require(accepted.accepted(Operation.AUTHENTICATE), "auth status zero");
    require(
        Arrays.equals(accepted.acknowledgement(), new byte[] {2, 9, 0, 0, 0}),
        "ACK uses response sequence");
    require(
        !CameraBleProtocol.response(response(12, 2, 1), Operation.AUTHENTICATE)
            .accepted(Operation.AUTHENTICATE),
        "auth rejection distinct");
    for (int status : new int[] {0, 1})
      require(
          CameraBleProtocol.response(response(15, 1, status), Operation.TRANSFER_WAKE)
              .accepted(Operation.TRANSFER_WAKE),
          "wake accepted status");
    for (int status : new int[] {2, 255})
      require(
          !CameraBleProtocol.response(response(15, 1, status), Operation.TRANSFER_WAKE)
              .accepted(Operation.TRANSFER_WAKE),
          "wake rejected status");
    rejects(() -> CameraBleProtocol.response(new byte[6], Operation.AUTHENTICATE));
    rejects(() -> CameraBleProtocol.response(new byte[21], Operation.AUTHENTICATE));
    rejects(() -> CameraBleProtocol.response(response(15, 1, 0), Operation.AUTHENTICATE));
    rejects(() -> CameraBleProtocol.response(response(12, 1, 0), Operation.AUTHENTICATE));
    byte[] wrongType = response(12, 2, 0);
    wrongType[0] = 5;
    rejects(() -> CameraBleProtocol.response(wrongType, Operation.AUTHENTICATE));
    require(
        CameraBleProtocol.receipt(new byte[] {5}) && !CameraBleProtocol.receipt(new byte[] {4}),
        "receipt type");
    require(
        !CameraBleProtocol.receipt(new byte[0]) && !CameraBleProtocol.receipt(null),
        "receipt bounds");

    byte[] manufacturer = {1, 0, 99, 0x29, 0, 0};
    require(
        CameraBleProtocol.manufacturer(0x04d0, manufacturer)
            && CameraBleProtocol.manufacturer(0x09f1, manufacturer),
        "both verified company IDs");
    require(!CameraBleProtocol.manufacturer(0x1234, manufacturer), "other manufacturer rejected");
    require(!CameraBleProtocol.manufacturer(0x04d0, new byte[] {1, 0}), "short manufacturer data");
    require(
        !CameraBleProtocol.manufacturer(0x04d0, new byte[] {2, 0, 0, 0, 0, 0}),
        "unknown advertisement version");
    require(
        CameraBleProtocol.advertisementFlags(manufacturer) == 0x29,
        "Android manufacturer data excludes company ID");
    require(
        !CameraBleProtocol.requiresWakeCommand(8) && !CameraBleProtocol.requiresWakeCommand(32),
        "Bluetooth/location mode skip power command");
    require(
        CameraBleProtocol.requiresWakeCommand(1) && CameraBleProtocol.requiresWakeCommand(0),
        "transfer-wake command mode");
    System.out.println(
        "PASS BLE outgoing vectors/length/checksum, UTF-8 bounds, observed response fields/status, ACK, receipt and manufacturer flags; no hardware operations");
  }
}

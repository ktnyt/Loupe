package jp.nano.rawgallery.camera;

import java.util.regex.Pattern;

/** Finds the physical USB port and the sole enumerated reader from the system USB dump. */
final class SdRemountUsbPort {
    static final int VENDOR = 0x21c4;
    static final int PRODUCT = 0xb060;
    private static final Pattern ADDRESS = Pattern.compile("/dev/bus/usb/([0-9]{3})/[0-9]{3}");
    private static final Pattern DEVICE_NAME = field(6, "name");
    private static final Pattern VENDOR_ID = field(6, "vendor_id");
    private static final Pattern PRODUCT_ID = field(6, "product_id");
    private static final Pattern CONNECTS = field(4, "num_connects");
    private static final Pattern SIMULATION = field(4, "is_simulation_active");
    private static final Pattern PORT_ID = field(8, "id");
    private static final Pattern CONNECTED = field(8, "connected");
    private static final Pattern MODE = field(8, "current_mode");
    private static final Pattern ROLE = field(8, "data_role");
    private static final Pattern DATA = field(8, "usb_data_status");
    private static final Pattern CONNECTED_AT = field(6, "connected_at_millis");
    private static final Pattern ROOT_DEVICE = Pattern.compile("/usb([0-9]+)/\\1-[0-9]+$");

    record State(String address, long enumerations, long connectedAt, boolean connectedHost, String data) {
        boolean ready() { return address != null && connectedHost && "enabled".equals(data); }
        boolean removed() { return address == null && "disabled-force".equals(data); }
        boolean onBus(String sysPath) {
            if (address == null) return false;
            var usbAddress = ADDRESS.matcher(address);
            var device = ROOT_DEVICE.matcher(sysPath);
            return usbAddress.matches() && device.find()
                    && Integer.toString(Integer.parseInt(usbAddress.group(1))).equals(device.group(1));
        }
    }

    private SdRemountUsbPort() {}

    static boolean validAddress(String value) {
        return value != null && ADDRESS.matcher(value).matches();
    }

    static State parse(String dump) {
        String host = block(dump, "host_manager");
        String ports = block(dump, "port_manager");
        if (host == null || ports == null || !"false".equals(unique(ports, SIMULATION))
                || !"port0".equals(unique(ports, PORT_ID))) return null;
        var connections = CONNECTS.matcher(host);
        if (!connections.find()) return null;
        String devices = host.substring(0, connections.start());
        var names = DEVICE_NAME.matcher(devices);
        String address = null;
        if (names.find()) {
            address = names.group(1);
            if (!validAddress(address) || names.find()
                    || !Integer.toString(VENDOR).equals(unique(devices, VENDOR_ID))
                    || !Integer.toString(PRODUCT).equals(unique(devices, PRODUCT_ID))) return null;
        } else if (!devices.isBlank()) {
            return null;
        }
        try {
            long count = Long.parseLong(unique(host, CONNECTS));
            long connectedAt = Long.parseLong(unique(ports, CONNECTED_AT));
            String data = unique(ports, DATA);
            String connected = unique(ports, CONNECTED);
            String mode = unique(ports, MODE);
            String role = unique(ports, ROLE);
            if (count < 0 || connectedAt < 0 || data == null || role == null || mode == null
                    || !("true".equals(connected) || "false".equals(connected))) return null;
            boolean connectedHost = "true".equals(connected) && "dfp".equals(mode) && "host".equals(role);
            return new State(address, count, connectedAt, connectedHost, data);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Pattern field(int indentation, String key) {
        return Pattern.compile("(?m)^" + " ".repeat(indentation) + key + "=([^\\r\\n]+)$");
    }

    private static String unique(String text, Pattern field) {
        var rows = field.matcher(text);
        if (!rows.find()) return null;
        String value = rows.group(1);
        return rows.find() ? null : value;
    }

    private static String block(String dump, String name) {
        var rows = Pattern.compile("(?ms)^  " + name + "=\\{\\R(.*?)^  \\}[ \\t]*$").matcher(dump);
        if (!rows.find()) return null;
        String value = rows.group(1);
        return rows.find() ? null : value;
    }
}

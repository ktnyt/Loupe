package jp.nano.rawgallery.camera;

import android.content.Context;
import android.net.*;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.*;

/** Select the camera's direct LAN even when Android keeps a second Internet Wi-Fi alive. */
public final class CameraWifiNetwork {
  private CameraWifiNetwork() {}

  /** Eligibility only: callers must acquire their own credential-bound request before using it. */
  public static Network selectRegistered(Context context, String savedSsid) {
    try {
      ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
      WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
      if (manager == null || wifi == null) return null;
      WifiInfo info = wifi.getConnectionInfo();
      if (info == null
          || !matchesRegisteredSsid(savedSsid, info.getSSID())
          || info.getIpAddress() == 0) return null;
      Network match = null;
      for (Network network : manager.getAllNetworks()) {
        if (!registeredAddress(
            manager.getNetworkCapabilities(network),
            manager.getLinkProperties(network),
            info.getIpAddress())) continue;
        if (match != null) return null;
        match = network;
      }
      return match;
    } catch (SecurityException ignored) {
      return null;
    }
  }

  static boolean matchesRegisteredSsid(String saved, String current) {
    if (saved == null
        || current == null
        || saved.isEmpty()
        || current.equals(WifiManager.UNKNOWN_SSID)) return false;
    if (current.length() >= 2 && current.startsWith("\"") && current.endsWith("\""))
      current = current.substring(1, current.length() - 1);
    return !current.isEmpty() && !current.equals(WifiManager.UNKNOWN_SSID) && saved.equals(current);
  }

  static boolean registeredAddress(NetworkCapabilities caps, LinkProperties links, int address) {
    if (address == 0
        || caps == null
        || links == null
        || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false;
    for (LinkAddress link : links.getLinkAddresses()) {
      if (matchesAddress(link.getAddress(), address)) return true;
    }
    return false;
  }

  static boolean matchesAddress(InetAddress value, int address) {
    if (address == 0 || !(value instanceof Inet4Address)) return false;
    byte[] bytes = value.getAddress();
    int ip =
        (bytes[0] & 255)
            | ((bytes[1] & 255) << 8)
            | ((bytes[2] & 255) << 16)
            | ((bytes[3] & 255) << 24);
    return ip == address;
  }

  public static Network select(ConnectivityManager manager) throws IOException {
    List<Network> wifi = new ArrayList<>(), direct = new ArrayList<>();
    for (Network network : manager.getAllNetworks()) {
      NetworkCapabilities caps = manager.getNetworkCapabilities(network);
      if (caps == null
          || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
          || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
      wifi.add(network);
      LinkProperties links = manager.getLinkProperties(network);
      if (links == null) continue;
      for (LinkAddress link : links.getLinkAddresses()) {
        byte[] ip = link.getAddress().getAddress();
        if (link.getAddress() instanceof Inet4Address
            && link.getPrefixLength() >= 24
            && (ip[0] & 255) == 192
            && (ip[1] & 255) == 168
            && ip[2] == 0) {
          direct.add(network);
          break;
        }
      }
    }
    if (direct.size() > 1) throw new IOException("Ambiguous camera Wi-Fi networks");
    if (direct.size() == 1) return direct.get(0);
    return wifi.size() == 1 ? wifi.get(0) : null;
  }
}

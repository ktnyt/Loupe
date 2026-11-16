package jp.nano.rawgallery.camera;

import android.net.*;
import java.io.IOException;
import java.net.Inet4Address;
import java.util.*;

/** Select the camera's direct LAN even when Android keeps a second Internet Wi-Fi alive. */
public final class CameraWifiNetwork {
    private CameraWifiNetwork() { }
    public static Network select(ConnectivityManager manager) throws IOException {
        List<Network> wifi=new ArrayList<>(), direct=new ArrayList<>();
        for(Network network:manager.getAllNetworks()) {
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            wifi.add(network);
            LinkProperties links=manager.getLinkProperties(network);
            if(links==null)continue;
            for(LinkAddress link:links.getLinkAddresses()) {
                byte[] ip=link.getAddress().getAddress();
                if(link.getAddress() instanceof Inet4Address && link.getPrefixLength()>=24 && (ip[0]&255)==192 && (ip[1]&255)==168 && ip[2]==0){direct.add(network);break;}
            }
        }
        if(direct.size()>1)throw new IOException("Ambiguous camera Wi-Fi networks");
        if(direct.size()==1)return direct.get(0);
        return wifi.size()==1?wifi.get(0):null;
    }
}

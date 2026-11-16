package jp.nano.rawgallery.camera;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.Locale;

/** Stable source metadata keys, separate from original content hashes. */
public final class CameraSourceKey {
    private CameraSourceKey() { }
    public static String wifi(String ssid,String bssid) {
        if(ssid==null||bssid==null)return null;
        if(ssid.startsWith("\"")&&ssid.endsWith("\"")&&ssid.length()>=2)ssid=ssid.substring(1,ssid.length()-1);
        if(ssid.isEmpty()||ssid.length()>128||ssid.equalsIgnoreCase("<unknown ssid>"))return null;
        bssid=bssid.toLowerCase(Locale.ROOT);
        if(!bssid.matches("[0-9a-f]{2}(?::[0-9a-f]{2}){5}")||bssid.equals("02:00:00:00:00:00")||bssid.equals("00:00:00:00:00:00")||(Integer.parseInt(bssid.substring(0,2),16)&1)!=0)return null;
        return "wifi-ap:"+hash("camera-wifi-v1",ssid,bssid);
    }
    public static String sd(String uuid,long totalBytes) {
        if(uuid==null||!uuid.matches("(?i)[0-9a-f]{4}-?[0-9a-f]{4}")||totalBytes<=0)return null;
        String serial=uuid.replace("-","").toUpperCase(Locale.ROOT);
        if(serial.equals("00000000"))return null;
        return "sd-saf:"+hash("camera-sd-v1",serial,Long.toString(totalBytes));
    }
    private static String hash(String... fields) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String field:fields){byte[] b=field.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(b.length).array());digest.update(b);}
            StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}

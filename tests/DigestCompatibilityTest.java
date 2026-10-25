package jp.nano.rawgallery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
public final class DigestCompatibilityTest {
  public static void main(String[] args) throws Exception {
    Random random=new Random(17);
    for(int i=0;i<100;i++) {
      String text="写真/GR/"+random.nextLong()+":"+i;
      StringBuilder expected=new StringBuilder();
      for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) expected.append(String.format(Locale.ROOT,"%02x",b));
      if(!PhotoRepository.digest(text).equals(expected.toString())) throw new AssertionError("Changed persistent key");
    }
    System.out.println("Persistent photo keys: 100 compatibility cases passed");
  }
}

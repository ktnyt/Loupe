package jp.nano.rawgallery;

public final class VideoFormatsTest {
  public static void main(String[] args) {
    String[] names = {"clip.MP4", "clip.m4v", "clip.MOV", "clip.3gp", "clip.3gpp", "clip.mkv", "clip.webm"};
    for (String name : names) {
      if (!VideoFormats.accepts(name)) throw new AssertionError("Undiscovered video: " + name);
      if (RawFormats.accepts(name)) throw new AssertionError("Video reached RAW allowlist: " + name);
      if (!VideoFormats.mime(name).startsWith("video/")) throw new AssertionError("Video MIME: " + name);
    }
    for (String name : new String[] {"photo.jpg", "photo.ORF", "clip.mp4.exe", "mp4", "", null})
      if (VideoFormats.accepts(name)) throw new AssertionError("Incorrect video: " + name);
    if (VideoFormats.patterns().length != names.length) throw new AssertionError("Discovery patterns");
    System.out.println("VideoFormatsTest PASS");
  }
}

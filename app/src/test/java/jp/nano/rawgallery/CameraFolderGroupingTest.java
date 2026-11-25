package jp.nano.rawgallery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CameraFolderGroupingTest {
  @Test
  void keepsTheExistingCaseInsensitiveMatches() {
    assertEquals("GR", camera("DCIM/GR World/"));
    assertEquals("OM-1", camera("Pictures/old om-1 backups/"));
    assertEquals("E-P7", camera("Pictures/E-P7/2026/"));
    assertEquals("Leitz", camera("DCIM/CAMERA/Raw/"));
    assertEquals("その他", camera("DCIM/Camera/Edited/"));
  }

  @Test
  void keepsTheExistingOrderForOverlappingFolders() {
    assertEquals("GR", camera("Camera/Raw/E-P7/OM-1/GR World/"));
    assertEquals("OM-1", camera("Camera/Raw/E-P7/OM-1/"));
    assertEquals("E-P7", camera("Camera/Raw/E-P7/"));
  }

  private static String camera(String folder) {
    return new PhotoRepository.Photo(null, "fixture.jpg", folder, 1, 1, 1).cameraGroup();
  }
}

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import jp.nano.rawgallery.AutomaticTagsTest;
import jp.nano.rawgallery.BestShotSelectionTest;
import jp.nano.rawgallery.BurstFrameTest;
import jp.nano.rawgallery.BurstGapSettingsTest;
import jp.nano.rawgallery.BurstGroupsTest;
import jp.nano.rawgallery.CameraExFatCheck;
import jp.nano.rawgallery.CloudPolicyTest;
import jp.nano.rawgallery.ComparisonLayoutTest;
import jp.nano.rawgallery.DiagnosticWriterTest;
import jp.nano.rawgallery.DigestCompatibilityTest;
import jp.nano.rawgallery.GalleryCaptureDatesTest;
import jp.nano.rawgallery.IndexRefreshRequestsTest;
import jp.nano.rawgallery.NanoBananaE2Test;
import jp.nano.rawgallery.PhotoRankingTest;
import jp.nano.rawgallery.RankingStoreTest;
import jp.nano.rawgallery.RawEditToneTest;
import jp.nano.rawgallery.RawPreviewStructureTest;
import jp.nano.rawgallery.RelatedPhotosTest;
import jp.nano.rawgallery.ShareCapacityTest;
import jp.nano.rawgallery.ShareHistoryValidationTest;
import jp.nano.rawgallery.SharePreparationTest;
import jp.nano.rawgallery.SyncPolicyTest;
import jp.nano.rawgallery.TagConfidenceTest;
import jp.nano.rawgallery.TagJudgmentsTest;
import jp.nano.rawgallery.VideoFormatsTest;
import jp.nano.rawgallery.camera.CameraBleRecoveryTest;
import jp.nano.rawgallery.camera.CameraCardRoutingTest;
import jp.nano.rawgallery.camera.CameraConnectionWaitTest;
import jp.nano.rawgallery.camera.CameraImportCopyTest;
import jp.nano.rawgallery.camera.CameraImportOperationGateTest;
import jp.nano.rawgallery.camera.CameraRegisteredQueueTest;
import jp.nano.rawgallery.camera.PhotoImportCopyCheckKt;
import org.junit.jupiter.api.Test;

/** Lets Gradle and IDEs run the same checks as the standalone host commands. */
public class HostRegressionTest {
  private static final String[] NO_ARGS = {};

  @Test
  void automaticTags() throws Exception {
    AutomaticTagsTest.main(NO_ARGS);
  }

  @Test
  void bestShotSelection() throws Exception {
    BestShotSelectionTest.main(NO_ARGS);
  }

  @Test
  void burstFrame() throws Exception {
    BurstFrameTest.main(NO_ARGS);
  }

  @Test
  void burstGapSettings() throws Exception {
    BurstGapSettingsTest.main(NO_ARGS);
  }

  @Test
  void burstGroups() throws Exception {
    BurstGroupsTest.main(NO_ARGS);
  }

  @Test
  void cameraBleProtocol() throws Exception {
    CameraBleProtocolTest.main(NO_ARGS);
  }

  @Test
  void cameraBleRecovery() throws Exception {
    CameraBleRecoveryTest.main(NO_ARGS);
  }

  @Test
  void cameraCardRouting() throws Exception {
    CameraCardRoutingTest.main(NO_ARGS);
  }

  @Test
  void cameraConnectionWait() throws Exception {
    CameraConnectionWaitTest.main(NO_ARGS);
  }

  @Test
  void cameraExFatCheck() throws Exception {
    CameraExFatCheck.main(NO_ARGS);
  }

  @Test
  void cameraImportCopy() throws Exception {
    CameraImportCopyTest.main(NO_ARGS);
  }

  @Test
  void cameraImportOperationGate() throws Exception {
    CameraImportOperationGateTest.main(NO_ARGS);
  }

  @Test
  void cameraQrCredentials() throws Exception {
    CameraQrCredentialsTest.main(NO_ARGS);
  }

  @Test
  void cameraRegisteredQueue() throws Exception {
    CameraRegisteredQueueTest.main(NO_ARGS);
  }

  @Test
  void cameraSourceKey() throws Exception {
    CameraSourceKeyTest.main(NO_ARGS);
  }

  @Test
  void cloudPolicy() throws Exception {
    CloudPolicyTest.main(NO_ARGS);
  }

  @Test
  void comparisonLayout() throws Exception {
    ComparisonLayoutTest.main(NO_ARGS);
  }

  @Test
  void diagnosticWriter() throws Exception {
    DiagnosticWriterTest.main(NO_ARGS);
  }

  @Test
  void digestCompatibility() throws Exception {
    DigestCompatibilityTest.main(NO_ARGS);
  }

  @Test
  void ep7WifiClient() throws Exception {
    Ep7WifiClientTest.main(NO_ARGS);
  }

  @Test
  void galleryCaptureDates() throws Exception {
    GalleryCaptureDatesTest.main(NO_ARGS);
  }

  @Test
  void indexRefreshRequests() throws Exception {
    IndexRefreshRequestsTest.main(NO_ARGS);
  }

  @Test
  void nanoBananaE2() throws Exception {
    NanoBananaE2Test.main(NO_ARGS);
  }

  @Test
  void photoRanking() throws Exception {
    PhotoRankingTest.main(NO_ARGS);
  }

  @Test
  void rankingStore() throws Exception {
    RankingStoreTest.main(NO_ARGS);
  }

  @Test
  void rawEditState() throws Exception {
    RawEditStateTest.main(NO_ARGS);
  }

  @Test
  void rawEditTone() throws Exception {
    RawEditToneTest.main(NO_ARGS);
  }

  @Test
  void rawPreviewStructure() throws Exception {
    RawPreviewStructureTest.main(NO_ARGS);
  }

  @Test
  void relatedPhotos() throws Exception {
    RelatedPhotosTest.main(NO_ARGS);
  }

  @Test
  void shareCapacity() throws Exception {
    ShareCapacityTest.main(NO_ARGS);
  }

  @Test
  void shareHistoryValidation() throws Exception {
    ShareHistoryValidationTest.main(NO_ARGS);
  }

  @Test
  void sharePreparation() throws Exception {
    SharePreparationTest.main(NO_ARGS);
  }

  @Test
  void syncPolicy() throws Exception {
    SyncPolicyTest.main(NO_ARGS);
  }

  @Test
  void tagConfidence() throws Exception {
    TagConfidenceTest.main(NO_ARGS);
  }

  @Test
  void tagJudgments() throws Exception {
    TagJudgmentsTest.main(NO_ARGS);
  }

  @Test
  void videoFormats() throws Exception {
    VideoFormatsTest.main(NO_ARGS);
  }

  @Test
  void photoImportCopy() {
    PhotoImportCopyCheckKt.main();
  }

  @Test
  void rawEditFullSource() throws Exception {
    // The existing launcher supplies graphics stubs to its own JVM only.
    Path script = Path.of(System.getProperty("loupe.repoRoot", "."), "tests/raw-full-source.sh");
    if (Files.notExists(script)) script = Path.of("../tests/raw-full-source.sh");
    ProcessBuilder command = new ProcessBuilder("sh", script.toString()).inheritIO();
    String javaBin = Path.of(System.getProperty("java.home"), "bin").toString();
    command.environment().put("PATH", javaBin + File.pathSeparator
        + command.environment().getOrDefault("PATH", ""));
    Process check = command.start();
    try {
      assertTrue(check.waitFor(120, TimeUnit.SECONDS), "RAW full-source check timed out");
      assertEquals(0, check.exitValue(), "RAW full-source check failed");
    } finally {
      if (check.isAlive()) check.destroyForcibly();
    }
  }
}

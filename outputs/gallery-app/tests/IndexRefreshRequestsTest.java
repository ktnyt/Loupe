package jp.nano.rawgallery;

public final class IndexRefreshRequestsTest {
  private static void check(boolean value) {
    if (!value) throw new AssertionError();
  }

  public static void main(String[] args) {
    IndexRefreshRequests requests = new IndexRefreshRequests();
    check(requests.start(false));
    // A camera save or import completes while the previous snapshot is being indexed.
    for (int i = 0; i < 100; i++) check(!requests.start(false));
    check(requests.finish() == IndexRefreshRequests.NORMAL);
    check(requests.start(false));
    check(requests.finish() == IndexRefreshRequests.NONE);
    // Manual rebuild must survive ordinary notifications arriving afterwards.
    check(requests.start(false));
    check(!requests.start(true));
    check(!requests.start(false));
    check(requests.finish() == IndexRefreshRequests.FORCE);
    // Completion after failure follows the same path; pending work is not lost.
    check(requests.start(true));
    check(!requests.start(false));
    check(requests.finish() == IndexRefreshRequests.NORMAL);
    check(requests.start(false));
    check(requests.finish() == IndexRefreshRequests.NONE);
    System.out.println("Index refresh request regression tests passed");
  }
}

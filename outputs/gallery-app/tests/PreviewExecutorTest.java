package jp.nano.rawgallery;

import java.util.*;
import java.util.concurrent.*;

public final class PreviewExecutorTest {
  public static void main(String[] args) throws Exception {
    PreviewExecutor executor = new PreviewExecutor();
    CountDownLatch running = new CountDownLatch(2), release = new CountDownLatch(1);
    List<Integer> order = Collections.synchronizedList(new ArrayList<>());
    try {
      for (int i = 0; i < 2; i++) executor.execute(() -> {
        running.countDown();
        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      });
      if (!running.await(5, TimeUnit.SECONDS)) throw new AssertionError("workers did not start");
      // Reduce to one worker after the blockers to make ordering observable.
      executor.setCorePoolSize(1);
      executor.setMaximumPoolSize(1);
      for (int i = 0; i < 100; i++) {
        final int index = i;
        executor.execute(() -> order.add(index));
      }
      executor.execute(() -> order.add(-1), true);
      release.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
      if (order.size() != 101 || order.get(0) != -1) throw new AssertionError(order);
      for (int i = 0; i < 100; i++) if (order.get(i + 1) != i) throw new AssertionError(order);
      System.out.println("PASS: visible photo precedes 100 queued thumbnails; FIFO within priority");
    } finally { release.countDown(); executor.shutdownNow(); }
  }
}

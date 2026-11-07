package jp.nano.rawgallery;

import java.util.List;
import java.util.Map;

public final class ShareCapacityTest {
  static PhotoRepository.Photo photo(String name, long size) {
    return new PhotoRepository.Photo(null, name, "fixture/", size, 1, 0);
  }
  static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
  public static void main(String[] args) {
    long limit=ShareCapacity.LIMIT;
    PhotoRepository.Photo exact=photo("exact.jpg",limit), over=photo("over.jpg",limit+1);
    check(ShareCapacity.assess(List.of(exact),false,Map.of(),false).actionable(),"exact limit allowed");
    check(ShareCapacity.assess(List.of(over),false,Map.of(),false).state==ShareCapacity.State.OVER,"one byte over blocked");
    check(ShareCapacity.format(limit+1).equals("512.1"),"overage visible");
    PhotoRepository.Photo raw=photo("large.orf",300L*1024*1024), raw2=photo("other.orf",300L*1024*1024), jpeg=photo("small.jpg",100);
    ShareCapacity.Result deferred=ShareCapacity.assess(List.of(raw),false,Map.of(),false);
    check(deferred.state==ShareCapacity.State.UNKNOWN,"raw original size is not JPEG export size");
    check(ShareCapacity.available(ShareCapacity.assess(List.of(raw,raw2),false,Map.of(),false),ShareCapacity.assess(List.of(raw,raw2),true,Map.of(),false)).actionable(),"unknown JPEG batch not forbidden by original total");
    PhotoRepository.Photo unsupported=photo("unsupported.orf",OriginalCache.LIMIT+1);
    check(!OriginalCache.supportsSize(unsupported.size),"same materialization size guard");
    check(ShareCapacity.assess(List.of(unsupported),false,Map.of(ShareCapacity.key(unsupported),100L),false).state==ShareCapacity.State.SOURCE_LIMIT,"small JPEG cannot bypass per-original limit");
    check(ShareCapacity.assess(List.of(raw),false,Map.of(),true).state==ShareCapacity.State.CHECKING,"local probe pending");
    check(ShareCapacity.assess(List.of(raw),false,Map.of(ShareCapacity.key(raw),100L),false).state==ShareCapacity.State.FIT,"measured JPEG fits");
    check(ShareCapacity.assess(List.of(raw),false,Map.of(ShareCapacity.key(raw),-1L),false).state==ShareCapacity.State.FAILED,"failed probe not fit");
    check(ShareCapacity.assess(List.of(over,raw),false,Map.of(),false).state==ShareCapacity.State.OVER,"known over wins over unknown");
    check(ShareCapacity.assess(List.of(photo("a.jpg",Long.MAX_VALUE),photo("b.jpg",10)),true,Map.of(),false).state==ShareCapacity.State.OVER,"sum saturates");
    check(ShareCapacity.assess(List.of(photo("unknown.jpg",0)),false,Map.of(),false).state==ShareCapacity.State.SOURCE_SIZE,"unknown source cannot be materialized");
    check(ShareCapacity.assess(List.of(jpeg),false,Map.of(),false).state==ShareCapacity.State.FIT,"reduced selection fits");
    check(ShareCapacity.assess(List.of(raw),false,Map.of(ShareCapacity.key(photo("large.orf",raw.size+1)),100L),false).state==ShareCapacity.State.UNKNOWN,"changed source invalidates size");
    System.out.println("Share capacity: exact/over, per-format unknown/pending/failure, reduction, overflow and identity passed");
  }
}

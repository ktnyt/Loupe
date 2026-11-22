package jp.nano.rawgallery.camera;

import java.util.*;

/** Read-only SD history classification. No receipt, hash, or publication is created by this plan. */
public final class CameraImportHistoryPlan {
    public static final class Entry {
        public final String key, uri;
        public final boolean deleted;
        public Entry(String name,long bytes,long shot,String uri,boolean deleted) {
            this.key=key(name,bytes,shot);this.uri=uri;this.deleted=deleted;
        }
    }
    public static final class Destination {
        public final long bytes;
        public final boolean published;
        public Destination(long bytes,boolean published){this.bytes=bytes;this.published=published;}
    }
    private final Map<String,List<Entry>> history=new HashMap<>();
    private final Map<String,Destination> destinations;
    public CameraImportHistoryPlan(List<Entry> rows,Map<String,Destination> destinations) {
        for(Entry row:rows)history.computeIfAbsent(row.key,k->new ArrayList<>()).add(row);
        this.destinations=destinations;
    }
    public static String key(String name,long bytes,long shot){return name+"|"+bytes+"|"+shot;}
    public static boolean validUri(String uri){return uri!=null&&uri.matches("content://media/(external|external_primary|[0-9a-f]+(?:-[0-9a-f]+)*)/images/media/[0-9]+");}
    /** A matching common receipt may account for a repaired JPEG's different stored byte count. */
    public boolean known(String name,long bytes,long shot,boolean verifyAll,String receiptUri,long storedBytes) {
        if(verifyAll||destinations==null)return false;
        List<Entry> rows=history.get(key(name,bytes,shot));if(rows==null)return false;
        Set<String> uris=new HashSet<>();
        for(Entry row:rows){
            if(row.deleted)return false;
            if(validUri(row.uri))uris.add(row.uri);
            else if(!"existing".equals(row.uri))return false;
        }
        if(uris.size()!=1)return false;
        String uri=uris.iterator().next();Destination present=destinations.get(uri);
        long expected=uri.equals(receiptUri)&&storedBytes>0?storedBytes:bytes;
        return expected>0&&present!=null&&present.published&&present.bytes==expected;
    }
}

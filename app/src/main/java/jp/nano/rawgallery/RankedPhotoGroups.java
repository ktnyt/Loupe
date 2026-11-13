package jp.nano.rawgallery;

import java.io.IOException;
import java.util.*;

/** One rank predicate for library rendering and the unapplied filter's result count. */
final class RankedPhotoGroups {
  interface Load { PhotoRanking get(List<Integer> members) throws IOException; }
  static List<List<Integer>> filter(List<PhotoRepository.Photo> photos,List<List<Integer>> groups,
      boolean expanded,int filter,RankingStore store,Load load) {
    List<List<Integer>> result=new ArrayList<>();
    for(List<Integer> members:groups) {
      if(filter==0){add(result,members,expanded);continue;}
      if(members.size()==1) {
        PhotoRepository.Photo photo=photos.get(members.get(0));
        if(photo.isVideo())continue;
        try {
          boolean chosen=store.singleBest(ArchiveStore.key(photo));
          if(chosen&&(filter==1||filter==3)||!chosen&&filter==2)result.add(members);
        } catch(Exception unreadable){if(filter==2)result.add(members);}
      } else try {
        PhotoRanking saved=load.get(members);
        if(filter==1&&saved.done()) {
          Set<String> best=new HashSet<>(saved.best());
          for(int member:members)if(best.contains(ArchiveStore.key(photos.get(member))))result.add(List.of(member));
        }else if(filter==2&&!saved.done()||filter==3&&saved.done())add(result,members,expanded);
      }catch(Exception unreadable){if(filter==2)result.add(members);}
    }
    return result;
  }
  private static void add(List<List<Integer>> result,List<Integer> members,boolean expanded){
    if(expanded)for(int member:members)result.add(List.of(member));else result.add(members);
  }
}

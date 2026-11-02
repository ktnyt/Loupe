package jp.nano.rawgallery;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class LookPresetStoreTest {
  interface Op { void run() throws Exception; }
  static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  static void reject(Op op,String why)throws Exception{try{op.run();throw new AssertionError(why);}catch(IOException|IllegalArgumentException expected){}}
  static Path preset(Path d,String id){return d.resolve("preset-"+id+".look");}
  static void rawEntry(Path p,String id,String name)throws Exception{try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(p))){out.writeInt(0x4c505331);out.writeInt(1);out.writeUTF("preset");out.writeUTF(id);out.writeUTF(name);RawEditLook.neutral().write(out);}}
  static void noTemps(Path d)throws Exception{try(var s=Files.list(d)){check(s.noneMatch(p->p.getFileName().toString().endsWith(".tmp")),"temp cleanup");}}
  public static void main(String[] args)throws Exception {
    Path root=Files.createTempDirectory("look-presets-"); Path dir=root.resolve("private"); LookPresetStore store=new LookPresetStore(dir.toFile());
    try {
      RawEditLook original=RawEditLook.cntLike().withVignetteStrength(.37f).withLutStrength(.67f).withGrainEnabled(true).withGrainStrength(.83f).withGrainShadows(.45f).withSeed(-314159).withCurve(4,new RawEditCurve(new float[]{0,0,.2f,.22f,.45f,.49f,.7f,.76f,1,1})).withCube(RawEditCube.identity(65));
      LookPresetStore.Entry saved=store.save("  Cine 夜景  ",original);
      check(UUID.fromString(saved.id).toString().equals(saved.id)&&saved.name.equals("Cine 夜景"),"UUID and trimmed name");
      check(store.list().size()==1&&store.list().get(0).look.equals(original),"full 65^3 recipe round trip");
      LookPresetStore.Entry builtIn=new LookPresetStore.Entry("cnt-like","CNT-like（仮）",RawEditLook.cntLike());
      store.saveSelection("archive-key-abc",builtIn);
      LookPresetStore.Entry selection=store.loadSelection("archive-key-abc");
      check(selection.id.equals("cnt-like")&&selection.name.equals(builtIn.name)&&selection.look.equals(builtIn.look),"built-in snapshot round trip");
      try(var files=Files.list(dir)){check(files.noneMatch(p->p.getFileName().toString().contains("archive-key-abc")),"raw photo key not used as path");}
      store.saveSelection("photo-1",saved);store.delete(saved.id);
      check(store.list().isEmpty()&&store.loadSelection("photo-1").look.equals(original),"selection independent of library deletion");
      check(store.loadSelection("missing")==null,"absent selection");store.clearSelection("photo-1");check(store.loadSelection("photo-1")==null,"selection clear");store.clearSelection("photo-1");
      for(String bad:new String[]{null,"","   ","x".repeat(41),"bad\nname","bad\u0000name","\ud800"})reject(()->store.save(bad,original),"invalid name accepted: "+bad);
      reject(()->store.delete("../escape"),"path traversal id accepted");
      for(int i=0;i<32;i++)store.save("p"+i,RawEditLook.neutral());
      reject(()->store.save("over limit",RawEditLook.neutral()),"33rd preset accepted");
      check(store.list().size()==32,"preset count");
      for(LookPresetStore.Entry e:store.list())store.delete(e.id);
      LookPresetStore.Entry e=store.save("Preserved",RawEditLook.neutral());Path file=preset(dir,e.id);byte[] committed=Files.readAllBytes(file);
      // Reader reports damaged entries instead of silently omitting them.
      Files.write(file,Arrays.copyOf(committed,committed.length-1));reject(store::list,"truncated entry silently discarded");Files.write(file,committed);
      Files.write(file,new byte[]{0},StandardOpenOption.APPEND);reject(store::list,"trailing bytes accepted");Files.write(file,committed);
      try(RandomAccessFile raf=new RandomAccessFile(file.toFile(),"rw")){raf.seek(4);raf.writeInt(99);}reject(store::list,"unknown version accepted");Files.write(file,committed);
      try(RandomAccessFile raf=new RandomAccessFile(file.toFile(),"rw")){raf.setLength(4L*1024*1024+1);}reject(store::list,"oversized file accepted");Files.write(file,committed);
      Path badPath=dir.resolve("preset-00000000-0000-0000-0000-000000000000.look");Files.write(badPath,committed);reject(store::list,"filename/id mismatch accepted");Files.delete(badPath);
      String malformedId="00000000-0000-0000-0000-000000000002";Path malformed=preset(dir,malformedId);rawEntry(malformed,"../escape","Valid");reject(store::list,"malformed serialized id accepted");Files.delete(malformed);
      rawEntry(malformed,malformedId,"bad\nname");reject(store::list,"malformed serialized name accepted");Files.delete(malformed);
      Path link=dir.resolve("preset-00000000-0000-0000-0000-000000000001.look");Files.createSymbolicLink(link,file);reject(store::list,"symlink preset accepted");Files.delete(link);
      byte[] old=Files.readAllBytes(file);reject(()->store.save("",RawEditLook.cntLike()),"invalid replacement accepted");check(Arrays.equals(old,Files.readAllBytes(file)),"invalid save modified committed file");noTemps(dir);
      Path quotaDir=root.resolve("quota");LookPresetStore quotaStore=new LookPresetStore(quotaDir.toFile());LookPresetStore.Entry kept=quotaStore.save("kept",RawEditLook.neutral());quotaStore.saveSelection("key",kept);Path filler=quotaDir.resolve("photo-quota-fixture");try(RandomAccessFile raf=new RandomAccessFile(filler.toFile(),"rw")){raf.setLength(128L*1024*1024);}
      reject(()->quotaStore.saveSelection("key",new LookPresetStore.Entry("cnt-like","CNT",RawEditLook.neutral())),"aggregate quota accepted");check(quotaStore.loadSelection("key").id.equals(kept.id),"quota failure replaced prior selection");
      noTemps(quotaDir);
      check(store.loadSelection("never")==null,"selection absent after damaged preset tests");
      System.out.println("PASS LookPresetStore: recipe snapshot, 65^3 LUT, Unicode names, built-in and photo snapshots, delete independence, count/name/id/size/corruption/EOF/symlink checks, prior bytes and temp cleanup");
    } finally { try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);} }
  }
}

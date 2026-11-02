package jp.nano.rawgallery;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class RawEditLookStoreTest {
  private static void require(boolean ok,String text){if(!ok)throw new AssertionError(text);}
  private interface Operation {void run()throws Exception;}
  private static void rejects(Operation operation,String text)throws Exception {try{operation.run();throw new AssertionError(text);}catch(IOException expected){}}
  private static Path recipe(File directory,String token){return directory.toPath().resolve(token+".look");}
  private static void noTemporaryFiles(File directory)throws Exception {try(var files=Files.list(directory.toPath())){require(files.noneMatch(p->p.toString().endsWith(".tmp")),"temporary file cleanup");}}
  public static void main(String[] args)throws Exception {
    Path root=Files.createTempDirectory("loupe-look-store-");File directory=root.resolve("private-session").toFile();
    try {
      RawEditLook current=RawEditLook.cntLike().withVignetteEnabled(true).withVignetteStrength(.37f).withLutStrength(.67f).withGrainStrength(.83f).withGrainShadows(.45f).withSeed(-314159).withCurve(4,new RawEditCurve(new float[]{0,.02f,.4f,.45f,1,.98f}));
      RawEditLook saved=RawEditLook.neutral();
      RawEditLook custom=current.withCube(RawEditCube.identity(65)).withToneEnabled(false).withLutEnabled(false).withGrainEnabled(false).withSeed(Integer.MIN_VALUE);
      RawEditCube external=RawEditCube.identity(65);
      String token=RawEditLookStore.save(directory,null,current,saved,custom,external);
      require(UUID.fromString(token).toString().equals(token),"new UUID token");require(Files.size(recipe(directory,token))<RawEditLookStore.MAX_FILE_BYTES,"derived file size bound");
      RawEditLook[] restored=RawEditLookStore.load(directory,token);require(restored.length==3&&current.equals(restored[0])&&saved.equals(restored[1])&&custom.equals(restored[2]),"all three complete recipes restored");
      RawEditLookStore.Session session=RawEditLookStore.loadSession(directory,token);require(current.equals(session.current)&&saved.equals(session.saved)&&custom.equals(session.custom)&&external.equals(session.external),"external 65-cube restored independently of recipes");
      require(RawEditLookStore.save(directory,token,custom,current,saved,external).equals(token),"replacement keeps session token");
      session=RawEditLookStore.loadSession(directory,token);require(external.equals(session.external)&&session.custom.cube()==null,"retained import survives Custom selecting a different LUT and grain values");
      RawEditCube replacementExternal=RawEditCube.cntLike();RawEditLookStore.save(directory,token,custom,current,saved,replacementExternal);session=RawEditLookStore.loadSession(directory,token);require(replacementExternal.equals(session.external)&&custom.equals(session.current)&&saved.equals(session.custom),"external import replacement leaves recipe slots intact");

      restored=RawEditLookStore.load(directory,token);require(custom.equals(restored[0])&&current.equals(restored[1])&&saved.equals(restored[2]),"replacement committed as a complete snapshot");noTemporaryFiles(directory);
      byte[] committed=Files.readAllBytes(recipe(directory,token));
      rejects(()->RawEditLookStore.save(directory,token,null,current,saved),"missing look accepted");require(Arrays.equals(committed,Files.readAllBytes(recipe(directory,token))),"failed save preserves prior valid bytes");require(custom.equals(RawEditLookStore.load(directory,token)[0]),"failed save preserves readable prior recipe");
      if(Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix")){
        Set<java.nio.file.attribute.PosixFilePermission> permissions=Files.getPosixFilePermissions(directory.toPath());
        try{Files.setPosixFilePermissions(directory.toPath(),java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
          if(!Files.isWritable(directory.toPath()))rejects(()->RawEditLookStore.save(directory,token,current,saved,custom),"write-protected directory accepted");
        }finally{Files.setPosixFilePermissions(directory.toPath(),permissions);}
        require(Arrays.equals(committed,Files.readAllBytes(recipe(directory,token)))&&custom.equals(RawEditLookStore.load(directory,token)[0]),"I/O save failure preserves valid session");noTemporaryFiles(directory);
      }
      String linkToken=UUID.randomUUID().toString();Path outside=root.resolve("outside-private-look");Files.write(outside,new byte[]{42});Files.createSymbolicLink(recipe(directory,linkToken),outside);
      rejects(()->RawEditLookStore.load(directory,linkToken),"symlink load accepted");rejects(()->RawEditLookStore.save(directory,linkToken,current,saved,custom),"symlink target accepted");RawEditLookStore.delete(directory,linkToken);require(Files.exists(outside)&&Files.readAllBytes(outside)[0]==42,"token link cannot read/write/delete outside file");
      File parentFile=root.resolve("not-directory").toFile();Files.write(parentFile.toPath(),new byte[]{42});rejects(()->RawEditLookStore.save(parentFile,token,current,saved,custom),"non-directory accepted");require(Files.readAllBytes(parentFile.toPath())[0]==42,"failure preserves conflicting file");
      for(String invalid:new String[]{"../escape","../../outside",token+"/x","",token.replace("-",""),"1-1-1-1-1","00000000-0000-0000-0000-00000000000g"}){
        rejects(()->RawEditLookStore.save(directory,invalid,current,saved,custom),"invalid save token");rejects(()->RawEditLookStore.load(directory,invalid),"invalid load token");rejects(()->RawEditLookStore.delete(directory,invalid),"invalid delete token");
      }
      // Truncation at header, look header, curve and table payload boundaries cannot restore partially.
      for(int length:new int[]{0,4,7,8,15,61,committed.length-1}){Files.write(recipe(directory,token),Arrays.copyOf(committed,length));rejects(()->RawEditLookStore.load(directory,token),"truncated session accepted at "+length);}
      Files.write(recipe(directory,token),committed);try(RandomAccessFile file=new RandomAccessFile(recipe(directory,token).toFile(),"rw")){file.seek(4);file.writeInt(99);}rejects(()->RawEditLookStore.load(directory,token),"unknown version accepted");
      Files.write(recipe(directory,token),committed);Files.write(recipe(directory,token),new byte[]{0},StandardOpenOption.APPEND);rejects(()->RawEditLookStore.load(directory,token),"surplus data accepted");
      try(RandomAccessFile file=new RandomAccessFile(recipe(directory,token).toFile(),"rw")){file.setLength(RawEditLookStore.MAX_FILE_BYTES+1);}rejects(()->RawEditLookStore.load(directory,token),"oversize session accepted");
      // A malformed retained-table header and truncated payload cannot return even valid recipe slots.
      Files.write(recipe(directory,token),committed);
      long externalOffset;try(DataInputStream in=new DataInputStream(new FileInputStream(recipe(directory,token).toFile()))){in.readInt();in.readInt();RawEditLook.read(in);RawEditLook.read(in);RawEditLook.read(in);externalOffset=committed.length-in.available();require(in.readBoolean(),"retained external fixture present");}
      try(RandomAccessFile file=new RandomAccessFile(recipe(directory,token).toFile(),"rw")){file.seek(externalOffset+1);file.writeInt(66);}rejects(()->RawEditLookStore.loadSession(directory,token),"invalid external table size accepted");
      Files.write(recipe(directory,token),Arrays.copyOf(committed,(int)externalOffset+1));rejects(()->RawEditLookStore.loadSession(directory,token),"missing retained external body accepted");
      Files.write(recipe(directory,token),committed);try(RandomAccessFile file=new RandomAccessFile(recipe(directory,token).toFile(),"rw")){file.seek(4);file.writeInt(1);}rejects(()->RawEditLookStore.loadSession(directory,token),"version 1 must not migrate implicitly");
      // Maximum supported tables in all four slots fit the bound and restore their full precision.
      RawEditLook large=current.withCube(RawEditCube.identity(65));RawEditLookStore.save(directory,token,large,large.withSeed(1),large.withSeed(2),external);restored=RawEditLookStore.load(directory,token);session=RawEditLookStore.loadSession(directory,token);require(large.equals(restored[0])&&large.withSeed(1).equals(restored[1])&&large.withSeed(2).equals(restored[2])&&external.equals(session.external),"four 65-cube round trip");require(Files.size(recipe(directory,token))<=RawEditLookStore.MAX_FILE_BYTES,"four-cube derived bound");
      RawEditLookStore.save(directory,token,current,saved,custom);require(RawEditLookStore.loadSession(directory,token).external==null,"old save adapter clears optional external table");

      RawEditLookStore.delete(directory,token);require(!Files.exists(recipe(directory,token)),"finish removes session");RawEditLookStore.delete(directory,token);rejects(()->RawEditLookStore.load(directory,token),"deleted session restored");noTemporaryFiles(directory);
      System.out.println("PASS private look store: UUID/path validation, current/saved/custom round trip, independent retained import, four 65^3 tables, atomic replacement, preservation on failed save, bounded/truncated/version/EOF rejection, temporary cleanup, finish deletion");
    } finally {
      try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
  }
}

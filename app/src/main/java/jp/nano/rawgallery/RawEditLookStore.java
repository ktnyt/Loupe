package jp.nano.rawgallery;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.regex.Pattern;

/** Private, synchronous PoC session persistence. Bundle stores only the returned opaque UUID token. */
public final class RawEditLookStore {
  private static final int MAGIC=0x4c4b5031,VERSION=2;
  // Three recipe tables plus an independently retained external 65^3 table and bounded metadata.
  public static final long MAX_FILE_BYTES=4L*(RawEditCube.MAX_SIZE*RawEditCube.MAX_SIZE*RawEditCube.MAX_SIZE*3*4+8192)+16;
  private static final Pattern TOKEN=Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  /** Immutable session values; external is the last imported table independently of Custom. */
  public static final class Session {
    public final RawEditLook current,saved,custom;
    public final RawEditCube external;
    private Session(RawEditLook current,RawEditLook saved,RawEditLook custom,RawEditCube external){this.current=current;this.saved=saved;this.custom=custom;this.external=external;}
  }
  private RawEditLookStore(){}
  private static Path path(File directory,String token)throws IOException {
    if(directory==null||token==null||!TOKEN.matcher(token).matches())throw new IOException("Invalid look session token");
    return directory.toPath().resolve(token+".look");
  }
  /** Same-directory fsync + atomic replacement. Failure preserves any existing committed recipe. */
  public static String save(File directory,String token,RawEditLook current,RawEditLook saved,RawEditLook custom)throws IOException {
    return save(directory,token,current,saved,custom,null);
  }
  /** Retains imported pixels only; no external document URI is stored. */
  public static String save(File directory,String token,RawEditLook current,RawEditLook saved,RawEditLook custom,RawEditCube retainedExternal)throws IOException {
    String selected=token==null?UUID.randomUUID().toString():token;Path target=path(directory,selected);
    if(current==null||saved==null||custom==null)throw new IOException("Missing look session recipe");
    Files.createDirectories(directory.toPath());
    if(!Files.isDirectory(directory.toPath())||Files.isSymbolicLink(directory.toPath()))throw new IOException("Invalid private look directory");
    if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid private look session file");
    Path temporary=Files.createTempFile(directory.toPath(),selected+"-",".tmp");
    try {
      try(FileOutputStream stream=new FileOutputStream(temporary.toFile());DataOutputStream output=new DataOutputStream(new BufferedOutputStream(stream))){
        output.writeInt(MAGIC);output.writeInt(VERSION);current.write(output);saved.write(output);custom.write(output);output.writeBoolean(retainedExternal!=null);if(retainedExternal!=null)retainedExternal.write(output);output.flush();
        if(stream.getChannel().size()>MAX_FILE_BYTES)throw new IOException("Look session exceeds size limit");
        stream.getFD().sync();
      }
      // No non-atomic fallback: an unsupported move leaves the old valid file in place.
      Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
      return selected;
    } finally {Files.deleteIfExists(temporary);}
  }
  /** Returns current, saved and custom, or throws on missing, truncated, extra or invalid data. */
  public static RawEditLook[] load(File directory,String token)throws IOException {
    Session session=loadSession(directory,token);return new RawEditLook[]{session.current,session.saved,session.custom};
  }
  /** Version-2 format only; rejects partial/extra imported-table data before returning a session. */
  public static Session loadSession(File directory,String token)throws IOException {
    Path target=path(directory,token);
    if(Files.isSymbolicLink(directory.toPath())||!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing/invalid private look session");
    long length=Files.size(target);if(length<8||length>MAX_FILE_BYTES)throw new IOException("Invalid look session size");
    try(DataInputStream input=new DataInputStream(new BufferedInputStream(Files.newInputStream(target)))){
      if(input.readInt()!=MAGIC||input.readInt()!=VERSION)throw new IOException("Unsupported look session version");
      RawEditLook current=RawEditLook.read(input),saved=RawEditLook.read(input),custom=RawEditLook.read(input);
      RawEditCube external=input.readBoolean()?RawEditCube.read((DataInput)input):null;
      if(input.read()!=-1)throw new IOException("Extra look session data");return new Session(current,saved,custom,external);
    }
  }
  /** Call only when the owning Activity finishes, never for rotation or a temporary stop. */
  public static void delete(File directory,String token)throws IOException {Files.deleteIfExists(path(directory,token));}
}

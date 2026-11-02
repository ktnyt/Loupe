package jp.nano.rawgallery;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.util.*;

/** Durable, private library of named Looks and independent per-photo snapshots. */
public final class LookPresetStore {
  private static final int MAGIC=0x4c505331, VERSION=1;
  private static final long MAX_FILE=4L*1024*1024, MAX_TOTAL=128L*1024*1024;
  private static final int MAX_PRESETS=32;
  private static final Object LOCK=new Object();
  private final Path directory;

  public static final class Entry {
    public final String id,name;
    public final RawEditLook look;
    public Entry(String id,String name,RawEditLook look) {
      if(!validId(id,true)||look==null)throw new IllegalArgumentException("Invalid Look entry");
      this.id=id; this.name=validateName(name); this.look=look;
    }
  }
  public LookPresetStore(File directory) {
    if(directory==null)throw new IllegalArgumentException("Missing Look directory");
    this.directory=directory.toPath();
  }

  public List<Entry> list() throws IOException { synchronized(LOCK) {
    ensureDirectory(); List<Entry> result=new ArrayList<>();
    for(Path p:files("preset-")) { Entry e=read(p,"preset"); if(!isUuid(e.id)||!p.equals(presetPath(e.id)))throw new IOException("Invalid preset identity"); result.add(e); }
    result.sort(Comparator.comparing(e->e.name,String.CASE_INSENSITIVE_ORDER)); return Collections.unmodifiableList(result);
  }}
  public Entry save(String name,RawEditLook look) throws IOException { synchronized(LOCK) {
    String clean=validateName(name); if(look==null)throw new IOException("Missing Look recipe"); ensureDirectory();
    List<Path> presets=files("preset-"); if(presets.size()>=MAX_PRESETS)throw new IOException("Look preset limit reached");
    String id=UUID.randomUUID().toString(); Entry entry=new Entry(id,clean,look); write(presetPath(id),"preset",entry); return entry;
  }}
  public void delete(String id) throws IOException { synchronized(LOCK) {
    if(!isUuid(id))throw new IOException("Invalid preset id"); ensureDirectory(); Path p=presetPath(id);
    if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)) { read(p,"preset"); Files.delete(p); }
  }}
  public Entry loadSelection(String key) throws IOException { synchronized(LOCK) {
    ensureDirectory(); Path p=selectionPath(key); if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return null;
    return read(p,"photo");
  }}
  public void saveSelection(String key,Entry entry) throws IOException { synchronized(LOCK) {
    if(entry==null)throw new IOException("Missing selection snapshot"); ensureDirectory(); write(selectionPath(key),"photo",entry);
  }}
  public void clearSelection(String key) throws IOException { synchronized(LOCK) {
    ensureDirectory(); Files.deleteIfExists(selectionPath(key));
  }}

  private void ensureDirectory() throws IOException {
    Files.createDirectories(directory);
    if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid Look directory");
  }
  private List<Path> files(String prefix) throws IOException {
    List<Path> out=new ArrayList<>(); try(DirectoryStream<Path> ds=Files.newDirectoryStream(directory,prefix+"*")) {
      for(Path p:ds) { if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid Look file"); out.add(p); }
    } return out;
  }
  private Path presetPath(String id){return directory.resolve("preset-"+id+".look");}
  private Path selectionPath(String key)throws IOException {
    if(key==null)throw new IOException("Missing photo key");
    try { byte[] b=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)); return directory.resolve("photo-"+hex(b)+".look"); }
    catch(NoSuchAlgorithmException e){throw new IOException(e);}
  }
  private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
  private void write(Path target,String type,Entry entry)throws IOException {
    byte[] bytes; try(ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b)) {
      out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(type);out.writeUTF(entry.id);out.writeUTF(entry.name);entry.look.write(out);out.flush();bytes=b.toByteArray();
    }
    if(bytes.length>MAX_FILE)throw new IOException("Look file exceeds size limit");
    long total=0; for(Path p:files("preset-"))total+=Files.size(p); for(Path p:files("photo-"))total+=Files.size(p);
    if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
      if(Files.isSymbolicLink(target)||!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid Look destination");
      total-=Files.size(target);
    }
    if(total+bytes.length>MAX_TOTAL)throw new IOException("Look storage limit reached");
    Path temp=Files.createTempFile(directory,"look-",".tmp");
    try { try(FileOutputStream f=new FileOutputStream(temp.toFile())){f.write(bytes);f.getFD().sync();}
      Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temp); }
  }
  private Entry read(Path p,String expected)throws IOException {
    if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid Look file");
    long size=Files.size(p); if(size<16||size>MAX_FILE)throw new IOException("Invalid Look file size");
    try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
      if(in.readInt()!=MAGIC||in.readInt()!=VERSION||!expected.equals(in.readUTF()))throw new IOException("Invalid Look header");
      String id=in.readUTF(), name=in.readUTF(); if(!validId(id,expected.equals("photo")))throw new IOException("Invalid Look identity");
      String clean=validateName(name); if(!clean.equals(name))throw new IOException("Invalid Look name");
      RawEditLook look=RawEditLook.read(in); if(in.read()!=-1)throw new IOException("Trailing Look data"); return new Entry(id,name,look);
    } catch(EOFException|UTFDataFormatException e){throw new IOException("Truncated Look file",e);} catch(IllegalArgumentException e){throw new IOException("Invalid Look data",e);}
  }
  private static boolean validId(String id,boolean builtin){return id!=null&&(builtin&&id.equals("cnt-like")||isUuid(id));}
  private static boolean isUuid(String id){try{return id!=null&&UUID.fromString(id).toString().equals(id);}catch(IllegalArgumentException e){return false;}}
  private static String validateName(String value) {
    if(value==null)throw new IllegalArgumentException("Missing Look name");
    int start=0,end=value.length(); while(start<end){int cp=value.codePointAt(start);if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))break;start+=Character.charCount(cp);}
    while(end>start){int cp=value.codePointBefore(end);if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))break;end-=Character.charCount(cp);}
    String s=value.substring(start,end);
    int n=s.codePointCount(0,s.length()); if(n<1||n>40)throw new IllegalArgumentException("Invalid Look name length");
    for(int i=0;i<s.length();) {char c=s.charAt(i);int cp=s.codePointAt(i); if(Character.isISOControl(cp)||Character.isSurrogate(c)&&Character.charCount(cp)==1)throw new IllegalArgumentException("Invalid Look name character"); i+=Character.charCount(cp);}
    return s;
  }
}

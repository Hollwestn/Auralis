package io.auralis.core.util;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Windows AF_UNIX pipes fail under some redirected/short-name user TEMP paths. */
public final class NetworkRuntime {
 private NetworkRuntime() {}
 public static synchronized void prepare() {
  if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") || System.getProperty("jdk.net.unixdomain.tmpdir") != null) return;
  Path directory=null;
  try {
   String programData=System.getenv("ProgramData");if(programData==null||!programData.chars().allMatch(c->c<128))return;
   Path parent=Path.of(programData).toRealPath();directory=Files.createTempDirectory(parent,"auralis-net-");
   AclFileAttributeView view=Files.getFileAttributeView(directory,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
   if(view==null)throw new java.io.IOException("Windows ACL unavailable");
   UserPrincipal owner=Files.getOwner(directory,LinkOption.NOFOLLOW_LINKS);
   UserPrincipal system=directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("SYSTEM");
   Set<AclEntryPermission> permissions=EnumSet.allOf(AclEntryPermission.class);
   view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(permissions).build(),AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(system).setPermissions(permissions).build()));
   if(Files.isSymbolicLink(directory)||!directory.toRealPath().getParent().equals(parent))throw new java.io.IOException("Unexpected socket directory");
   System.setProperty("jdk.net.unixdomain.tmpdir",directory.toString());
   final Path cleanup=directory;Runtime.getRuntime().addShutdownHook(new Thread(()->{try{Files.deleteIfExists(cleanup);}catch(Exception ignored){}},"auralis-socket-cleanup"));
  } catch(Exception ignored) { if(directory!=null)try{Files.deleteIfExists(directory);}catch(Exception unused){} }
 }
}

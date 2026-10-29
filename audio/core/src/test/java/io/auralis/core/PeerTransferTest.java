package io.auralis.core;

import io.auralis.core.backup.LibraryArchive;
import io.auralis.core.library.LibraryService;
import io.auralis.core.transfer.PeerTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerTransferTest {
 @TempDir Path root;
 @Test void encryptedTransferRequiresSecretApprovalAndPreservesSelectedPlaylists()throws Exception {
  BlockingQueue<PeerTransfer.PairRequest> paired=new LinkedBlockingQueue<>();BlockingQueue<PeerTransfer.Incoming> incoming=new LinkedBlockingQueue<>();
  try(var library=new LibraryService(root.resolve("library"));var peer=new PeerTransfer(InetAddress.getByName("127.0.0.1"),root.resolve("peer"),Duration.ofMinutes(1),paired::add,incoming::add)){
   var info=LibraryArchive.JSON.readTree(Base64.getUrlDecoder().decode(peer.pairingCode().substring(13)));String pin=info.path("certificate").asText(),secret=info.path("secret").asText(),nonce=UUID.randomUUID().toString();
   try(var client=client(pin)){
    String base="https://127.0.0.1:"+peer.port();
    assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(base+"/bundle")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(403,client.send(request(base,"/bundle",secret,nonce).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    var result=client.send(request(base,"/pair",secret,nonce).POST(HttpRequest.BodyPublishers.ofString("{\"device\":\"Test phone\"}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(202,result.statusCode());var body=LibraryArchive.JSON.readTree(result.body());var pair=paired.poll(5,TimeUnit.SECONDS);assertNotNull(pair);assertEquals(pair.code(),body.path("code").asText());
    assertFalse(LibraryArchive.JSON.readTree(client.send(request(base,"/pair/status/"+pair.id(),secret,nonce).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).path("approved").asBoolean());
    peer.approve(pair.id());Path archive=root.resolve("empty.auralis");LibraryArchive.write(library,archive,true,null);peer.share(archive);
    var received=client.send(request(base,"/bundle",secret,nonce).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,received.statusCode());assertArrayEquals(Files.readAllBytes(archive),received.body());assertEquals(LibraryArchive.hash(archive),received.headers().firstValue("X-Auralis-SHA256").orElseThrow());
    assertEquals(403,client.send(request(base,"/bundle",secret,UUID.randomUUID().toString()).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    var sent=client.send(request(base,"/upload",secret,nonce).POST(HttpRequest.BodyPublishers.ofFile(archive)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,sent.statusCode());assertNotNull(incoming.poll(5,TimeUnit.SECONDS));assertEquals(0,library.tracks().size());
   }
   try(var wrong=client("0".repeat(64))){assertThrows(Exception.class,()->wrong.send(request("https://127.0.0.1:"+peer.port(),"/bundle",secret,nonce).GET().build(),HttpResponse.BodyHandlers.ofString()));}
  }
 }
 private static HttpRequest.Builder request(String base,String path,String secret,String nonce){return HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10)).header("Authorization","Auralis "+secret).header("X-Auralis-Device",nonce);}
 private static HttpClient client(String pin)throws Exception{var tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException();}public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{try{c[0].checkValidity();assertEquals(pin,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded())));}catch(Throwable e){throw new CertificateException("Certificate does not match QR",e);}}}},new SecureRandom());return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();}
}

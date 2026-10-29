package io.auralis.core.transfer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import io.auralis.core.backup.LibraryArchive;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Temporary, explicit LAN session. TLS identity and secret are transferred out of band by QR. */
public final class PeerTransfer implements AutoCloseable {
 static { io.auralis.core.util.NetworkRuntime.prepare(); }
 public record PairRequest(String id,String device,String code){}
 public record Incoming(Path archive,LibraryArchive.Preview preview){}
 private static final long MAX=3_000_000_000L;
 private final HttpsServer server;private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
 private final ScheduledExecutorService expiry=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"auralis-peer-expiry");t.setDaemon(true);return t;});
 private final byte[] secretBytes=new byte[32];private final String secret,fingerprint;private final InetAddress address;private final Instant until;private final Path directory;
 private final Consumer<PairRequest> onPair;private final Consumer<Incoming> onIncoming;private final Map<String,String> requests=new ConcurrentHashMap<>();private final AtomicBoolean transferring=new AtomicBoolean();
 private volatile String approvedNonce;private volatile Path outgoing;private volatile boolean closed;
 public PeerTransfer(InetAddress address,Path directory,Duration lifetime,Consumer<PairRequest> onPair,Consumer<Incoming> onIncoming)throws Exception{
  if(!(address instanceof Inet4Address)||(!address.isSiteLocalAddress()&&!address.isLoopbackAddress()))throw new IllegalArgumentException("Выберите IPv4-адрес локальной сети");
  if(lifetime.isNegative()||lifetime.isZero()||lifetime.compareTo(Duration.ofMinutes(15))>0)throw new IllegalArgumentException("Сеанс должен быть не дольше 15 минут");
  this.address=address;this.directory=directory.toAbsolutePath().normalize();Files.createDirectories(this.directory);this.onPair=Objects.requireNonNull(onPair);this.onIncoming=Objects.requireNonNull(onIncoming);until=Instant.now().plus(lifetime);
  SecureRandom random=new SecureRandom();random.nextBytes(secretBytes);secret=Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
  KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));KeyPair keys=generator.generateKeyPair();X500Name name=new X500Name("CN=Auralis LAN");
  var builder=new JcaX509v3CertificateBuilder(name,new BigInteger(128,random).abs(),Date.from(Instant.now().minusSeconds(60)),Date.from(until.plusSeconds(60)),name,keys.getPublic());
  builder.addExtension(Extension.basicConstraints,true,new BasicConstraints(false));builder.addExtension(Extension.keyUsage,true,new KeyUsage(KeyUsage.digitalSignature));builder.addExtension(Extension.extendedKeyUsage,false,new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));builder.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName(GeneralName.iPAddress,address.getHostAddress())));
  X509Certificate certificate=new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));certificate.verify(keys.getPublic());fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
  char[] password=UUID.randomUUID().toString().toCharArray();KeyStore store=KeyStore.getInstance("PKCS12");store.load(null,password);store.setKeyEntry("session",keys.getPrivate(),password,new java.security.cert.Certificate[]{certificate});KeyManagerFactory factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());factory.init(store,password);Arrays.fill(password,'\0');SSLContext tls=SSLContext.getInstance("TLS");tls.init(factory.getKeyManagers(),null,random);
  server=HttpsServer.create(new InetSocketAddress(address,0),4);server.setHttpsConfigurator(new HttpsConfigurator(tls));server.setExecutor(workers);server.createContext("/",this::handle);server.start();expiry.schedule(this::close,lifetime.toMillis(),TimeUnit.MILLISECONDS);
 }
 public int port(){return server.getAddress().getPort();}
 public Instant expires(){return until;}
 public String pairingCode(){ObjectNode data=LibraryArchive.JSON.createObjectNode().put("v",1).put("host",address.getHostAddress()).put("port",port()).put("certificate",fingerprint).put("secret",secret).put("expires",until.toString());return "auralis-pair:"+Base64.getUrlEncoder().withoutPadding().encodeToString(data.toString().getBytes(StandardCharsets.UTF_8));}
 public void share(Path archive)throws IOException{if(Files.size(archive)>MAX)throw new IOException("Для переноса на телефон выберите не более 3 ГБ музыки");LibraryArchive.preview(archive,null);outgoing=archive.toAbsolutePath().normalize();}
 public void approve(String requestId){String nonce=requests.get(requestId);if(nonce!=null&&!closed)approvedNonce=nonce;}
 public void reject(String requestId){requests.remove(requestId);}
 private void handle(HttpExchange exchange){
  try(exchange){
   if(closed||Instant.now().isAfter(until)){json(exchange,410,"expired");return;}
   String auth=exchange.getRequestHeaders().getFirst("Authorization");if(auth==null||!MessageDigest.isEqual(("Auralis "+secret).getBytes(StandardCharsets.US_ASCII),auth.getBytes(StandardCharsets.US_ASCII))){json(exchange,401,"unauthorized");return;}
   String path=exchange.getRequestURI().getRawPath(),method=exchange.getRequestMethod(),nonce=exchange.getRequestHeaders().getFirst("X-Auralis-Device");
   if(exchange.getRequestURI().getRawQuery()!=null||nonce==null||!nonce.matches("[A-Za-z0-9-]{16,100}")){json(exchange,400,"invalid request");return;}
   if(path.equals("/pair")&&method.equals("POST")){
    if(requests.size()>=8){json(exchange,429,"too many pairing requests");return;}
    byte[] body=exchange.getRequestBody().readNBytes(4097);if(body.length>4096){json(exchange,413,"request too large");return;}
    var data=LibraryArchive.JSON.readTree(body);String label=data.path("device").asText("Телефон").replaceAll("[\\p{Cntrl}]","");if(label.length()>80)label=label.substring(0,80);
    String id=UUID.randomUUID().toString(),code=code(nonce);requests.put(id,nonce);onPair.accept(new PairRequest(id,label,code));reply(exchange,202,LibraryArchive.JSON.createObjectNode().put("requestId",id).put("code",code));return;
   }
   if(path.startsWith("/pair/status/")&&method.equals("GET")){String id=path.substring(13);if(!nonce.equals(requests.get(id))){json(exchange,404,"pairing not found");return;}reply(exchange,200,LibraryArchive.JSON.createObjectNode().put("approved",nonce.equals(approvedNonce)).put("ready",outgoing!=null));return;}
   if(!nonce.equals(approvedNonce)){json(exchange,403,"approve pairing on the computer first");return;}
   if(!transferring.compareAndSet(false,true)){json(exchange,409,"transfer already running");return;}
   try{
    if(path.equals("/bundle")&&method.equals("GET")){
     Path file=outgoing;if(file==null){json(exchange,404,"no selected music");return;}exchange.getResponseHeaders().set("Content-Type","application/zip");exchange.getResponseHeaders().set("X-Auralis-SHA256",LibraryArchive.hash(file));exchange.sendResponseHeaders(200,Files.size(file));try(InputStream in=Files.newInputStream(file)){in.transferTo(exchange.getResponseBody());}return;
    }
    if(path.equals("/upload")&&method.equals("POST")){
     String length=exchange.getRequestHeaders().getFirst("Content-Length");long expected=length==null?-1:Long.parseLong(length);if(expected<0||expected>MAX||Files.getFileStore(directory).getUsableSpace()<expected+32L*1024*1024){json(exchange,413,"file too large or insufficient storage");return;}
     Path partial=Files.createTempFile(directory,"incoming-",".part");try{
      long size=0;try(OutputStream out=Files.newOutputStream(partial);InputStream input=exchange.getRequestBody()){byte[] buffer=new byte[131072];int n;while((n=input.read(buffer))!=-1){size+=n;if(size>expected||size>MAX)throw new IOException("Объём данных превышен");out.write(buffer,0,n);}}if(size!=expected)throw new IOException("Передача прервана");
      var preview=LibraryArchive.preview(partial,null);Path destination=directory.resolve("incoming-"+UUID.randomUUID()+".auralis");Files.move(partial,destination);onIncoming.accept(new Incoming(destination,LibraryArchive.preview(destination,null)));reply(exchange,200,LibraryArchive.JSON.createObjectNode().put("received",true).put("tracks",preview.tracks()));
     }finally{Files.deleteIfExists(partial);}return;
    }
    json(exchange,404,"unknown endpoint");
   }finally{transferring.set(false);}
  }catch(Exception ignored){/* Closed connections never import partial files or disclose local paths. */}
 }
 private String code(String nonce)throws Exception{javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secretBytes,"HmacSHA256"));byte[] bytes=mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8));int n=((bytes[0]&255)<<16)|((bytes[1]&255)<<8)|(bytes[2]&255);return String.format(Locale.ROOT,"%06d",n%1000000);}
 private static void json(HttpExchange e,int status,String error)throws IOException{reply(e,status,LibraryArchive.JSON.createObjectNode().put("error",error));}
 private static void reply(HttpExchange e,int status,ObjectNode body)throws IOException{byte[] bytes=LibraryArchive.JSON.writeValueAsBytes(body);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);}
 public static List<InetAddress> localAddresses()throws SocketException{List<InetAddress> result=new ArrayList<>();for(NetworkInterface net:Collections.list(NetworkInterface.getNetworkInterfaces()))if(net.isUp()&&!net.isLoopback())for(InetAddress address:Collections.list(net.getInetAddresses()))if(address instanceof Inet4Address&&address.isSiteLocalAddress())result.add(address);return List.copyOf(result);}
 @Override public void close(){if(closed)return;closed=true;server.stop(0);expiry.shutdownNow();workers.shutdownNow();requests.clear();approvedNonce=null;Arrays.fill(secretBytes,(byte)0);}
}

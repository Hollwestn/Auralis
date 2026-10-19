package io.auralis.desktop.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerClientTest {
    ServerSocket server;
    ExecutorService connections;
    ServerClient client;
    @TempDir Path temp;
    final AtomicReference<String> auth=new AtomicReference<>(),uploadType=new AtomicReference<>();
    final AtomicReference<byte[]> uploaded=new AtomicReference<>();
    @BeforeEach void start()throws Exception{
        server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));connections=Executors.newCachedThreadPool();
        connections.submit(()->{while(!server.isClosed())try{Socket socket=server.accept();connections.submit(()->{try(socket){respond(socket);}catch(IOException e){throw new UncheckedIOException(e);}});}catch(IOException e){if(!server.isClosed())throw new UncheckedIOException(e);}});
        client=new ServerClient("http://127.0.0.1:"+server.getLocalPort());client.login("you@example.com","password","",false);
    }
    private void respond(Socket socket)throws IOException{
            InputStream input=socket.getInputStream();ByteArrayOutputStream head=new ByteArrayOutputStream();int matched=0;
            while(matched<4){int next=input.read();if(next<0)return;head.write(next);matched=next=="\r\n\r\n".charAt(matched)?matched+1:(next=='\r'?1:0);if(head.size()>65536)throw new IOException("HTTP header limit");}
            String[] lines=head.toString(StandardCharsets.ISO_8859_1).split("\r\n");String path=URI.create(lines[0].split(" ")[1]).getPath();Map<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon>0)headers.put(lines[i].substring(0,colon),lines[i].substring(colon+1).strip());}
            byte[] request=input.readNBytes(Integer.parseInt(headers.getOrDefault("Content-Length","0")));auth.set(headers.get("Authorization"));
            byte[] body;int status=200;String type="application/json";
            if(path.equals("/api/v1/auth/login")){body="{\"token\":\"secret-token\",\"user\":{\"id\":\"account-a\"}}".getBytes(StandardCharsets.UTF_8);}
            else if(path.equals("/api/v1/catalog/upload")){uploaded.set(request);uploadType.set(headers.get("Content-Type"));body="{\"id\":\"track-a\"}".getBytes(StandardCharsets.UTF_8);}
            else if(path.equals("/api/v1/tracks/track-a/stream")){body=new byte[]{1,3,5,7,9};type="audio/wav";}
            else if(path.endsWith("/redirect")){body=new byte[0];status=302;}
            else if(path.endsWith("/missing/stream")){body="{\"error\":\"Нет доступа\"}".getBytes(StandardCharsets.UTF_8);status=404;}
            else if(path.endsWith("/conflict")){body="{\"error\":\"Обновите состояние\"}".getBytes(StandardCharsets.UTF_8);status=409;}
            else body="[]".getBytes(StandardCharsets.UTF_8);
            var output=socket.getOutputStream();output.write(("HTTP/1.1 "+status+" Response\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n"+(status==302?"Location: http://127.0.0.1:1/stolen\r\n":"")+"\r\n").getBytes(StandardCharsets.ISO_8859_1));output.write(body);output.flush();
    }
    @AfterEach void stop()throws Exception{if(server!=null)server.close();if(connections!=null){connections.shutdownNow();connections.awaitTermination(3,TimeUnit.SECONDS);}}
    @Test void authenticatedRequestsAndScopedIdentity()throws Exception{
        assertEquals("account-a",client.userId());assertTrue(client.identity().endsWith(".account-a"));client.request("tracks?q=test","GET",null);assertEquals("Bearer secret-token",auth.get());
    }
    @Test void recognizesErrorContractAndRetainsStatus(){var e=assertThrows(ServerClient.ApiFailure.class,()->client.request("conflict","PUT",Map.of()));assertEquals(409,e.status());assertTrue(e.getMessage().contains("Обновите состояние"));}
    @Test void rejectsCrossOriginAndPathEscape(){assertThrows(IllegalArgumentException.class,()->client.request("https://evil.example/api","GET",null));assertThrows(IllegalArgumentException.class,()->client.request("../../other","GET",null));}
    @Test void neverFollowsRedirects(){var e=assertThrows(ServerClient.ApiFailure.class,()->client.request("redirect","GET",null));assertEquals(302,e.status());}
    @Test void streamsMultipartFileWithOriginalBytes()throws Exception{
        byte[] audio=new byte[65537];new Random(42).nextBytes(audio);Path source=temp.resolve("музыка.wav");Files.write(source,audio);
        assertEquals("track-a",client.upload(source).path("id").asText());assertTrue(uploadType.get().startsWith("multipart/form-data; boundary=Auralis"));
        byte[] body=uploaded.get();int start=indexOf(body,"\r\n\r\n".getBytes(StandardCharsets.UTF_8))+4;assertArrayEquals(audio,Arrays.copyOfRange(body,start,start+audio.length));assertArrayEquals(audio,Files.readAllBytes(source));
    }
    @Test void downloadsAtomicallyAndDoesNotReplaceGoodFileOnFailure()throws Exception{
        Path dest=temp.resolve("cache/song.wav");client.download("track-a",dest);assertArrayEquals(new byte[]{1,3,5,7,9},Files.readAllBytes(dest));
        assertThrows(ServerClient.ApiFailure.class,()->client.download("missing",dest));assertArrayEquals(new byte[]{1,3,5,7,9},Files.readAllBytes(dest));
        try(var files=Files.list(dest.getParent())){assertEquals(List.of(dest),files.toList());}
    }
    private static int indexOf(byte[] haystack,byte[] needle){outer:for(int i=0;i<=haystack.length-needle.length;i++){for(int n=0;n<needle.length;n++)if(haystack[i+n]!=needle[n])continue outer;return i;}return -1;}
}

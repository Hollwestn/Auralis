package io.auralis.desktop.server;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Blocking HTTP runs on the app worker, with streamed files, TLS verification and no redirects. */
public final class ServerClient {
    private final ObjectMapper json=new ObjectMapper();
    private final URI base;
    private String token,userId;
    public ServerClient(String url){
        base=URI.create(url.strip().replaceAll("/+$","")+"/api/v1/");
        if(!List.of("https","http").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null)throw new IllegalArgumentException("Укажите URL сервера http://host:8080 или https://music.example.com");
    }
    public String userId(){return userId;}
    public String identity(){if(userId==null)throw new IllegalStateException("Сначала войдите в аккаунт");return Base64.getUrlEncoder().withoutPadding().encodeToString(base.toString().getBytes(StandardCharsets.UTF_8))+"."+userId;}
    public JsonNode login(String email,String password,String displayName,boolean register)throws IOException,InterruptedException{
        Map<String,String> body=new HashMap<>();body.put("email",email);body.put("password",password);if(register)body.put("displayName",displayName);
        JsonNode result=request(register?"auth/register":"auth/login","POST",body);String value=result.path("token").asText(),id=result.path("user").path("id").asText();
        if(value.isBlank()||id.isBlank())throw new IOException("Некорректный ответ авторизации");token=value;userId=id;return result;
    }
    public JsonNode request(String path,String method,Object body)throws IOException,InterruptedException{
        HttpURLConnection connection=open(path,method,60000);
        try{
            if(body!=null){byte[] bytes=json.writeValueAsBytes(body);connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setFixedLengthStreamingMode(bytes.length);try(var out=connection.getOutputStream()){out.write(bytes);}}
            return read(connection);
        }finally{connection.disconnect();}
    }
    public JsonNode upload(Path file)throws IOException,InterruptedException{
        if(!Files.isRegularFile(file))throw new IOException("Файл не найден: "+file.getFileName());long size=Files.size(file);
        if(size>300L*1024*1024)throw new IOException("Максимальный размер серверного файла — 300 МБ");
        String boundary="Auralis"+UUID.randomUUID().toString().replace("-","");
        String name=file.getFileName().toString().replace('"','_').replace('\r','_').replace('\n','_');
        byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8),suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection=open("catalog/upload","POST",600000);
        try{
            connection.setDoOutput(true);connection.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);connection.setFixedLengthStreamingMode(prefix.length+size+suffix.length);
            try(var out=connection.getOutputStream();var in=Files.newInputStream(file)){out.write(prefix);copy(in,out,300L*1024*1024);out.write(suffix);}
            return read(connection);
        }finally{connection.disconnect();}
    }
    private JsonNode read(HttpURLConnection connection)throws IOException{
        int status=connection.getResponseCode();String text="";InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
        if(input!=null)try(input){byte[] bytes=input.readNBytes(8*1024*1024+1);if(bytes.length>8*1024*1024)throw new IOException("Слишком большой ответ API");text=new String(bytes,StandardCharsets.UTF_8);}
        if(status<200||status>299){String message="HTTP "+status;try{JsonNode e=json.readTree(text);message+=" · "+e.path("error").asText(e.path("message").asText(e.path("detail").asText("Ошибка запроса")));}catch(Exception ignored){}throw new ApiFailure(status,message);}
        return text.isBlank()?json.createObjectNode():json.readTree(text);
    }
    private HttpURLConnection open(String path,String method,int timeout)throws IOException{
        URI target=base.resolve(path);
        if(!Objects.equals(base.getAuthority(),target.getAuthority())||!Objects.equals(base.getScheme(),target.getScheme())||!target.getPath().startsWith(base.getPath()))throw new IllegalArgumentException("Запрос за пределы API сервера запрещён");
        HttpURLConnection connection=(HttpURLConnection)target.toURL().openConnection();connection.setConnectTimeout(12000);connection.setReadTimeout(timeout);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");
        if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);return connection;
    }
    public Path download(String id,Path destination)throws IOException,InterruptedException{
        Files.createDirectories(destination.toAbsolutePath().getParent());Path temp=Files.createTempFile(destination.toAbsolutePath().getParent(),".download-",".part");
        HttpURLConnection connection=open("tracks/"+URLEncoder.encode(id,StandardCharsets.UTF_8)+"/stream","GET",600000);
        try{
            connection.setRequestProperty("Accept","audio/*");int status=connection.getResponseCode();if(status!=200)throw new ApiFailure(status,"Загрузка аудио: HTTP "+status);
            long bytes;try(var in=connection.getInputStream();var out=Files.newOutputStream(temp)){bytes=copy(in,out,300L*1024*1024);}
            long length=connection.getContentLengthLong();if(length>=0&&bytes!=length)throw new IOException("Соединение прервано до полной загрузки аудио");
            Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING);return destination;
        }finally{connection.disconnect();Files.deleteIfExists(temp);}
    }
    private static long copy(InputStream in,OutputStream out,long max)throws IOException,InterruptedException{long bytes=0;byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();bytes+=n;if(bytes>max)throw new IOException("Аудиофайл превышает 300 МБ");out.write(buffer,0,n);}return bytes;}
    public void logout(){try{request("auth/logout","POST",null);}catch(Exception ignored){}token=null;userId=null;}
    public static final class ApiFailure extends IOException {
        private final int status;
        public ApiFailure(int status,String message){super(message);this.status=status;}
        public int status(){return status;}
    }
}

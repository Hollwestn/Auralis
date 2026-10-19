package io.auralis.server;

import static io.auralis.server.ApiModels.*;
import static io.auralis.server.AuthController.user;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1")
class CatalogController {
    private final CatalogService catalog;
    CatalogController(CatalogService catalog){this.catalog=catalog;}
    @GetMapping("/tracks") List<Track> tracks(HttpServletRequest r,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="200") int limit){return catalog.list(user(r).id(),q,offset,limit);}
    @GetMapping("/tracks/{id}") Track track(HttpServletRequest r,@PathVariable String id){return catalog.require(user(r).id(),id);}
    @DeleteMapping("/tracks/{id}") void delete(HttpServletRequest r,@PathVariable String id) throws IOException {catalog.deleteTrack(user(r).id(),id);}
    @PostMapping("/catalog/upload") Track upload(HttpServletRequest r,@RequestParam MultipartFile file,@RequestParam Map<String,String> metadata) throws IOException {
        try(var input=file.getInputStream()){return catalog.upload(user(r).id(),file.getOriginalFilename(),input,metadata);}
    }
    @PostMapping("/catalog/import") Map<String,Object> folder(HttpServletRequest r,@Valid @RequestBody ImportInput input) throws IOException {return catalog.importCatalog(user(r),input.path());}
    @GetMapping("/tracks/{id}/cover") void cover(HttpServletRequest request,HttpServletResponse response,@PathVariable String id)throws IOException{
        var cover=catalog.cover(user(request).id(),id);response.setContentType(cover.mimeType());response.setContentLengthLong(Files.size(cover.path()));response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Cache-Control","private, max-age=86400");if(request.getMethod().equals("HEAD"))return;try(var input=Files.newInputStream(cover.path())){input.transferTo(response.getOutputStream());}
    }
    @GetMapping("/tracks/{id}/stream") void stream(HttpServletRequest request,HttpServletResponse response,@PathVariable String id) throws IOException {
        var media=catalog.media(user(request).id(),id);Path path=Path.of(media.path());long size=Files.size(path);
        String etag="\""+media.hash()+"\"",rangeHeader=request.getHeader("Range"),ifRange=request.getHeader("If-Range");
        if(ifRange!=null&&!ifRange.equals(etag))rangeHeader=null;
        response.setHeader("Accept-Ranges","bytes");response.setHeader("ETag",etag);
        response.setContentType(media.track().mimeType());
        ByteRange range;
        try{range=ByteRange.parse(rangeHeader,size);}catch(IllegalArgumentException e){response.setStatus(416);response.setHeader("Content-Range","bytes */"+size);return;}
        if(range.partial()){response.setStatus(206);response.setHeader("Content-Range","bytes "+range.start()+"-"+range.end()+"/"+size);}
        response.setContentLengthLong(range.length());
        if(request.getMethod().equals("HEAD"))return;
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ)) {
            channel.position(range.start());ByteBuffer buffer=ByteBuffer.allocate(65536);long remaining=range.length();
            var out=response.getOutputStream();
            while(remaining>0){buffer.clear();buffer.limit((int)Math.min(buffer.capacity(),remaining));int read=channel.read(buffer);if(read<0)break;out.write(buffer.array(),0,read);remaining-=read;}
        }
    }
}

package io.auralis.server;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc
class ServerIntegrationTest {
    static final Path STORE;
    static {try{STORE=Files.createTempDirectory("auralis-test-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:auralis;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username",()->"sa");registry.add("spring.datasource.password",()->"");registry.add("auralis.storage",STORE::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String account() throws Exception {
        String email=UUID.randomUUID()+"@example.com";
        return json.readTree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",email,"password","Strong password 2026!","displayName","Тест")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }
    String upload(String token,String title,String artist,String genre,int frequency) throws Exception {
        var file=new MockMultipartFile("file","tone.wav","audio/wav",wav(frequency));
        return json.readTree(mvc.perform(multipart("/api/v1/catalog/upload").file(file).param("title",title).param("artist",artist).param("genre",genre).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }
    @Test void healthAndAuthAccessControl() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/api/v1/tracks")).andExpect(status().isUnauthorized());
        String token=account();mvc.perform(get("/api/v1/account").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/account").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void catalogRangesOwnershipAndDuplicateDetection() throws Exception {
        String token=account(),other=account(),id=upload(token,"Имя","Автор","Ambient",400);
        assertThat(upload(token,"Дубликат","Другой","Rock",400)).isEqualTo(id);
        mvc.perform(get("/api/v1/tracks").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/tracks").header("Authorization","Bearer "+other)).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/tracks/"+id+"/stream").header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        var streamed=mvc.perform(get("/api/v1/tracks/"+id+"/stream").header("Authorization","Bearer "+token).header("Range","bytes=0-43")).andExpect(status().isPartialContent()).andExpect(header().string("Accept-Ranges","bytes")).andReturn().getResponse().getContentAsByteArray();
        assertThat(streamed).isEqualTo(Arrays.copyOf(wav(400),44));
        mvc.perform(get("/api/v1/tracks/"+id+"/stream").header("Authorization","Bearer "+token).header("Range","bytes=99999999-")).andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(delete("/api/v1/account").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/tracks").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void rejectsInvalidAudioAndDisabledImport() throws Exception {
        String token=account();
        mvc.perform(multipart("/api/v1/catalog/upload").file(new MockMultipartFile("file","evil.mp3","audio/mpeg","not audio".getBytes())).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/catalog/import").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"../../secret\"}")).andExpect(status().isForbidden());
    }
    @Test void favoritesPlaylistHistoryAndIsolation() throws Exception {
        String token=account(),other=account(),id=upload(token,"Track","Artist","Jazz",440);
        mvc.perform(put("/api/v1/favorites/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/favorites").header("Authorization","Bearer "+token)).andExpect(jsonPath("$[0].id").value(id));
        String playlist=json.readTree(mvc.perform(post("/api/v1/playlists").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Jazz\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(put("/api/v1/playlists/"+playlist).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name","My Jazz","trackIds",List.of(id,id))))).andExpect(jsonPath("$.trackIds.length()").value(2));
        mvc.perform(delete("/api/v1/playlists/"+playlist).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/history").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("trackId",id,"playedSeconds",1,"completed",true,"skipped",false)))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/history").header("Authorization","Bearer "+token)).andExpect(jsonPath("$[0].trackId").value(id));
        mvc.perform(delete("/api/v1/history").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/history").header("Authorization","Bearer "+token)).andExpect(content().json("[]"));
    }
    @Test void syncUsesOptimisticVersionAndRejectsForeignTracks() throws Exception {
        String token=account(),other=account(),id=upload(other,"Other","Author","Rock",490);
        mvc.perform(put("/api/v1/sync").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"settings\":{\"theme\":\"Jade\"},\"playback\":{}}")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/v1/sync").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"settings\":{},\"playback\":{}}")).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/sync").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version",1,"settings",Map.of(),"playback",Map.of("trackId",id))))).andExpect(status().isNotFound());
    }
    @Test void recommendationsUsePreferencesFeedbackAndNeverOtherUsersTracks() throws Exception {
        String token=account();String ambient=upload(token,"Calm","A","Ambient",510),metal=upload(token,"Heavy","B","Metal",520);
        mvc.perform(put("/api/v1/preferences").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"genres\":[\"Ambient\"],\"artists\":[]}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/wave?mode=RELAX").header("Authorization","Bearer "+token)).andExpect(jsonPath("$[0].track.id").value(ambient)).andExpect(jsonPath("$[0].reasons").isArray());
        mvc.perform(post("/api/v1/feedback").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("trackId",ambient,"action","DISLIKE")))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/wave").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].track.id").value(metal));
        mvc.perform(get("/api/v1/wave").header("Authorization","Bearer "+account())).andExpect(content().json("[]"));
    }
    @Test void historyRetriesKeepOneEventAndOriginalTimestamp()throws Exception{
        String token=account(),id=upload(token,"Retry","Artist","Ambient",610),eventId=UUID.randomUUID().toString();
        String payload=json.writeValueAsString(Map.of("eventId",eventId,"trackId",id,"playedSeconds",.75,"completed",false,"skipped",true,"playedAt","2025-03-01T12:34:56.123456789Z"));
        var first=json.readTree(mvc.perform(post("/api/v1/history").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var retry=json.readTree(mvc.perform(post("/api/v1/history").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(retry.get("id").asText()).isEqualTo(first.get("id").asText());assertThat(retry.get("playedAt").asText()).isEqualTo("2025-03-01T12:34:56.123456Z");
        mvc.perform(get("/api/v1/history").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.length()").value(1));
        String conflicting=json.writeValueAsString(Map.of("eventId",eventId,"trackId",id,"playedSeconds",.5,"completed",false,"skipped",true));
        mvc.perform(post("/api/v1/history").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(conflicting)).andExpect(status().isConflict());
    }
    @Test void playlistVersionProtectsOtherDeviceEditsAndKeepsLegacyRequests()throws Exception{
        String token=account(),id=upload(token,"Playlist","Artist","Jazz",620);
        var playlist=json.readTree(mvc.perform(post("/api/v1/playlists").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name","Original","trackIds",List.of(id))))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String playlistId=playlist.path("id").asText();long version=playlist.path("version").asLong();
        String update=json.writeValueAsString(Map.of("name","Phone edit","trackIds",List.of(id,id),"version",version));
        mvc.perform(put("/api/v1/playlists/"+playlistId).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(version+1));
        String stale=json.writeValueAsString(Map.of("name","Old PC edit","trackIds",List.of(id),"version",version));
        mvc.perform(put("/api/v1/playlists/"+playlistId).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(stale)).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/playlists").header("Authorization","Bearer "+token)).andExpect(jsonPath("$[0].name").value("Phone edit")).andExpect(jsonPath("$[0].trackIds.length()").value(2));
        mvc.perform(put("/api/v1/playlists/"+playlistId).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Legacy\",\"trackIds\":[]}")).andExpect(status().isOk());
    }
    @Test void syncPreservesDuplicateQueueAndValidatesSelectedIndex()throws Exception{
        String token=account(),a=upload(token,"A","Artist","Ambient",630),b=upload(token,"B","Artist","Ambient",640);
        String valid=json.writeValueAsString(Map.of("version",0,"settings",Map.of("theme","JADE"),"playback",Map.of("trackId",a,"queue",List.of(a,b,a),"queueIndex",2,"positionSeconds",.5,"playing",false,"shuffle",true,"repeat","ALL")));
        mvc.perform(put("/api/v1/sync").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isOk()).andExpect(jsonPath("$.playback.queueIndex").value(2)).andExpect(jsonPath("$.playback.queue.length()").value(3));
        String invalid=json.writeValueAsString(Map.of("version",1,"settings",Map.of(),"playback",Map.of("trackId",a,"queue",List.of(a,b,a),"queueIndex",1)));
        mvc.perform(put("/api/v1/sync").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/sync").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.playback.queueIndex").value(2));
    }
    @Test void actualEmbeddedPngAndJpegArePrivateBoundedAndLazilyCached()throws Exception{
        String token=account(),other=account();
        for(String format:List.of("png","jpg")){
            var image=new java.awt.image.BufferedImage(12,10,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(3,4,0xffaabb);var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,format,bytes);byte[] expected=bytes.toByteArray();
            Path original=STORE.resolve(UUID.randomUUID()+".wav");Files.write(original,wav(format.equals("png")?720:730));var audio=org.jaudiotagger.audio.AudioFileIO.read(original.toFile());var artwork=new org.jaudiotagger.tag.images.StandardArtwork();artwork.setBinaryData(expected);artwork.setMimeType(format.equals("png")?"image/png":"image/jpeg");artwork.setPictureType(3);var wavTag=(org.jaudiotagger.tag.wav.WavTag)audio.getTagOrCreateAndSetDefault();wavTag.getID3Tag().setField(artwork);if(format.equals("jpg")){wavTag.getInfoTag().setField(org.jaudiotagger.tag.FieldKey.TITLE,"RIFF INFO title");wavTag.setExistingInfoTag(true);}audio.commit();byte[] source=Files.readAllBytes(original);
            var uploaded=json.readTree(mvc.perform(multipart("/api/v1/catalog/upload").file(new MockMultipartFile("file","art.wav","audio/wav",source)).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());String id=uploaded.path("id").asText(),url="/api/v1/tracks/"+id+"/cover";assertThat(uploaded.path("coverUrl").asText()).isEqualTo(url);
            mvc.perform(get(url)).andExpect(status().isUnauthorized());mvc.perform(get(url).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
            byte[] actual=mvc.perform(get(url).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(content().contentType(format.equals("png")?"image/png":"image/jpeg")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Cache-Control","private, max-age=86400")).andReturn().getResponse().getContentAsByteArray();assertThat(actual).isEqualTo(expected);
            var caches=Files.walk(STORE).filter(p->p.getFileName().toString().equals(id+".cover."+format)).toList();assertThat(caches).hasSize(1);assertThat(caches.getFirst().getParent().getFileName().toString()).matches("[a-f0-9-]{36}");assertThat(Files.size(caches.getFirst())).isLessThanOrEqualTo(CoverCache.MAX_BYTES);
            Files.delete(caches.getFirst());mvc.perform(get("/api/v1/tracks/"+id).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.coverUrl").value(url));assertThat(Files.exists(caches.getFirst())).isTrue();assertThat(Files.readAllBytes(original)).isEqualTo(source);
            mvc.perform(delete("/api/v1/tracks/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());assertThat(Files.exists(caches.getFirst())).isFalse();mvc.perform(get(url).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        }
    }
    @Test void invalidAndOversizedEmbeddedArtworkNeverBecomesServedContent()throws Exception{
        String token=account();
        var huge=new java.awt.image.BufferedImage(CoverCache.MAX_DIMENSION+1,1,java.awt.image.BufferedImage.TYPE_INT_RGB);var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(huge,"png",png);
        for(byte[] art:List.of("<svg><script>bad</script></svg>".getBytes(),png.toByteArray())){
            Path original=STORE.resolve(UUID.randomUUID()+".wav");Files.write(original,wav(740));var audio=org.jaudiotagger.audio.AudioFileIO.read(original.toFile());var artwork=new org.jaudiotagger.tag.images.StandardArtwork();artwork.setBinaryData(art);artwork.setMimeType("image/png");artwork.setPictureType(3);audio.getTagOrCreateAndSetDefault().setField(artwork);audio.commit();
            var uploaded=json.readTree(mvc.perform(multipart("/api/v1/catalog/upload").file(new MockMultipartFile("file","bad-cover.wav","audio/wav",Files.readAllBytes(original))).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(uploaded.path("coverUrl").isNull()).isTrue();mvc.perform(get("/api/v1/tracks/"+uploaded.path("id").asText()+"/cover").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        }
    }
    static byte[] wav(int frequency){
        int frames=8000;ByteBuffer b=ByteBuffer.allocate(44+frames*2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36+frames*2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(frames*2);
        for(int i=0;i<frames;i++)b.putShort((short)(10000*Math.sin(i*2*Math.PI*frequency/8000)));return b.array();
    }
}

package io.auralis.server;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc
class FolderImportAuthorizationTest {
    static final String ADMIN=UUID.randomUUID().toString(),PASSWORD="Import test strong password!";
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("auralis-import-");Files.createDirectories(ROOT.resolve("allowed"));Files.write(ROOT.resolve("allowed/tone.wav"),ServerIntegrationTest.wav(700));Files.write(ROOT.resolve("outside.wav"),ServerIntegrationTest.wav(710));}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",()->"jdbc:h2:mem:import-auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");p.add("spring.datasource.username",()->"sa");p.add("spring.datasource.password",()->"");
        p.add("auralis.storage",()->ROOT.resolve("storage").toString());p.add("auralis.import-root",()->ROOT.resolve("allowed").toString());p.add("auralis.import-admin-user-id",()->ADMIN);
        // This removed setting must never authorize a self-claimed email.
        p.add("auralis.import-admin-email",()->"claimed@example.com");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    String session(String email,boolean register)throws Exception{
        Map<String,String> payload=new HashMap<>(Map.of("email",email,"password",PASSWORD));if(register)payload.put("displayName","Import test");
        return json.readTree(mvc.perform(post(register?"/api/v1/auth/register":"/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asText();
    }
    @Test void immutableAccountIdAuthorizesAndEmailReclaimDoesNot()throws Exception{
        String attacker=session("claimed@example.com",true);
        mvc.perform(post("/api/v1/catalog/import").header("Authorization","Bearer "+attacker).contentType(MediaType.APPLICATION_JSON).content("{\"path\":\".\"}")).andExpect(status().isForbidden());
        db.update("INSERT INTO accounts(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)",ADMIN,"owner@example.com","Owner",Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD),Timestamp.from(Instant.now()));
        String owner=session("owner@example.com",false);
        mvc.perform(post("/api/v1/catalog/import").header("Authorization","Bearer "+owner).contentType(MediaType.APPLICATION_JSON).content("{\"path\":\".\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.tracks.length()").value(1));
        mvc.perform(post("/api/v1/catalog/import").header("Authorization","Bearer "+owner).contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"../outside.wav\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/account").header("Authorization","Bearer "+owner)).andExpect(status().isOk());
        String reclaimed=session("owner@example.com",true);String newId=json.readTree(mvc.perform(get("/api/v1/account").header("Authorization","Bearer "+reclaimed)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();assertThat(newId).isNotEqualTo(ADMIN);
        mvc.perform(post("/api/v1/catalog/import").header("Authorization","Bearer "+reclaimed).contentType(MediaType.APPLICATION_JSON).content("{\"path\":\".\"}")).andExpect(status().isForbidden());
    }
}

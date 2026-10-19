package io.auralis.server;

import static io.auralis.server.ApiModels.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {
    private final JdbcTemplate db;
    private final int tokenDays;
    private final SecureRandom random = new SecureRandom();
    private final Pbkdf2PasswordEncoder passwords = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    // Always perform a password derivation on unsuccessful login, including unknown accounts.
    private final String dummyHash = passwords.encode(UUID.randomUUID().toString());
    AuthService(JdbcTemplate db, @Value("${auralis.token-days:30}") int tokenDays) { this.db=db; this.tokenDays=tokenDays; }

    @Transactional
    Session register(Register request) {
        String email=request.email().trim().toLowerCase(Locale.ROOT), id=UUID.randomUUID().toString();
        try {
            db.update("INSERT INTO accounts(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)",
                id,email,request.displayName().trim(),passwords.encode(request.password()),Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException e) { throw new ApiException(HttpStatus.CONFLICT,"Адрес уже зарегистрирован"); }
        db.update("INSERT INTO preferences(user_id,genres_json,artists_json) VALUES(?,'[]','[]')",id);
        db.update("INSERT INTO sync_state(user_id,version,settings_json,playback_json,updated_at) VALUES(?,0,'{}','{}',?)",id,Timestamp.from(Instant.now()));
        return new Session(issue(id),new User(id,email,request.displayName().trim()));
    }

    Session login(Login request) {
        var users=db.query("SELECT id,email,display_name,password_hash FROM accounts WHERE email=?",
            (rs,n)->new LoginRecord(new User(rs.getString(1),rs.getString(2),rs.getString(3)),rs.getString(4)),request.email().trim().toLowerCase(Locale.ROOT));
        var user=users.isEmpty()?null:users.getFirst();
        boolean matches=passwords.matches(request.password(),user==null?dummyHash:user.hash());
        if(user==null||!matches) throw new ApiException(HttpStatus.UNAUTHORIZED,"Неверный адрес или пароль");
        return new Session(issue(user.user().id()),user.user());
    }

    Optional<User> authenticate(String token) {
        if(token==null || token.length()<32 || token.length()>200) return Optional.empty();
        return db.query("SELECT a.id,a.email,a.display_name FROM accounts a JOIN access_tokens t ON t.user_id=a.id WHERE t.token_hash=? AND t.expires_at>?",
            (rs,n)->new User(rs.getString(1),rs.getString(2),rs.getString(3)),hash(token),Timestamp.from(Instant.now())).stream().findFirst();
    }
    void logout(String token) { db.update("DELETE FROM access_tokens WHERE token_hash=?",hash(token)); }
    private String issue(String user) {
        byte[] value=new byte[32]; random.nextBytes(value);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        db.update("DELETE FROM access_tokens WHERE expires_at<?",Timestamp.from(Instant.now()));
        db.update("INSERT INTO access_tokens(token_hash,user_id,expires_at) VALUES(?,?,?)",hash(token),user,Timestamp.from(Instant.now().plus(Duration.ofDays(tokenDays))));
        return token;
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record LoginRecord(User user,String hash) {}
}

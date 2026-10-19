package io.auralis.server;

import static io.auralis.server.ApiModels.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
class AuthController {
    private final AuthService auth;
    private final CatalogService catalog;
    private final JdbcTemplate db;
    AuthController(AuthService auth,CatalogService catalog,JdbcTemplate db){this.auth=auth;this.catalog=catalog;this.db=db;}
    @GetMapping("/health") Map<String,String> health(){db.queryForObject("SELECT 1",Integer.class); return Map.of("status","ok","service","auralis","apiVersion","1");}
    @PostMapping("/auth/register") Session register(@Valid @RequestBody Register input){return auth.register(input);}
    @PostMapping("/auth/login") Session login(@Valid @RequestBody Login input){return auth.login(input);}
    @PostMapping("/auth/logout") void logout(HttpServletRequest r){auth.logout((String)r.getAttribute("auralis.token"));}
    @GetMapping("/account") User current(HttpServletRequest r){return user(r);}
    @DeleteMapping("/account") void delete(HttpServletRequest r){catalog.deleteAccount(user(r).id());}
    static User user(HttpServletRequest r){return (User)r.getAttribute("auralis.user");}
}

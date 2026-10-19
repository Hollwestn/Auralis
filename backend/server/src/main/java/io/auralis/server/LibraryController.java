package io.auralis.server;

import static io.auralis.server.ApiModels.*;
import static io.auralis.server.AuthController.user;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
class LibraryController {
    private final LibraryService library;
    LibraryController(LibraryService library){this.library=library;}
    @GetMapping("/favorites") List<Track> favorites(HttpServletRequest r){return library.favorites(user(r).id());}
    @PutMapping("/favorites/{id}") void favorite(HttpServletRequest r,@PathVariable String id){library.favorite(user(r).id(),id,true);}
    @DeleteMapping("/favorites/{id}") void unfavorite(HttpServletRequest r,@PathVariable String id){library.favorite(user(r).id(),id,false);}
    @GetMapping("/playlists") List<Playlist> playlists(HttpServletRequest r){return library.playlists(user(r).id());}
    @PostMapping("/playlists") Playlist create(HttpServletRequest r,@Valid @RequestBody PlaylistInput p){return library.createPlaylist(user(r).id(),p);}
    @PutMapping("/playlists/{id}") Playlist replace(HttpServletRequest r,@PathVariable String id,@Valid @RequestBody PlaylistInput p){return library.replacePlaylist(user(r).id(),id,p);}
    @DeleteMapping("/playlists/{id}") void delete(HttpServletRequest r,@PathVariable String id){library.deletePlaylist(user(r).id(),id);}
    @GetMapping("/history") List<History> history(HttpServletRequest r,@RequestParam(defaultValue="200") int limit,@RequestParam(defaultValue="0") int offset){return library.history(user(r).id(),limit,offset);}
    @PostMapping("/history") History history(HttpServletRequest r,@Valid @RequestBody HistoryInput input){return library.history(user(r).id(),input);}
    @DeleteMapping("/history") void clearHistory(HttpServletRequest r){library.clearHistory(user(r).id());}
    @GetMapping("/preferences") Preference preferences(HttpServletRequest r){return library.preferences(user(r).id());}
    @PutMapping("/preferences") Preference preferences(HttpServletRequest r,@Valid @RequestBody Preference input){library.preferences(user(r).id(),input);return input;}
    @PostMapping("/feedback") void feedback(HttpServletRequest r,@Valid @RequestBody FeedbackInput input){library.feedback(user(r).id(),input);}
    @GetMapping("/sync") Sync sync(HttpServletRequest r){return library.sync(user(r).id());}
    @PutMapping("/sync") Sync sync(HttpServletRequest r,@Valid @RequestBody SyncInput input){return library.sync(user(r).id(),input);}
}

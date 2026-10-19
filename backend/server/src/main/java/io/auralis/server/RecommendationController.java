package io.auralis.server;

import static io.auralis.server.ApiModels.*;
import static io.auralis.server.AuthController.user;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
class RecommendationController {
    private final RecommendationService recommendations;
    RecommendationController(RecommendationService recommendations){this.recommendations=recommendations;}
    @GetMapping("/wave") List<Recommendation> wave(HttpServletRequest r,@RequestParam(defaultValue="MY_WAVE") String mode,@RequestParam(defaultValue="30") int limit){return recommendations.recommend(user(r).id(),mode,limit);}
}

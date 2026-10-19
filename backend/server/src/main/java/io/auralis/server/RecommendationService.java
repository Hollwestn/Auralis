package io.auralis.server;

import static io.auralis.server.ApiModels.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Transparent content ranking. No external catalog, paid API or inferred audio analysis. */
@Service
class RecommendationService {
    private final JdbcTemplate db;
    private final CatalogService catalog;
    private final LibraryService library;
    RecommendationService(JdbcTemplate db,CatalogService catalog,LibraryService library){this.db=db;this.catalog=catalog;this.library=library;}
    List<Recommendation> recommend(String user,String requested,int limit){
        if(limit<1||limit>100)throw ApiException.bad("limit: 1–100");
        String mode=requested.toUpperCase(Locale.ROOT).replace(' ','_');
        if(!Set.of("MY_WAVE","DISCOVERY","FAVORITES","FOCUS","ENERGY","NIGHT","RELAX","FAMILIAR").contains(mode))throw ApiException.bad("Неизвестный режим волны");
        List<Track> tracks=catalog.all(user);Map<String,Track> byId=new HashMap<>();tracks.forEach(t->byId.put(t.id(),t));
        Set<String> favorites=new HashSet<>();library.favorites(user).forEach(t->favorites.add(t.id()));
        Set<String> blocked=new HashSet<>(db.queryForList("SELECT artist FROM blocked_artists WHERE user_id=?",String.class,user));
        Map<String,String> reactions=new HashMap<>();db.query("SELECT track_id,action FROM feedback WHERE user_id=?",rs->{reactions.put(rs.getString(1),rs.getString(2));},user);
        Map<String,Double> genres=new HashMap<>(),artists=new HashMap<>();
        Preference preferences=library.preferences(user);preferences.genres().forEach(g->genres.merge(normal(g),3.0,Double::sum));preferences.artists().forEach(a->artists.merge(normal(a),3.0,Double::sum));
        for(Track t:tracks)if(favorites.contains(t.id())||Set.of("LIKE","MORE").contains(reactions.getOrDefault(t.id(),""))){genres.merge(normal(t.genre()),1.5,Double::sum);artists.merge(normal(t.artist()),1.5,Double::sum);}
        List<History> history=library.history(user,1000,0);Map<String,Integer> counts=new HashMap<>();Map<String,Instant> last=new HashMap<>();
        for(History h:history){counts.merge(h.trackId(),1,Integer::sum);last.putIfAbsent(h.trackId(),h.playedAt());Track t=byId.get(h.trackId());if(t!=null){double engagement=t.durationSeconds()>0?Math.min(1,h.playedSeconds()/t.durationSeconds()):0;double evidence=h.skipped()?-.1:engagement*.15;genres.merge(normal(t.genre()),evidence,Double::sum);artists.merge(normal(t.artist()),evidence,Double::sum);}}
        List<Recommendation> scored=new ArrayList<>();
        for(Track t:tracks){
            if(blocked.contains(t.artist())||"DISLIKE".equals(reactions.get(t.id())))continue;
            if(mode.equals("FAVORITES")&&!favorites.contains(t.id()))continue;
            if(mode.equals("FAMILIAR")&&!counts.containsKey(t.id())&&!favorites.contains(t.id()))continue;
            List<String> reasons=new ArrayList<>();double score=1;
            double genreScore=genres.getOrDefault(normal(t.genre()),0.0),artistScore=artists.getOrDefault(normal(t.artist()),0.0);
            if(genreScore>0&&!t.genre().isBlank()){score+=Math.min(5,genreScore);reasons.add("Ваш интерес к жанру «"+t.genre()+"»");}
            if(artistScore>0){score+=Math.min(5,artistScore);reasons.add("Вы слушаете «"+t.artist()+"»");}
            if(favorites.contains(t.id())){score+=3;reasons.add("В избранном");}
            if("MORE".equals(reactions.get(t.id()))||"LIKE".equals(reactions.get(t.id()))){score+=3;reasons.add("Ваша положительная реакция");}
            if("LESS".equals(reactions.get(t.id()))){score-=4;reasons.add("Понижено по вашей просьбе");}
            boolean known=counts.containsKey(t.id())||favorites.contains(t.id());
            if(mode.equals("DISCOVERY")){score+=known?-2:4;if(!known)reasons.add("Вы ещё не слушали этот трек");}
            else if(mode.equals("MY_WAVE")){score+=known?.7:1.2;if(!known)reasons.add("Новая композиция из вашей библиотеки");}
            Set<String> matching=switch(mode){case "FOCUS"->Set.of("ambient","classical","instrumental","lo-fi","jazz");case "ENERGY"->Set.of("dance","electronic","rock","metal","house");case "NIGHT","RELAX"->Set.of("ambient","chill","classical","downtempo","acoustic");default->Set.of();};
            if(matching.stream().anyMatch(g->normal(t.genre()).contains(g))){score+=3;reasons.add("Жанр подходит режиму «"+mode+"» (по тегам)");}
            Instant recent=last.get(t.id());if(recent!=null&&recent.isAfter(Instant.now().minus(Duration.ofHours(24)))){score-=Math.min(4,counts.get(t.id())*.8);reasons.add("Учтён недавний повтор");}
            if(reasons.isEmpty())reasons.add("Стартовая подборка из доступного каталога; добавьте предпочтения");
            scored.add(new Recommendation(t,score,reasons));
        }
        // Greedy diversity penalty keeps consecutive recommendations from one artist less likely.
        List<Recommendation> result=new ArrayList<>();Map<String,Integer> selected=new HashMap<>();
        while(!scored.isEmpty()&&result.size()<limit){
            Recommendation best=scored.stream().max(Comparator.comparingDouble((Recommendation r)->r.score()-selected.getOrDefault(r.track().artist(),0)*1.5).thenComparing(r->r.track().id())).orElseThrow();
            scored.remove(best);selected.merge(best.track().artist(),1,Integer::sum);result.add(new Recommendation(best.track(),Math.round(best.score()*100)/100.0,best.reasons()));
        }
        return result;
    }
    private static String normal(String value){return value.strip().toLowerCase(Locale.ROOT);}
}

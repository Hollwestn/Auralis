package io.auralis.desktop.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralis.core.library.LibraryService;
import io.auralis.desktop.visualization.VisualizerPane;
import java.util.*;

/** Shared visual wire keys; unsupported remote values never replace usable local preferences. */
public final class SettingsSynchronization {
    private static final Set<String> THEMES=Set.of("OBSIDIAN_YELLOW","MONOCHROME","CRIMSON_NOIR","DEEP_VIOLET","ARCTIC_SILVER","JADE","AMBER","AZURE");
    private static final Set<String> MODES=Set.of("BLACK_HOLE","NEBULA_DRIFT","RESONANCE","PHOTON_TUNNEL","PRISM_BLOOM","MAGNETIC_FIELD","OCEAN_CAUSTICS","FRACTAL_CATHEDRAL");
    private static final Set<String> QUALITIES=Set.of("LOW","MEDIUM","HIGH");
    private static final Map<String,String> FLAGS=Map.of("visualEnabled","visual.enabled","backgroundEnabled","background.enabled","animationsEnabled","animations.enabled","reducedMotion","reducedMotion","advancedMode","advanced");
    private SettingsSynchronization(){}
    public static Map<String,Object> toServer(LibraryService library,Map<String,Object> existing){
        Map<String,Object> result=new LinkedHashMap<>(existing);
        result.put("theme",choice(library.setting("theme","OBSIDIAN_YELLOW"),THEMES,"OBSIDIAN_YELLOW"));
        String visual=visualKey(library.setting("visual.mode","BLACK_HOLE"));result.put("visualMode",visual.isEmpty()?"BLACK_HOLE":visual);
        result.put("visualQuality",choice(library.setting("visual.quality","MEDIUM"),QUALITIES,"MEDIUM"));
        int fps=45;try{int stored=Integer.parseInt(library.setting("visual.fps","45"));if(Set.of(15,30,45,60).contains(stored))fps=stored;}catch(NumberFormatException ignored){}
        double intensity=.9;try{double stored=Double.parseDouble(library.setting("visual.intensity","0.9"));if(Double.isFinite(stored))intensity=Math.clamp(stored,0,2);}catch(NumberFormatException ignored){}
        result.put("visualIdleSpeed",speed(library,"visual.idleSpeed",1));result.put("visualPlayingSpeed",speed(library,"visual.playingSpeed",.75));
        result.put("visualFps",fps);result.put("visualIntensity",intensity);
        FLAGS.forEach((wire,local)->result.put(wire,Boolean.parseBoolean(library.setting(local,wire.equals("advancedMode")||wire.equals("reducedMotion")?"false":"true"))));return result;
    }
    public static void applyRemote(LibraryService library,JsonNode settings){
        if(!settings.isObject())return;
        applyChoice(library,settings,"theme","theme",THEMES);if(settings.path("visualMode").isTextual()){String value=visualKey(settings.path("visualMode").asText());if(!value.isEmpty())library.setSetting("visual.mode",value);}applyChoice(library,settings,"visualQuality","visual.quality",QUALITIES);
        JsonNode fps=settings.path("visualFps");if(fps.isIntegralNumber()&&Set.of(15,30,45,60).contains(fps.asInt()))library.setSetting("visual.fps",Integer.toString(fps.asInt()));
        JsonNode intensity=settings.path("visualIntensity");if(intensity.isNumber()&&Double.isFinite(intensity.asDouble())&&intensity.asDouble()>=0&&intensity.asDouble()<=2)library.setSetting("visual.intensity",Double.toString(intensity.asDouble()));
        for(var entry:Map.of("visualIdleSpeed","visual.idleSpeed","visualPlayingSpeed","visual.playingSpeed").entrySet()){var node=settings.path(entry.getKey());if(node.isNumber()&&Double.isFinite(node.asDouble())&&node.asDouble()>=0&&node.asDouble()<=2)library.setSetting(entry.getValue(),Double.toString(node.asDouble()));}
        FLAGS.forEach((wire,local)->{JsonNode value=settings.path(wire);if(value.isBoolean())library.setSetting(local,Boolean.toString(value.asBoolean()));});
    }
    private static double speed(LibraryService library,String key,double fallback){try{double value=Double.parseDouble(library.setting(key,Double.toString(fallback)));return Double.isFinite(value)?Math.clamp(value,0,2):fallback;}catch(NumberFormatException failure){return fallback;}}
    private static String visualKey(String value){try{return VisualizerPane.parseMode(value.toUpperCase(Locale.ROOT)).name();}catch(IllegalArgumentException failure){return "";}}
    private static String choice(String value,Set<String> supported,String fallback){String normalized=value.toUpperCase(Locale.ROOT);return supported.contains(normalized)?normalized:fallback;}
    private static void applyChoice(LibraryService library,JsonNode input,String wire,String local,Set<String> supported){JsonNode node=input.path(wire);if(node.isTextual()){String value=node.asText().toUpperCase(Locale.ROOT);if(supported.contains(value))library.setSetting(local,value);}}
}

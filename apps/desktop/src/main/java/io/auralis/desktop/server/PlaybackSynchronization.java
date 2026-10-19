package io.auralis.desktop.server;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Converts complete playback snapshots without losing queue order, duplicates or selection. */
public final class PlaybackSynchronization {
    private PlaybackSynchronization(){}
    @FunctionalInterface public interface LocalResolver { String resolve(String serverId)throws Exception; }

    public static Map<String,Object> toServer(Map<String,Object> snapshot,Function<String,String> remoteId,Function<String,String> title)throws IOException{
        String current=string(snapshot.get("trackId"));List<String> queue=queue(snapshot.get("queue"),current);
        int index=index(snapshot.get("queueIndex"),queue,current);double seconds=position(snapshot.get("positionSeconds"));
        List<String> mapped=new ArrayList<>(),missing=new ArrayList<>();
        for(String local:queue){String remote=remoteId.apply(local);if(remote==null||remote.isBlank()){missing.add(title.apply(local));mapped.add("");}else mapped.add(remote);}
        if(!missing.isEmpty())throw new IOException("Очередь не отправлена: "+missing.size()+" треков доступны только на ПК: "+String.join(", ",missing.stream().limit(3).toList())+". Явно загрузите нужные файлы или уберите их из очереди. Автоматической загрузки нет.");
        Map<String,Object> result=new LinkedHashMap<>(snapshot);result.put("trackId",current.isBlank()?null:mapped.get(index));result.put("queue",mapped);result.put("queueIndex",index);result.put("positionSeconds",seconds);
        normalizeOptions(result);return result;
    }

    public static ObjectNode toLocal(JsonNode state,LocalResolver resolver)throws Exception{
        if(!state.isObject())throw new IOException("Сервер вернул некорректное состояние проигрывателя");
        String current=state.path("trackId").asText("");if(current.isBlank())throw new IOException("На сервере нет текущего трека");
        ObjectMapper json=new ObjectMapper();Map<String,Object> values=json.convertValue(state,new com.fasterxml.jackson.core.type.TypeReference<>(){});
        List<String> serverQueue=queue(values.get("queue"),current);int index=index(values.get("queueIndex"),serverQueue,current);position(values.get("positionSeconds"));normalizeOptions(values);
        // Resolve each distinct file once but preserve every occurrence in the actual queue.
        Map<String,String> resolved=new HashMap<>();List<String> local=new ArrayList<>();
        for(String id:serverQueue){String localId=resolved.get(id);if(localId==null){localId=resolver.resolve(id);if(localId==null||localId.isBlank())throw new IOException("Серверный трек недоступен на этом устройстве: "+id);resolved.put(id,localId);}local.add(localId);}
        ObjectNode result=json.valueToTree(values);result.put("localTrackId",local.get(index));result.set("localQueueIds",json.valueToTree(local));result.put("queueIndex",index);return result;
    }

    private static List<String> queue(Object raw,String current)throws IOException{
        if(raw==null)return current.isBlank()?List.of():List.of(current);
        if(!(raw instanceof List<?> items)||items.size()>1000)throw new IOException("Очередь должна содержать не более 1000 треков");
        // Legacy clients sometimes sent an empty queue together with a current track.
        if(items.isEmpty()&&!current.isBlank())return List.of(current);
        List<String> result=new ArrayList<>();for(Object item:items){if(!(item instanceof String id)||id.isBlank())throw new IOException("В очереди обнаружен некорректный идентификатор");result.add(id);}return result;
    }
    private static int index(Object raw,List<String> queue,String current)throws IOException{
        if(queue.isEmpty()){if(!current.isBlank()||raw!=null&&(!(raw instanceof Number n)||n.doubleValue()!=-1))throw new IOException("Некорректная пустая очередь");return -1;}
        if(current.isBlank())throw new IOException("В очереди не выбран текущий трек");
        int index;
        if(raw==null)index=queue.indexOf(current);
        else{if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue())throw new IOException("Некорректный queueIndex");index=n.intValue();}
        if(index<0||index>=queue.size()||!queue.get(index).equals(current))throw new IOException("Текущий трек и индекс очереди не совпадают");return index;
    }
    private static String string(Object value)throws IOException{if(value==null)return "";if(!(value instanceof String text))throw new IOException("Некорректный trackId");return text;}
    private static double position(Object value)throws IOException{if(value==null)return 0;if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<0)throw new IOException("Некорректная позиция прослушивания");return number.doubleValue();}
    private static void normalizeOptions(Map<String,Object> state)throws IOException{
        for(String option:List.of("playing","shuffle"))if(state.containsKey(option)&&!(state.get(option) instanceof Boolean))throw new IOException(option+" должен быть boolean");
        if(state.containsKey("repeat")){Object raw=state.get("repeat");if(!(raw instanceof String text)||!Set.of("OFF","ALL","ONE").contains(text.toUpperCase(Locale.ROOT)))throw new IOException("Неизвестный режим повтора");state.put("repeat",text.toUpperCase(Locale.ROOT));}
    }
}

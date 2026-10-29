import React, { useEffect, useRef, useState } from 'react';
import { PanResponder, type PanResponderInstance, Pressable, ScrollView, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useAuralis } from '../core/AuralisProvider';
import { Button, Cover, Field, IconButton, Notice, Screen, ui } from '../components/ui';
import { palette as p } from '../core/theme';

export default function Queue() {
  const app=useAuralis(), [name,setName]=useState('Моя очередь'), [drag,setDrag]=useState<number|null>(null), active=useRef<number|null>(null);
  const latest=useRef(app);useEffect(()=>{latest.current=app;},[app]);
  const [pan,setPan]=useState<PanResponderInstance|null>(null);
  useEffect(()=>{setPan(PanResponder.create({onMoveShouldSetPanResponder:()=>active.current!==null,onPanResponderTerminationRequest:()=>false,onPanResponderRelease:(_,gesture)=>{const from=active.current;active.current=null;setDrag(null);if(from!==null){const to=Math.max(0,Math.min(latest.current.queue.length-1,from+Math.round(gesture.dy/82)));if(to!==from)latest.current.run(()=>latest.current.moveQueueItem(from,to));}},onPanResponderTerminate:()=>{active.current=null;setDrag(null);}}));},[]);
  return <Screen><Notice/><ScrollView scrollEnabled={drag===null} contentContainerStyle={{padding:22,gap:18}} {...pan?.panHandlers}><View style={ui.between}><Text style={ui.title}>Очередь</Text><IconButton name="close" label="Закрыть очередь" onPress={()=>router.back()}/></View><Text style={ui.muted}>Удерживайте строку и потяните вверх или вниз. Кнопки стрелок также меняют порядок без перезапуска текущего трека.</Text><Button secondary label="Отменить последнее изменение" disabled={!app.canUndoQueue} onPress={()=>app.run(app.undoQueue)}/>
    {app.queue.map((track,index)=><View key={`${track.id}-${index}`} style={[ui.row,{gap:8,minHeight:82,opacity:drag===index ? 0.6 : 1,backgroundColor:index===app.status.currentIndex?p.surface:'transparent',borderRadius:14}]}><Pressable accessibilityRole="button" accessibilityLabel={`${index+1}. ${track.artist}, ${track.title}${index===app.status.currentIndex?', текущий трек':''}`} accessibilityHint="Удерживайте для перемещения. Для изменения порядка также доступны кнопки выше и ниже." onLongPress={()=>{active.current=index;setDrag(index);}} style={[ui.row,{flex:1,gap:10,minHeight:72}]}><Cover track={track} size={44}/><View style={{flex:1}}><Text numberOfLines={1} style={[ui.text,index===app.status.currentIndex&&{color:app.accent}]}>{track.title}</Text><Text numberOfLines={1} style={ui.muted}>{track.artist}</Text></View></Pressable><IconButton name="chevron-up" label={`Поднять ${track.title}`} onPress={()=>{if(index>0)app.run(()=>app.moveQueueItem(index,index-1));}} size={18}/><IconButton name="chevron-down" label={`Опустить ${track.title}`} onPress={()=>{if(index<app.queue.length-1)app.run(()=>app.moveQueueItem(index,index+1));}} size={18}/><IconButton name="close" label={`Убрать ${track.title} из очереди`} onPress={()=>app.run(()=>app.removeQueueItem(index))} size={17}/></View>)}
    <View style={ui.card}><Text style={ui.subtitle}>Сохранить как плейлист</Text><Field label="Название" value={name} onChangeText={setName}/><Button label="Сохранить очередь" disabled={!name.trim()||!app.queue.length} onPress={()=>app.run(()=>app.saveQueuePlaylist(name))}/></View>
  </ScrollView></Screen>;
}

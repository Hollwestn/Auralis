import React, { useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useAuralis } from '../core/AuralisProvider';
import { MetadataEditor } from '../components/MetadataEditor';
import { Button, Cover, IconButton, Screen, ui } from '../components/ui';

export default function Metadata(){
 const app=useAuralis(),params=useLocalSearchParams<{ids?:string}>();
 const initial=()=>{try{const value=JSON.parse(params.ids||'[]');return Array.isArray(value)?value.filter((id):id is string=>typeof id==='string'):[];}catch{return [];}};
 const [selected,setSelected]=useState<string[]>(initial),[editing,setEditing]=useState(!!params.ids);
 const tracks=app.library.tracks.filter(t=>selected.includes(t.id));
 if(editing&&tracks.length)return <MetadataEditor tracks={tracks} onClose={()=>{if(params.ids)router.back();else setEditing(false);}}/>;
 return <Screen><ScrollView contentContainerStyle={{padding:22,gap:18}}><View style={ui.between}><Text style={ui.title}>Метаданные</Text><IconButton name="close" label="Закрыть" onPress={()=>router.back()}/></View><Text style={ui.muted}>Выберите треки для групповой правки. Аудиофайлы останутся прежними.</Text><Button secondary label="Выбрать все" onPress={()=>setSelected(app.library.tracks.map(t=>t.id))}/><Button label={`Редактировать: ${tracks.length}`} disabled={!tracks.length||!app.localMode} onPress={()=>setEditing(true)}/>{app.library.tracks.map(track=><Pressable key={track.id} accessibilityRole="checkbox" accessibilityLabel={`${track.artist} — ${track.title}`} accessibilityState={{checked:selected.includes(track.id)}} onPress={()=>setSelected(ids=>ids.includes(track.id)?ids.filter(id=>id!==track.id):[...ids,track.id])} style={[ui.row,{gap:12,minHeight:64}]}><Cover track={track} size={42}/><View style={{flex:1}}><Text style={ui.text}>{track.title}</Text><Text style={ui.muted}>{track.artist}</Text></View><Text style={{color:app.accent}}>{selected.includes(track.id)?'✓':''}</Text></Pressable>)}</ScrollView></Screen>;
}

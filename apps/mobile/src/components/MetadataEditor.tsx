import React, { useState } from 'react';
import { Modal, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useAuralis } from '../core/AuralisProvider';
import type { Track } from '../core/types';
import { palette as p } from '../core/theme';
import { Button, Field, ui } from './ui';

export function MetadataEditor({tracks,onClose}:{tracks:Track[];onClose:()=>void}){
 const app=useAuralis(),batch=tracks.length>1,first=tracks[0];
 const [title,setTitle]=useState(batch?'':first.title),[artist,setArtist]=useState(batch?'':first.artist),[album,setAlbum]=useState(batch?'':first.album),[genre,setGenre]=useState(batch?'':first.genre),[number,setNumber]=useState(batch?'':String(first.trackNumber||0)),[disc,setDisc]=useState(batch?'':String(first.discNumber||0)),[cover,setCover]=useState<string|null>(null);
 const changes=()=>({...(title.trim()?{title:title.trim()}:{}),...(!batch||artist.trim()?{artist:artist.trim()}:{}),...(!batch||album.trim()?{album:album.trim()}:{}),...(!batch||genre.trim()?{genre:genre.trim()}:{}),...(number.trim()?{trackNumber:Number(number)}:{}),...(disc.trim()?{discNumber:Number(disc)}:{}),...(cover?{coverUrl:cover}:{})});
 return <Modal visible animationType="slide" onRequestClose={onClose}><SafeAreaView style={{flex:1,backgroundColor:p.background}}><ScrollView contentContainerStyle={{padding:24,gap:18}}><Text style={ui.title}>{batch?`Изменить ${tracks.length} треков`:'Метаданные'}</Text><Text style={ui.muted}>Изменения сохранятся только в библиотеке Auralis. {batch?'Пустые поля останутся прежними.':''}</Text><Field label="Название" value={title} onChangeText={setTitle}/><Field label="Исполнитель" value={artist} onChangeText={setArtist}/><Field label="Альбом" value={album} onChangeText={setAlbum}/><Field label="Жанр" value={genre} onChangeText={setGenre}/><Field label="Номер трека" keyboardType="number-pad" value={number} onChangeText={setNumber}/><Field label="Номер диска" keyboardType="number-pad" value={disc} onChangeText={setDisc}/><Button secondary label={cover?'Обложка выбрана':'Выбрать обложку'} onPress={()=>app.run(async()=>setCover(await app.pickTrackCover()))}/><View style={ui.card}><Text style={ui.subtitle}>Предварительный просмотр</Text><Text style={ui.text}>{artist||first.artist} — {title||first.title}</Text><Text style={ui.muted}>{album||first.album}</Text></View><Button label="Применить к библиотеке" onPress={()=>app.run(async()=>{await app.updateLocalMetadata(tracks.map(t=>t.id),changes());onClose();})}/><Button secondary label="Отмена" onPress={onClose}/></ScrollView></SafeAreaView></Modal>;
}

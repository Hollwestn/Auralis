import React, { useMemo, useState } from 'react';
import { Alert, FlatList, Modal, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useAuralis } from '../../core/AuralisProvider';
import { Button, Empty, Field, IconButton, Notice, Screen, TrackRow, ui } from '../../components/ui';
import { palette as p } from '../../core/theme';
import { smartCollection, type SmartCollection } from '../../core/collections';
import type { Playlist, Track } from '../../core/types';

export default function Library() {
  const app = useAuralis();
  const [filter, setFilter] = useState('all');
  const [manage, setManage] = useState(false);
  const [name, setName] = useState('');
  const [editing, setEditing] = useState<Playlist | null>(null);
  const selected = app.library.playlists.find(item => item.id === filter);
  const tracks = useMemo(() => {
    if (['recent', 'rediscover', 'mostPlayed'].includes(filter)) return smartCollection(app.library, filter as SmartCollection);
    if (filter === 'favorites') return app.library.favorites;
    if (filter === 'downloads') return app.downloads.map(item => item.track);
    if (filter === 'history') return [...new Set(app.library.history.map(item => item.trackId))].map(id => app.library.tracks.find(track => track.id === id)).filter((track): track is Track => !!track);
    if (selected) return selected.trackIds.map(id => app.library.tracks.find(track => track.id === id)).filter((track): track is Track => !!track);
    return app.library.tracks;
  }, [app.library, app.downloads, filter, selected]);
  const filters = [{ id: 'all', name: 'Все' }, { id: 'favorites', name: 'Избранное' }, ...(!app.localMode ? [{ id: 'downloads', name: 'Скачано' }] : []), { id: 'history', name: 'История' }, ...(app.localMode ? [{ id: 'recent', name: 'Недавно добавлено' }] : []), { id: 'rediscover', name: 'Открыть заново' }, { id: 'mostPlayed', name: 'Чаще слушаю' }, ...app.library.playlists];
  const save = async () => {
    if (editing) await app.savePlaylist({ ...editing, name: name.trim() }); else await app.createPlaylist(name.trim());
    setName(''); setEditing(null);
  };
  return <Screen><Notice /><FlatList data={tracks} keyExtractor={(item, index) => `${item.id}:${index}`} renderItem={({ item, index }) => <View><TrackRow track={item} tracks={tracks} queueIndex={index} />{selected && <Pressable accessibilityRole="button" accessibilityLabel={`Удалить ${item.title} из плейлиста`} onPress={() => app.run(() => app.savePlaylist({ ...selected, trackIds: selected.trackIds.filter((_, itemIndex) => itemIndex !== index) }))} style={{ alignSelf: 'flex-end', marginRight: 22, marginBottom: 8, padding: 8 }}><Text style={{ color: p.muted, fontSize: 11 }}>Убрать из плейлиста</Text></Pressable>}</View>} ListHeaderComponent={<>
    <View style={{ padding: 22, gap: 15 }}><Text style={ui.eyebrow}>СОБРАНО ВАМИ</Text><View style={ui.between}><Text style={ui.title}>Моя музыка</Text><IconButton name="add-circle-outline" label="Управлять плейлистами" onPress={() => setManage(true)} color={app.accent} size={29} /></View></View>
    {app.localMode && <View style={{ paddingHorizontal: 22, paddingBottom: 20, gap: 10 }}><Text style={ui.muted}>Локальная библиотека · без аккаунта</Text><Button icon="add-outline" label={app.importing ? 'Добавление музыки…' : 'Добавить музыку с телефона'} disabled={app.importing} onPress={() => app.run(app.importMusic)} /></View>}
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: 22, gap: 9, paddingBottom: 20 }}>{filters.map(item => <Pressable key={item.id} accessibilityRole="button" accessibilityState={{ selected: filter === item.id }} onPress={() => setFilter(item.id)} style={{ backgroundColor: filter === item.id ? app.accent : p.raised, paddingHorizontal: 17, paddingVertical: 11, borderRadius: 24 }}><Text style={{ color: filter === item.id ? '#10222B' : p.text, fontWeight: '600', fontSize: 12 }}>{item.name}</Text></Pressable>)}</ScrollView>
    <View style={[ui.between, { paddingHorizontal: 22, marginBottom: 10 }]}><Text style={ui.muted}>{tracks.length} треков {filter === 'downloads' ? '· без интернета' : ''}</Text>{selected && <Pressable accessibilityRole="button" onPress={() => { setEditing(selected); setName(selected.name); setManage(true); }} style={{ padding: 8 }}><Text style={{ color: app.accent, fontSize: 12 }}>Изменить</Text></Pressable>}</View>
  </>} ListEmptyComponent={<Empty title={filter === 'downloads' ? 'Музыка с собой' : selected ? 'Ваш новый плейлист' : 'Пока пусто'} detail={app.localMode ? 'Добавьте файлы кнопкой выше. В меню трека можно добавить его в плейлист или избранное.' : filter === 'downloads' ? 'Откройте меню трека и выберите «Скачать для офлайна». Файлы доступны только в вашем аккаунте Auralis.' : 'В меню любого трека можно добавить его в плейлист, избранное или скачать.'} />} />
    <Modal visible={manage} transparent animationType="fade" onRequestClose={() => setManage(false)}><View style={{ flex: 1, backgroundColor: '#000A', justifyContent: 'flex-end' }}><Pressable accessibilityLabel="Закрыть" style={{ flex: 1 }} onPress={() => setManage(false)} /><SafeAreaView edges={['bottom']} style={{ padding: 22, gap: 14, backgroundColor: p.surface, borderTopLeftRadius: 26, borderTopRightRadius: 26, maxHeight: '80%' }}><Text style={ui.subtitle}>{editing ? 'Изменить плейлист' : 'Новый плейлист'}</Text><Field label="Название" value={name} onChangeText={setName} placeholder="Музыка для вечера" maxLength={120} /><Button disabled={!name.trim()} label={editing ? 'Сохранить название' : 'Создать плейлист'} onPress={() => app.run(save)} />{editing && <Button secondary label="Удалить плейлист" onPress={() => Alert.alert('Удалить плейлист?', 'Музыкальные файлы останутся в каталоге.', [{ text: 'Отмена', style: 'cancel' }, { text: 'Удалить', style: 'destructive', onPress: () => app.run(async () => { await app.deletePlaylist(editing.id); setEditing(null); setName(''); setFilter('all'); }) }])} />}<ScrollView>{app.library.playlists.map(playlist => <View style={ui.between} key={playlist.id}><Text style={[ui.text, { flex: 1 }]}>{playlist.name}</Text><IconButton name="create-outline" label={`Изменить ${playlist.name}`} onPress={() => { setEditing(playlist); setName(playlist.name); }} /></View>)}</ScrollView><Button secondary label="Готово" onPress={() => { setManage(false); setName(''); setEditing(null); }} /></SafeAreaView></View></Modal>
  </Screen>;
}

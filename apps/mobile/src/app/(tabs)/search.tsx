import React, { useMemo, useState } from 'react';
import { FlatList, Text, TextInput, View } from 'react-native';
import { useAuralis } from '../../core/AuralisProvider';
import { Empty, Icon, Notice, Screen, TrackRow, ui } from '../../components/ui';
import { palette as p } from '../../core/theme';

export default function Search() {
  const app = useAuralis();
  const [query, setQuery] = useState('');
  const tracks = useMemo(() => {
    const all = [...app.library.tracks, ...app.downloads.map(item => item.track)];
    const unique = [...new Map(all.map(track => [track.id, track])).values()];
    const text = query.trim().toLocaleLowerCase();
    return text ? unique.filter(track => `${track.title} ${track.artist} ${track.album} ${track.genre}`.toLocaleLowerCase().includes(text)) : unique;
  }, [query, app.library.tracks, app.downloads]);
  return <Screen><Notice /><View style={{ padding: 22, gap: 19 }}><Text style={ui.eyebrow}>ИССЛЕДУЙТЕ СВОЙ ЗВУК</Text><Text style={ui.title}>Что послушаем?</Text><View style={[ui.row, ui.input, { gap: 12 }]}><Icon name="search-outline" color={p.muted} size={21} /><TextInput value={query} onChangeText={setQuery} placeholder="Трек, исполнитель, альбом, жанр" placeholderTextColor={p.muted} autoCapitalize="none" autoCorrect={false} accessibilityLabel="Поиск музыки" style={{ flex: 1, color: p.text, fontSize: 14 }} clearButtonMode="while-editing" /></View><Text style={ui.muted}>{query ? `Найдено: ${tracks.length}` : `Ваш каталог · ${tracks.length} треков`}</Text></View><FlatList data={tracks} keyExtractor={track => track.id} keyboardShouldPersistTaps="handled" renderItem={({ item }) => <TrackRow track={item} tracks={tracks} />} ListEmptyComponent={<Empty title={query ? 'Пока ничего' : 'Каталог пуст'} detail={query ? 'Попробуйте другое название, исполнителя или жанр.' : app.localMode ? 'Добавьте файлы с телефона в разделе «Моя музыка».' : 'Добавьте музыку на сервер, затем обновите главную страницу.'} />} /></Screen>;
}

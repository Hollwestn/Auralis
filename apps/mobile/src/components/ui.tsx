import React, { useEffect, useState } from 'react';
import { artworkSource } from '../core/artwork';
import { AccessibilityInfo, ActivityIndicator, Alert, Animated, Image, Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View, type TextInputProps, type ViewStyle } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { LinearGradient } from 'expo-linear-gradient';
import { router } from 'expo-router';
import { useAuralis } from '../core/AuralisProvider';
import { formatTime, palette as p } from '../core/theme';
import type { Track } from '../core/types';
import { NetworkBackdrop } from './NetworkBackdrop';

export const ui = StyleSheet.create({
  text: { color: p.text, fontSize: 15, lineHeight: 23 }, muted: { color: p.muted, fontSize: 13, lineHeight: 20 },
  title: { color: p.text, fontSize: 32, lineHeight: 39, letterSpacing: -1.1, fontWeight: '800' },
  subtitle: { color: p.text, fontSize: 21, fontWeight: '700', letterSpacing: -0.4 },
  eyebrow: { color: p.muted, fontSize: 10, fontWeight: '700', letterSpacing: 2.2 },
  row: { flexDirection: 'row', alignItems: 'center' }, between: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  section: { paddingHorizontal: 22, marginTop: 28, gap: 14 }, card: { padding: 18, borderRadius: 18, backgroundColor: p.surface, gap: 10 },
  input: { backgroundColor: p.surface, borderWidth: 1, borderColor: p.border, borderRadius: 14, paddingHorizontal: 16, paddingVertical: 14, fontSize: 16, color: p.text, minHeight: 52 },
});
export type IconName = React.ComponentProps<typeof Ionicons>['name'];
export function Icon({ name, size = 23, color = p.text }: { name: IconName; size?: number; color?: string }) { return <Ionicons name={name} size={size} color={color} />; }
export function IconButton({ name, label, onPress, color, size = 23 }: { name: IconName; label: string; onPress: () => void; color?: string; size?: number }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={({ pressed }) => ({ minWidth: 48, minHeight: 48, justifyContent: 'center', alignItems: 'center', opacity: pressed ? 0.55 : 1 })}><Icon name={name} color={color} size={size} /></Pressable>;
}
export function Button({ label, onPress, secondary = false, disabled = false, icon }: { label: string; onPress: () => void; secondary?: boolean; disabled?: boolean; icon?: IconName }) {
  const { accent } = useAuralis();
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled }} disabled={disabled} onPress={onPress} style={({ pressed }) => ({ borderRadius: 26, minHeight: 50, paddingVertical: 12, paddingHorizontal: 20, backgroundColor: secondary ? p.raised : accent, opacity: disabled ? 0.35 : pressed ? 0.7 : 1, flexDirection: 'row', gap: 9, alignItems: 'center', justifyContent: 'center' })}>{icon && <Icon name={icon} size={19} color={secondary ? p.text : '#0C191E'} />}<Text style={{ fontSize: 14, fontWeight: '700', color: secondary ? p.text : '#0C191E' }}>{label}</Text></Pressable>;
}
export function Field(props: TextInputProps & { label: string }) {
  return <View style={{ gap: 7 }}><Text style={ui.muted}>{props.label}</Text><TextInput accessibilityLabel={props.label} {...props} placeholderTextColor="#68707B" style={[ui.input, props.style]} /></View>;
}
export function Screen({ children, style }: { children: React.ReactNode; style?: ViewStyle }) {
  const [opacity] = useState(() => new Animated.Value(0));
  const { experience, accent, visuals, session } = useAuralis();
  useEffect(() => { void AccessibilityInfo.isReduceMotionEnabled().then(reduced => { if (reduced || experience.reducedMotion || !experience.animationsEnabled) opacity.setValue(1); else Animated.timing(opacity, { toValue: 1, duration: 220, useNativeDriver: true }).start(); }); }, [opacity, experience.reducedMotion, experience.animationsEnabled]);
  return <SafeAreaView edges={['top', 'left', 'right']} style={{ flex: 1, backgroundColor: p.background }}>{session && experience.backgroundEnabled && visuals.enabled && <NetworkBackdrop accent={accent} animate={experience.animationsEnabled && !experience.reducedMotion} quality={visuals.quality}/>}<Animated.View style={[{ flex: 1, opacity }, style]}>{children}</Animated.View></SafeAreaView>;
}
export function Notice() {
  const { notice, setNotice, online, session, localMode } = useAuralis();
  if (!notice && (online || localMode || !session)) return null;
  return <Pressable onPress={() => setNotice('')} style={{ backgroundColor: '#2A2620', paddingHorizontal: 22, paddingVertical: 10 }}><Text style={{ color: '#E9CCA0', fontSize: 12, lineHeight: 18 }}>{notice || (session ? 'Офлайн · доступна скачанная музыка' : '')}</Text></Pressable>;
}
export function Cover({ track, size = 54 }: { track?: Track | null; size?: number }) {
  const { accent, session, downloads } = useAuralis();
  const saved = downloads.find(item => item.track.id === track?.id && item.track.coverUrl?.startsWith('file://'));
  const source = artworkSource(saved?.track || track || null, session);
  const [failedUri, setFailedUri] = useState('');
  if (source && failedUri !== source.uri) return <Image source={source} onError={() => setFailedUri(source.uri)} style={{ width: size, height: size, borderRadius: size > 100 ? 20 : 10 }} accessibilityLabel={`Обложка: ${track?.album || track?.title}`} />;
  const seed = Array.from(track?.id || 'auralis').reduce((value, char) => value + char.charCodeAt(0), 0);
  const colors = ['#273833', '#34263C', '#383026', '#243340', '#3B282A'];
  const initial = (track?.artist || track?.title || 'A').slice(0, 1).toUpperCase();
  return <LinearGradient colors={[colors[seed % colors.length], '#0E1014']} style={{ width: size, height: size, borderRadius: size > 100 ? 14 : 8, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }} accessibilityLabel="Типографическая обложка: оригинальная обложка отсутствует">
    <View style={{ position: 'absolute', height: size * 1.5, width: size * .3, backgroundColor: `${accent}22`, transform: [{ rotate: `${25 + seed % 50}deg` }] }} /><View style={{ position: 'absolute', width: size * .8, height: size * .8, borderWidth: 1, borderColor: '#FFFFFF12', borderRadius: size * .4, left: size * .55 }} /><Text style={{ color: '#F7F4E8', fontSize: size * .41, fontWeight: '300', letterSpacing: -size * .03 }}>{initial}</Text>{size > 100 && <Text style={{ position: 'absolute', bottom: size * .08, left: size * .08, color: '#FFFFFF66', fontSize: size * .055, letterSpacing: 2 }}>AURALIS / {String(seed % 100).padStart(2, '0')}</Text>}
  </LinearGradient>;
}
export function Empty({ title, detail }: { title: string; detail: string }) { return <View style={{ padding: 32, alignItems: 'center', gap: 11 }}><Icon name="albums-outline" size={36} color={p.muted} /><Text style={[ui.subtitle, { textAlign: 'center' }]}>{title}</Text><Text style={[ui.muted, { textAlign: 'center', maxWidth: 310 }]}>{detail}</Text></View>; }

export function TrackRow({ track, tracks, queueIndex }: { track: Track; tracks: Track[]; queueIndex?: number }) {
  const app = useAuralis();
  const [menu, setMenu] = useState(false);
  const [choosePlaylist, setChoosePlaylist] = useState(false);
  const active = app.current?.id === track.id;
  const downloaded = app.downloads.some(item => item.track.id === track.id);
  const favorite = app.library.favorites.some(item => item.id === track.id);
  const act = (task: () => Promise<unknown>) => { setMenu(false); app.run(task); };
  return <>
    <View style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: 8, paddingLeft: 22, paddingRight: 12, gap: 13 }}>
      <Pressable accessibilityRole="button" accessibilityLabel={`Слушать ${track.title}, ${track.artist}`} onPress={() => app.run(() => app.play(track, tracks, 0, queueIndex))} onLongPress={() => setMenu(true)} style={{ flex: 1, flexDirection: 'row', alignItems: 'center', gap: 13, minHeight: 56 }}>
        <Cover track={track} /><View style={{ flex: 1, gap: 3 }}><Text numberOfLines={1} style={{ color: active ? app.accent : p.text, fontWeight: '600', fontSize: 15 }}>{track.title}</Text><Text numberOfLines={1} style={ui.muted}>{track.artist || 'Неизвестный исполнитель'} {downloaded ? ' · ↓' : ''}</Text></View><Text style={[ui.muted, { fontSize: 11 }]}>{formatTime(track.durationSeconds)}</Text>
      </Pressable>
      <IconButton name="ellipsis-horizontal" label={`Действия: ${track.title}`} onPress={() => setMenu(true)} />
    </View>
    <Modal visible={menu} transparent animationType="fade" onRequestClose={() => { setMenu(false); setChoosePlaylist(false); }}>
      <View style={{ flex: 1, backgroundColor: '#0009', justifyContent: 'flex-end' }}>
        <Pressable accessibilityLabel="Закрыть меню" onPress={() => { setMenu(false); setChoosePlaylist(false); }} style={{ flex: 1 }} />
        <SafeAreaView edges={['bottom']} style={{ backgroundColor: p.surface, borderTopLeftRadius: 28, borderTopRightRadius: 28, padding: 22, gap: 10, maxHeight: '75%' }}>
          <Text style={ui.subtitle}>{track.title}</Text><Text style={ui.muted}>{track.artist}</Text>
          {choosePlaylist ? <ScrollView><Text style={[ui.eyebrow, { marginVertical: 16 }]}>ДОБАВИТЬ В ПЛЕЙЛИСТ</Text>{app.library.playlists.map(playlist => <View key={playlist.id} style={{ marginBottom: 10 }}><Button secondary label={playlist.name} onPress={() => act(() => app.savePlaylist({ ...playlist, trackIds: playlist.trackIds.includes(track.id) ? playlist.trackIds : [...playlist.trackIds, track.id] }))} /></View>)}{!app.library.playlists.length && <Text style={ui.muted}>Сначала создайте плейлист в «Моей музыке».</Text>}<Button secondary label="Назад" onPress={() => setChoosePlaylist(false)} /></ScrollView> : <ScrollView contentContainerStyle={{gap:10}}>
            <Button secondary icon={favorite ? 'heart' : 'heart-outline'} label={favorite ? 'Убрать из избранного' : 'В избранное'} onPress={() => act(() => app.favorite(track))} />
            <Button secondary icon="play-forward-outline" label="Следующим" onPress={()=>act(()=>app.queueTrack(track,true))}/>
            <Button secondary icon="add-outline" label="В конец очереди" onPress={()=>act(()=>app.queueTrack(track,false))}/>
            {app.localMode&&<Button secondary icon="create-outline" label="Метаданные и обложка" onPress={()=>{setMenu(false);router.push({pathname:'/metadata',params:{ids:JSON.stringify([track.id])}});}}/>}
            <Button secondary icon="list-outline" label="В плейлист" onPress={() => setChoosePlaylist(true)} />
            {app.localMode ? <Button secondary icon="trash-outline" disabled={app.importing} label="Удалить из библиотеки" onPress={() => { setMenu(false); Alert.alert('Удалить трек?', 'Будет удалена только копия внутри Auralis. Исходный файл в памяти телефона останется.', [{ text: 'Отмена', style: 'cancel' }, { text: 'Удалить', style: 'destructive', onPress: () => app.run(() => app.removeLocalTrack(track.id)) }]); }} /> : <Button secondary icon={downloaded ? 'trash-outline' : 'download-outline'} disabled={!!app.downloading} label={downloaded ? 'Удалить скачанный файл' : app.downloading === track.id ? 'Скачивание…' : 'Скачать для офлайна'} onPress={() => { setMenu(false); if (downloaded) app.removeDownload(track.id); else app.run(() => app.download(track)); }} />}
            {active && <Button secondary icon="thumbs-down-outline" label="Меньше похожего" onPress={() => act(() => app.feedback('LESS'))} />}
            <Button label="Готово" onPress={() => setMenu(false)} />
          </ScrollView>}
        </SafeAreaView>
      </View>
    </Modal>
  </>;
}

export function MiniPlayer() {
  const app = useAuralis();
  if (!app.current) return null;
  const fraction = app.status.duration > 0 ? Math.min(1, app.status.currentTime / app.status.duration) : 0;
  return <View style={{ marginHorizontal: 10, marginBottom: 6, backgroundColor: p.raised, borderRadius: 13, overflow: 'hidden' }}><View style={[ui.row, { paddingHorizontal: 10, paddingVertical: 5, gap: 11 }]}><Pressable accessibilityRole="button" accessibilityLabel="Открыть плеер" onPress={() => router.push('/player')} style={[ui.row, { flex: 1, gap: 11 }]}><Cover track={app.current} size={43} /><View style={{ flex: 1 }}><Text numberOfLines={1} style={{ color: p.text, fontWeight: '600', fontSize: 13 }}>{app.current.title}</Text><Text numberOfLines={1} style={[ui.muted, { fontSize: 11 }]}>{app.current.artist}</Text></View></Pressable>{app.status.isBuffering ? <ActivityIndicator color={app.accent} /> : <IconButton name={app.status.playing ? 'pause' : 'play'} label={app.status.playing ? 'Пауза' : 'Слушать'} onPress={() => app.run(app.togglePlayback)} />}<IconButton name="play-skip-forward" label="Следующий трек" onPress={() => app.run(() => app.next())} /></View><View style={{ height: 2, backgroundColor: p.border }}><View style={{ height: 2, width: `${fraction * 100}%`, backgroundColor: app.accent }} /></View></View>;
}

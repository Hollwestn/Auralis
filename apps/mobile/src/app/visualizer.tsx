import React from 'react';
import { ScrollView, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useAuralis } from '../core/AuralisProvider';
import { AudioVisual } from '../components/AudioVisual';
import { VisualControls } from '../components/VisualControls';
import { IconButton, Screen, ui } from '../components/ui';
import { palette as p } from '../core/theme';

export default function Visualizer() {
  const app = useAuralis();
  return <Screen><ScrollView contentContainerStyle={{ paddingBottom: 32 }}><View style={[ui.between, { paddingHorizontal: 16, marginTop: 3 }]}><IconButton name="chevron-down" label="Закрыть визуализацию" onPress={() => router.back()} /><Text style={[ui.eyebrow, { color: app.accent }]}>THE SOUND SPACE</Text><IconButton name={app.status.playing ? 'pause' : 'play'} label={app.status.playing ? 'Пауза' : 'Слушать'} onPress={() => app.run(app.togglePlayback)} /></View><AudioVisual mode={app.visuals.mode} accent={app.accent} intensity={app.visuals.intensity} quality={app.visuals.quality} fps={app.visuals.fps} idleSpeed={app.visuals.idleSpeed} playingSpeed={app.visuals.playingSpeed} animate={app.experience.animationsEnabled && !app.experience.reducedMotion} coverUrl={app.currentArtwork?.uri} coverToken={app.currentArtwork?.headers?.Authorization?.replace(/^Bearer /, '')} enabled={app.visuals.enabled} playing={app.status.playing} style={{ height: 540 }} /><View style={{ padding: 22, gap: 27 }}><View style={{ gap: 7 }}><Text style={[ui.title, { fontSize: 29 }]}>{app.visuals.mode}</Text><Text style={ui.muted}>Форма и свечение следуют за низкими частотами вашей музыки. Нажатия не запускают эффекты.</Text>{app.current && <Text numberOfLines={2} style={{ color: p.text, fontSize: 13 }}>{app.current.artist} — {app.current.title}</Text>}</View><VisualControls /></View></ScrollView></Screen>;
}


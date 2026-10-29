import React from 'react';
import { Pressable, ScrollView, Switch, Text, View } from 'react-native';
import Slider from '@react-native-community/slider';
import { useAuralis } from '../core/AuralisProvider';
import { visualModes, type VisualQuality } from '../visuals/shaders';
import { audioTelemetry } from '../core/spectrum';
import { palette as p } from '../core/theme';
import { ui } from './ui';

export function VisualControls() {
  const app = useAuralis();
  return <View style={{ gap: 16 }}>
    <View style={ui.between}><View style={{ gap: 4 }}><Text style={ui.subtitle}>Живое пространство</Text><Text style={ui.muted}>Восемь GPU-сцен</Text></View><Switch accessibilityLabel="GPU-визуализация" value={app.visuals.enabled} onValueChange={enabled => app.run(() => app.setVisualPreferences({ enabled }))} trackColor={{ true: app.tint, false: p.border }} thumbColor={app.visuals.enabled ? app.accent : p.muted} /></View>
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>{visualModes.map((mode, index) => <Pressable key={mode} accessibilityRole="button" accessibilityState={{ selected: app.visuals.mode === mode }} onPress={() => app.run(() => app.setVisualPreferences({ mode }))} style={{ minWidth: 126, padding: 16, gap: 13, borderRadius: 16, borderColor: app.visuals.mode === mode ? app.accent : p.border, borderWidth: 1, backgroundColor: p.surface }}><Text style={{ color: app.accent, fontSize: 9, letterSpacing: 2 }}>0{index + 1}</Text><Text style={{ color: p.text, fontSize: 11, fontWeight: '600' }}>{mode}</Text></Pressable>)}</ScrollView>
    <View style={[ui.row, { gap: 8 }]}>{(['Eco', 'Balanced', 'Ultra'] as VisualQuality[]).map((quality, index) => <Pressable key={quality} accessibilityRole="button" onPress={() => app.run(() => app.setVisualPreferences({ quality }))} style={{ flex: 1, paddingVertical: 12, borderRadius: 12, backgroundColor: app.visuals.quality === quality ? app.tint : p.surface, alignItems: 'center', gap: 5 }}><Text style={{ color: app.visuals.quality === quality ? app.accent : p.text, fontSize: 12 }}>{quality}</Text><Text style={{ color: p.muted, fontSize: 10 }}>{[15,30,60][index]} FPS</Text></Pressable>)}</View>
    <View style={[ui.row, { gap: 8 }]}>{([15,30,45,60] as const).map(fps => <Pressable key={fps} accessibilityRole="button" onPress={() => app.run(() => app.setVisualPreferences({ fps }))} style={{ flex: 1, paddingVertical: 10, alignItems: 'center', borderWidth: 1, borderColor: app.visuals.fps === fps ? app.accent : p.border, borderRadius: 11 }}><Text style={{ color: p.muted, fontSize: 11 }}>{fps} FPS</Text></Pressable>)}</View>
    <Text style={ui.muted}>Интенсивность · {Math.round(app.visuals.intensity * 100)}%</Text><Slider accessibilityLabel="Интенсивность визуализации" value={app.visuals.intensity} minimumValue={0} maximumValue={2} step={.1} onSlidingComplete={intensity => app.run(() => app.setVisualPreferences({ intensity }))} minimumTrackTintColor={app.accent} maximumTrackTintColor={p.border} thumbTintColor={app.accent} style={{ height: 34 }} />
    <Text style={ui.muted}>Скорость без музыки · {Math.round(app.visuals.idleSpeed * 100)}%</Text>
    <Slider accessibilityLabel="Скорость без музыки" value={app.visuals.idleSpeed} minimumValue={0} maximumValue={2} step={.05} onSlidingComplete={idleSpeed => app.run(() => app.setVisualPreferences({ idleSpeed }))} minimumTrackTintColor={app.accent} maximumTrackTintColor={p.border} thumbTintColor={app.accent} style={{ height: 34 }} />
    <Text style={ui.muted}>Скорость при воспроизведении · {Math.round(app.visuals.playingSpeed * 100)}%</Text>
    <Slider accessibilityLabel="Скорость при воспроизведении" value={app.visuals.playingSpeed} minimumValue={0} maximumValue={2} step={.05} onSlidingComplete={playingSpeed => app.run(() => app.setVisualPreferences({ playingSpeed }))} minimumTrackTintColor={app.accent} maximumTrackTintColor={p.border} thumbTintColor={app.accent} style={{ height: 34 }} />
    <Pressable accessibilityRole="button" onPress={() => app.run(() => app.setVisualPreferences({ idleSpeed: 1, playingSpeed: .75 }))}><Text style={{ color: app.accent, paddingVertical: 8 }}>Сбросить скорость</Text></Pressable>
    <Text style={[ui.muted, { fontSize: 11 }]}>{audioTelemetry.supported ? 'Спектр: PCM → Hann 1024 → FFT → 32 полосы. Микрофон не используется.' : 'Нативный анализатор требует собственной сборки Auralis. В Expo Go аудиореакция недоступна.'} При паузе действует скорость «Без музыки». 100% — прежний темп в соответствующем режиме; 0% останавливает движение. В фоне отрисовка и анализ выключаются. Системное уменьшение движения учитывается.</Text>
  </View>;
}

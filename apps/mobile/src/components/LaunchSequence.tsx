import React, { useCallback, useEffect, useState } from 'react';
import { AccessibilityInfo, Animated, AppState, Easing, Pressable, StyleSheet, Text, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import * as SplashScreen from 'expo-splash-screen';

// Native compositor animation: opening the app never requires an OpenGL context.
export function LaunchSequence({ accent, reduced, ready, onFinished }: { accent: string; reduced: boolean; ready: boolean; onFinished: () => void }) {
  const [progress] = useState(() => new Animated.Value(0));
  const [systemReduced, setSystemReduced] = useState<boolean | null>(null);
  const [complete, setComplete] = useState(false);
  const finish = useCallback(() => setComplete(true), []);
  useEffect(() => { let alive = true; void AccessibilityInfo.isReduceMotionEnabled().then(value => { if (alive) setSystemReduced(value); }).catch(() => { if (alive) setSystemReduced(true); }); return () => { alive = false; }; }, []);
  useEffect(() => {
    if (systemReduced === null) return;
    const animation = Animated.timing(progress, { toValue: 1, duration: reduced || systemReduced ? 0 : 2800, easing: Easing.inOut(Easing.cubic), useNativeDriver: true });
    animation.start(({ finished }) => { if (finished) finish(); });
    const state = AppState.addEventListener('change', value => { if (value !== 'active') { animation.stop(); finish(); } });
    return () => { animation.stop(); state.remove(); };
  }, [progress, reduced, systemReduced, finish]);
  useEffect(() => { if (complete && ready) onFinished(); }, [complete, ready, onFinished]);
  const fade = progress.interpolate({ inputRange: [0, .15, .82, 1], outputRange: [0, 1, 1, 0] });
  const scale = progress.interpolate({ inputRange: [0, 1], outputRange: [.78, 1.14] });
  return <Pressable accessibilityRole="button" accessibilityLabel="Пропустить заставку" onPress={finish} onLayout={() => { void SplashScreen.hideAsync().catch(() => {}); }} style={styles.screen}>
    <Animated.View pointerEvents="none" style={[styles.scene, { opacity: fade, transform: [{ scale }] }]}>
      {Array.from({ length: 56 }, (_, i) => <View key={i} style={{ position: 'absolute', left: `${(i * 73.37) % 100}%`, top: `${(i * 37.71) % 100}%`, width: i % 9 ? 1 : 2, height: i % 9 ? 1 : 2, borderRadius: 2, backgroundColor: accent, opacity: .16 + (i % 5) * .1 }} />)}
      <View style={[styles.halo, { backgroundColor: accent + '02' }]} />
      <View style={[styles.halo, { width: 350, height: 350, borderRadius: 175, top: '20%', backgroundColor: accent + '03' }]} />
      <View style={[styles.eclipse, { borderColor: accent, shadowColor: accent }]}><View style={styles.shadow} /></View>
      <LinearGradient colors={['#05060900', accent + 'AA', '#FFF5DF', accent + 'AA', '#05060900']} start={{ x: 0, y: .5 }} end={{ x: 1, y: .5 }} style={styles.horizon} />
      <View style={styles.brand}><Text style={styles.overline}>YOUR SOUND. YOUR UNIVERSE.</Text><Text style={styles.title}>AURALIS</Text><View style={[styles.rule, { backgroundColor: accent }]} /><Text style={styles.caption}>НАСТРОЙТЕСЬ НА СВОЮ ОРБИТУ</Text></View>
    </Animated.View>
    <Text style={styles.skip}>{complete && !ready ? 'Подготовка библиотеки…' : 'Коснитесь, чтобы продолжить'}</Text>
  </Pressable>;
}
const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#050609', overflow: 'hidden', alignItems: 'center', justifyContent: 'center' },
  scene: { width: '100%', height: '100%', alignItems: 'center', justifyContent: 'center' },
  halo: { position: 'absolute', width: 420, height: 420, borderRadius: 210, top: '15%' },
  eclipse: { position: 'absolute', width: 226, height: 226, borderRadius: 113, borderWidth: 2, top: '27%', backgroundColor: '#050609', shadowOpacity: .9, shadowRadius: 25, shadowOffset: { width: 0, height: -2 } },
  shadow: { position: 'absolute', left: -3, top: 5, width: 230, height: 230, borderRadius: 115, backgroundColor: '#050609' },
  horizon: { position: 'absolute', top: '42%', height: 2, width: '100%' },
  brand: { alignItems: 'center', position: 'absolute', top: '59%', gap: 20 },
  overline: { fontSize: 8, color: '#969AA4', letterSpacing: 3 },
  title: { fontSize: 43, color: '#F6F3EC', fontWeight: '300', letterSpacing: 9 },
  rule: { width: 32, height: 1 },
  caption: { fontSize: 8, color: '#969AA4', letterSpacing: 2.3 },
  skip: { position: 'absolute', bottom: 40, color: '#737986', fontSize: 10 },
});

import React, { useCallback, useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { Stack, type ErrorBoundaryProps } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as SplashScreen from 'expo-splash-screen';
import { AuralisProvider, useAuralis } from '../core/AuralisProvider';
import { Login } from '../components/Login';
import { palette } from '../core/theme';
import { LaunchSequence } from '../components/LaunchSequence';

void SplashScreen.preventAutoHideAsync().catch(() => {});

export function ErrorBoundary({ error, retry }: ErrorBoundaryProps) {
  return <View onLayout={() => { void SplashScreen.hideAsync().catch(() => {}); }} style={{ flex: 1, backgroundColor: '#08090B', padding: 30, justifyContent: 'center', gap: 20 }}><Text style={{ color: '#F8D34A', fontSize: 25 }}>Не удалось открыть Auralis</Text><Text selectable style={{ color: '#E9EBEF' }}>{error.message}</Text><Text style={{ color: '#969AA4' }}>Библиотека и настройки сохранены. Этот текст поможет определить причину ошибки.</Text><Pressable accessibilityRole="button" onPress={() => void retry()}><Text style={{ color: '#F8D34A', paddingVertical: 18 }}>Повторить запуск</Text></Pressable></View>;
}

function Routes() {
  const app = useAuralis();
  const [intro, setIntro] = useState(true);
  const finishIntro = useCallback(() => setIntro(false), []);
  if (intro) return <LaunchSequence accent={app.accent} reduced={app.experience.reducedMotion || !app.experience.animationsEnabled} ready={app.hydrated} onFinished={finishIntro} />;
  if (!app.hydrated) return <View style={{ flex: 1, backgroundColor: palette.background, justifyContent: 'center' }}><ActivityIndicator color={app.accent} /></View>;
  if (!app.session) return <Login />;
  return <Stack screenOptions={{ headerShown: false, animation: app.experience.animationsEnabled && !app.experience.reducedMotion ? 'default' : 'none', contentStyle: { backgroundColor: palette.background } }}><Stack.Screen name="(tabs)" /><Stack.Screen name="player" options={{ presentation: 'modal', animation: app.experience.animationsEnabled && !app.experience.reducedMotion ? 'slide_from_bottom' : 'none' }} /><Stack.Screen name="visualizer" options={{ presentation: 'modal' }} /></Stack>;
}
export default function Layout() { return <AuralisProvider><StatusBar style="light" /><Routes /></AuralisProvider>; }

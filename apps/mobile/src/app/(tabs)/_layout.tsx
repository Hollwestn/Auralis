import React from 'react';
import { View } from 'react-native';
import { Tabs } from 'expo-router';
import { BottomTabBar } from 'expo-router/tabs';
import { useAuralis } from '../../core/AuralisProvider';
import { Icon, MiniPlayer } from '../../components/ui';
import { palette as p } from '../../core/theme';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

export default function Layout() {
  const app = useAuralis();
  const insets = useSafeAreaInsets();
  return <Tabs tabBar={props => <View style={{ backgroundColor: p.background }}><MiniPlayer /><BottomTabBar {...props} /></View>} screenOptions={{ headerShown: false, tabBarActiveTintColor: app.accent, tabBarInactiveTintColor: p.muted, tabBarStyle: { backgroundColor: p.background, borderTopColor: p.raised, height: 64 + Math.max(insets.bottom, 14), paddingTop: 9, paddingBottom: Math.max(insets.bottom, 14) }, tabBarLabelStyle: { fontSize: 10, fontWeight: '600' } }}>
    <Tabs.Screen name="index" options={{ title: 'Главная', tabBarIcon: ({ color }) => <Icon name="home-outline" color={typeof color === 'string' ? color : p.muted} /> }} />
    <Tabs.Screen name="search" options={{ title: 'Найти', tabBarIcon: ({ color }) => <Icon name="search-outline" color={typeof color === 'string' ? color : p.muted} /> }} />
    <Tabs.Screen name="library" options={{ title: 'Моя музыка', tabBarIcon: ({ color }) => <Icon name="library-outline" color={typeof color === 'string' ? color : p.muted} /> }} />
    <Tabs.Screen name="settings" options={{ title: 'Настройки', tabBarIcon: ({ color }) => <Icon name="options-outline" color={typeof color === 'string' ? color : p.muted} /> }} />
  </Tabs>;
}

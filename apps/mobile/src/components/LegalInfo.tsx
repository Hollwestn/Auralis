import React, { useState } from 'react';
import { Linking, Modal, Pressable, ScrollView, Text, View } from 'react-native';
import Constants from 'expo-constants';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Button, ui } from './ui';
import { palette as p } from '../core/theme';
import { privacySections, supportEmail } from '../core/legal';

/** Available before login as well as from settings; policy text works offline. */
export function LegalInfo() {
  const [visible, setVisible] = useState(false);
  const legal = Constants.expoConfig?.extra?.legal as { supportEmail?: string; privacyUrl?: string; deletionUrl?: string } | undefined;
  const email = legal?.supportEmail || supportEmail;
  const open = (url: string) => { void Linking.openURL(url).catch(() => {}); };
  return <><Pressable accessibilityRole="button" onPress={() => setVisible(true)} style={{ minHeight: 48, justifyContent: 'center' }}><Text style={[ui.muted, { textAlign: 'center' }]}>Конфиденциальность и поддержка</Text></Pressable><Modal visible={visible} animationType="slide" onRequestClose={() => setVisible(false)}><SafeAreaView style={{ flex: 1, backgroundColor: p.background }}><ScrollView contentContainerStyle={{ padding: 24, gap: 20 }}><Text style={ui.title}>Ваши данные</Text>{privacySections.map(section => <View key={section.title} style={{ gap: 8 }}><Text style={ui.subtitle}>{section.title}</Text><Text selectable style={ui.text}>{section.text}</Text></View>)}{!!legal?.privacyUrl && <Button secondary label="Политика на сайте" onPress={() => open(legal.privacyUrl!)} />}{!!legal?.deletionUrl && <Button secondary label="Как удалить аккаунт" onPress={() => open(legal.deletionUrl!)} />}<Button secondary label={`Написать: ${email}`} onPress={() => open(`mailto:${email}`)} /><Button label="Закрыть" onPress={() => setVisible(false)} /></ScrollView></SafeAreaView></Modal></>;
}

import React, { useState } from 'react';
import { ActivityIndicator, Image, KeyboardAvoidingView, Platform, ScrollView, Text, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { AuralisApi, normalizeServer } from '../core/api';
import { useAuralis } from '../core/AuralisProvider';
import { Button, Field, Screen, ui } from './ui';
import { palette as p } from '../core/theme';

export function Login() {
  const app = useAuralis();
  const [kind, setKind] = useState<'login' | 'register'>('login');
  const [server, setServer] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submit = async () => {
    setBusy(true); setError('');
    try {
      const api = new AuralisApi(normalizeServer(server));
      const session = await api.authenticate(kind, email, password, displayName);
      await app.activate(session);
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'Не удалось подключиться.'); }
    finally { setBusy(false); }
  };
  return <Screen><KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}><ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ padding: 26, paddingBottom: 42, gap: 24 }}>
    <LinearGradient colors={[app.tint, p.background]} style={{ marginHorizontal: -26, marginTop: -26, padding: 26, paddingTop: 50, gap: 21 }}>
      <View style={ui.between}><View style={[ui.row, { gap: 10 }]}><Image source={require("../../assets/auralis-icon.png")} style={{width: 36, height: 36}} /><Text style={{ color: p.text, fontSize: 21, letterSpacing: -0.8, fontWeight: '700' }}>auralis</Text></View><Text style={ui.eyebrow}>YOUR SOUND.</Text></View>
      <Text style={[ui.title, { fontSize: 43, lineHeight: 49, marginTop: 25, maxWidth: 300 }]}>Музыка. В вашем ритме.</Text><Text style={[ui.muted, { maxWidth: 300 }]}>Музыка на телефоне или ваша серверная коллекция — выбираете вы.</Text>
    </LinearGradient>
    <View style={[ui.card, { gap: 12 }]}><Text style={ui.subtitle}>Музыка с собой</Text><Text style={ui.muted}>Слушайте файлы из памяти телефона. Сервер и регистрация не нужны.</Text><Button icon="phone-portrait-outline" label="Продолжить без аккаунта" disabled={busy} onPress={() => app.run(app.enterLocal)} /></View>
    <View style={{ gap: 17 }}><Text style={ui.subtitle}>{kind === 'login' ? 'С возвращением' : 'Создайте аккаунт'}</Text>
      <Field label="Адрес вашего сервера" value={server} onChangeText={setServer} placeholder="https://music.example.com" autoCapitalize="none" autoCorrect={false} keyboardType="url" textContentType="URL" />
      {kind === 'register' && <Field label="Ваше имя" value={displayName} onChangeText={setDisplayName} autoComplete="name" />}
      <Field label="Электронная почта" value={email} onChangeText={setEmail} autoCapitalize="none" autoCorrect={false} keyboardType="email-address" autoComplete="email" />
      <Field label="Пароль" value={password} onChangeText={setPassword} secureTextEntry autoComplete={kind === 'login' ? 'current-password' : 'new-password'} onSubmitEditing={() => void submit()} />
      {!!error && <Text accessibilityRole="alert" style={{ color: p.danger, lineHeight: 21 }}>{error}</Text>}
      {busy ? <ActivityIndicator color={app.accent} style={{ height: 50 }} /> : <Button label={kind === 'login' ? 'Войти' : 'Создать аккаунт'} disabled={!server || !email || !password || (kind === 'register' && !displayName.trim())} onPress={() => void submit()} icon="arrow-forward" />}
      <Button secondary label={kind === 'login' ? 'Создать аккаунт на сервере' : 'Уже есть аккаунт? Войти'} onPress={() => { setKind(kind === 'login' ? 'register' : 'login'); setError(''); }} />
      <Text style={[ui.muted, { fontSize: 11, textAlign: 'center' }]}>Вход нужен только для серверной коллекции и синхронизации между устройствами. Общего сервера Auralis нет: здесь указывается адрес вашего сервера.</Text>
    </View>
  </ScrollView></KeyboardAvoidingView></Screen>;
}

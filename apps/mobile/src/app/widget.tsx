import { useEffect, useRef } from 'react';
import { ActivityIndicator } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useAuralis } from '../core/AuralisProvider';
import { Screen } from '../components/ui';

export default function WidgetCommand() {
  const app = useAuralis(), { command } = useLocalSearchParams<{ command?: string }>(), done = useRef(false);
  useEffect(() => {
    if (!app.hydrated || done.current) return;
    done.current = true;
    app.run(async () => {
      try { await app.widgetCommand(command || 'open'); }
      finally { router.replace('/player'); }
    });
  }, [app, command]);
  return <Screen><ActivityIndicator color={app.accent} /></Screen>;
}

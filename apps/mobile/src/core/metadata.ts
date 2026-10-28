import { requireNativeModule } from 'expo';
import { Platform } from 'react-native';
import type { Track } from './types';

export async function readLocalMetadata(uri: string, coverUri: string): Promise<Partial<Pick<Track, 'title' | 'artist' | 'album' | 'genre' | 'durationSeconds' | 'coverUrl'>>> {
  if (Platform.OS === 'web') return {};
  const module = requireNativeModule('ExpoAudio');
  if (typeof module.readAuralisMetadata !== 'function') throw new Error('Для обложек установите новую сборку приложения.');
  return module.readAuralisMetadata(uri, coverUri);
}

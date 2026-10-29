import { HStack, Link, Text, VStack } from '@expo/ui/swift-ui';
import { font, foregroundStyle, padding, widgetURL } from '@expo/ui/swift-ui/modifiers';
import { createWidget } from 'expo-widgets';
import { initialNowPlaying, type NowPlayingProps } from './types';

const NowPlaying = (props: NowPlayingProps) => {
  'widget';
  return <VStack alignment="leading" spacing={10} modifiers={[padding({ all: 12 }), widgetURL('auralis://widget?command=open')]}>
    <Text modifiers={[font({ size: 12, weight: 'bold' }), foregroundStyle(props.accent)]}>AURALIS</Text>
    <Text modifiers={[font({ size: 17, weight: 'semibold' }), foregroundStyle('#F6F5F1')]}>{props.title}</Text>
    <Text modifiers={[font({ size: 12 }), foregroundStyle('#9297A1')]}>{props.artist}</Text>
    <HStack spacing={18}>
      <Link label="Назад" destination="auralis://widget?command=previous" modifiers={[foregroundStyle(props.accent)]} />
      <Link label={props.playing ? 'Пауза' : 'Играть'} destination="auralis://widget?command=toggle" modifiers={[foregroundStyle(props.accent)]} />
      <Link label="Далее" destination="auralis://widget?command=next" modifiers={[foregroundStyle(props.accent)]} />
    </HStack>
  </VStack>;
};
export default NowPlaying;
const widget = createWidget<NowPlayingProps>('AuralisNowPlaying', NowPlaying, initialNowPlaying);
export function updateNowPlaying(props: NowPlayingProps) { widget.updateSnapshot(props); }

import { Button, Column, Row, Text } from '@expo/ui/jetpack-compose';
import { background, fillMaxWidth, paddingAll } from '@expo/ui/jetpack-compose/modifiers';
import { createWidget } from 'expo-widgets';
import { initialNowPlaying, type NowPlayingProps } from './types';

const NowPlaying = (props: NowPlayingProps) => {
  'widget';
  return <Column modifiers={[fillMaxWidth(), background('#111318'), paddingAll(16)]} verticalArrangement={{ spacedBy: 8 }}>
    <Text color={props.accent} style={{ fontSize: 12 }}>AURALIS</Text>
    <Text color="#F6F5F1" style={{ fontSize: 17 }} maxLines={1}>{props.title}</Text>
    <Text color="#9297A1" style={{ fontSize: 13 }} maxLines={1}>{props.artist}</Text>
    <Row horizontalArrangement={{ spacedBy: 8 }}>
      <Button {...{ target: 'auralis:previous' }} colors={{ containerColor: '#292D35', contentColor: '#F6F5F1' }}><Text>Назад</Text></Button>
      <Button {...{ target: 'auralis:toggle' }} colors={{ containerColor: props.accent, contentColor: '#08090B' }}><Text>{props.playing ? 'Пауза' : 'Играть'}</Text></Button>
      <Button {...{ target: 'auralis:next' }} colors={{ containerColor: '#292D35', contentColor: '#F6F5F1' }}><Text>Далее</Text></Button>
    </Row>
  </Column>;
};
export default NowPlaying;
const widget = createWidget<NowPlayingProps>('AuralisNowPlaying', NowPlaying, initialNowPlaying);
export function updateNowPlaying(props: NowPlayingProps) { widget.updateSnapshot(props); }

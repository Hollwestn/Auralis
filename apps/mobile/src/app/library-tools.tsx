import React, { useState } from 'react';
import { Alert, ScrollView, Switch, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useAuralis } from '../core/AuralisProvider';
import { Button, IconButton, Notice, Screen, ui } from '../components/ui';
import { palette as p } from '../core/theme';

export default function LibraryTools() {
  const app=useAuralis(), [audio,setAudio]=useState(false);
  const preview=app.backupPreview;
  const confirm=(replace:boolean)=>Alert.alert(replace?'Заменить локальную библиотеку?':'Объединить с текущей библиотекой?',replace?'Плейлисты, история и настройки будут заменены содержимым выбранной копии. Сначала сохраните текущую резервную копию.':'Совпадающие треки сохранят текущие метаданные. Отличающиеся плейлисты будут добавлены отдельно.',[{text:'Отмена',style:'cancel'},{text:replace?'Заменить':'Объединить',style:replace?'destructive':'default',onPress:()=>app.run(()=>app.restoreBackup(replace))}]);
  return <Screen><Notice/><ScrollView contentContainerStyle={{padding:22,gap:20}}><View style={ui.between}><Text style={ui.title}>Резервная копия</Text><IconButton name="close" label="Закрыть" onPress={()=>router.back()}/></View><Text style={ui.muted}>Один файл для компьютера и телефона. Аккаунты, пароли и токены не включаются.</Text>
    <View style={ui.card}><Text style={ui.subtitle}>Сохранить библиотеку</Text><Text style={ui.muted}>Избранное, плейлисты, история, обложки и настройки. Сохраните полученный файл вне приложения, чтобы он пережил переустановку.</Text><View style={ui.between}><Text style={ui.text}>Включить аудиофайлы</Text><Switch accessibilityLabel="Включить музыку в резервную копию" value={audio} onValueChange={setAudio} trackColor={{true:app.accent,false:p.border}}/></View><Text style={ui.muted}>{audio?'Телефон создаёт архив до 3 ГБ. Для большей коллекции используйте ПК.':'Без аудио музыку после переустановки нужно добавить повторно; метаданные и связи сохранятся.'}</Text><Button label={app.libraryWorking?'Подождите…':'Создать и сохранить'} disabled={app.libraryWorking||!app.localMode} onPress={()=>app.run(()=>app.exportBackup(audio))}/></View>
    <View style={ui.card}><Text style={ui.subtitle}>Восстановить</Text><Button secondary label="Выбрать файл .auralis" disabled={app.libraryWorking||!app.localMode} onPress={()=>app.run(app.chooseBackup)}/>{preview&&<><Text style={ui.text}>{preview.tracks} треков · {preview.playlists} плейлистов · {preview.favorites} любимых · {preview.history} событий истории</Text><Text style={ui.muted}>Включено аудио: {(preview.audioBytes/1024/1024).toFixed(1)} МБ. Без аудио: {preview.missingAudio}. Совпадений с вашей библиотекой: {preview.conflicts}.</Text><Text style={ui.subtitle}>В копии</Text>{preview.manifest.tracks.slice(0,5).map(t=><Text key={t.id} style={ui.muted}>{t.artist} — {t.title}</Text>)}<Button label="Объединить" disabled={app.libraryWorking} onPress={()=>confirm(false)}/><Button secondary label="Заменить после подтверждения" disabled={app.libraryWorking} onPress={()=>confirm(true)}/><Button secondary label="Отмена" disabled={app.libraryWorking} onPress={()=>app.setBackupPreview(null)}/></>}</View>
    {!app.localMode&&<Text style={ui.muted}>Эти операции предназначены для локальной библиотеки. Резервную копию серверной базы создаёт администратор сервера.</Text>}
  </ScrollView></Screen>;
}

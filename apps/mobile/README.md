# Auralis Mobile

Нативный клиент Android/iOS: Expo SDK 58, React Native 0.88, React 19.3, Expo Router и TypeScript. Режим без регистрации с импортом музыки из памяти телефона; также серверный каталог, поиск, избранное, плейлисты, история, рекомендации, скачивание и продолжение очереди используют общий API `/api/v1`. В поставке нет демонстрационного каталога или встроенных музыкальных файлов.

## Запуск и сборка

Из этой папки: `npm ci`, `npm run lint`, `npm run typecheck`, `npm test`. Для разработки — `npm start`; для собственного Android APK из корня репозитория — `./scripts/build-android.ps1 -AllowLocalHttp`. Этот корневой скрипт по умолчанию создаёт автономную тестовую сборку с Android debug-подписью. Для распространения нужны собственные ключи и HTTPS.

Подробные русские инструкции: [Android](../../docs/ANDROID_INSTALLATION.md), [iOS](../../docs/IOS_INSTALLATION.md), [фактические проверки и ограничения](../../docs/MOBILE_TEST_REPORT.md). Android/Xcode-проекты генерируются командой Expo prebuild; build-кэши и ключи не публикуются.

## Воспроизведение и настоящий спектр

`src/core/AuralisProvider.tsx` управляет нативным `AudioPlaylist`; очередь продолжает переключаться в нативном слое. Токен передаётся заголовком Authorization, локальные файлы изолированы по серверу и пользователю, токен хранится в SecureStore. Браузерный `storage.web.ts` предназначен для проверки экранов: хранение временное, скачивание честно сообщает о необходимости нативного приложения.

`scripts/patch-expo-audio.cjs` автоматически выполняется при `npm ci`, проверяет точную версию Expo Audio **58.0.9**, затем идемпотентно применяет собственное расширение из `native/audio`. Android использует read-only Media3 PCM-процессор, iOS — MTAudioProcessingTap AVQueuePlayer. Расширение вычисляет настоящий FFT1024, 32 полосы, RMS и пик без микрофона. Оно также исправляет передачу bearer-заголовков потока в Android AudioPlaylist. При смене версии Expo Audio скрипт намеренно останавливается до пересмотра исходников. Expo Go не содержит этого расширения.

В package.json для **обеих** платформ задано `expo.autolinking.<platform>.buildFromSource: ["expo-audio"]`. Без этого Expo58 может связать готовый AAR/XCFramework, проигнорировав изменённые исходники. Postinstall проверяет настройку, а `scripts/verify-apk.ps1` проверяет настоящий DEX на класс PCM-процессора и экспортируемую строку метода `setSpectrumEnabled`. Механизм описан в [официальной документации Expo](https://docs.expo.dev/guides/prebuilt-expo-modules/).

`src/visuals/shaders.ts` содержит восемь обязательных режимов; `AudioVisual.tsx` управляет Expo GL, реальным масштабом рендера, частотой кадров и освобождением ресурсов. Сигнал из `spectrum.ts` гаснет при паузе/устаревании; случайных значений аудио нет. В 0.6.0 семь прежних сцен заменены новыми; Black Hole медленно движется без музыки. LaunchSequence использует нативный композитор. Плагин withAuralisAndroid сохраняет JNI-метод GLContext.flush() при R8; детали исправления запуска — ../../docs/ANDROID_STARTUP_FIX.md.

## Проверка

Node-тесты проверяют сетевой контракт, границы хранения, идентичности режимов и поведение измеренных спектральных данных. Нативные Kotlin-тесты PCM расположены в `native/audio/AuralisPcmTapTest.kt`; postinstall копирует их в Expo Audio, запуск: `android/gradlew.bat :expo-audio:testDebugUnitTest` с настроенным SDK/JDK. В текущем выпуске два PCM-теста и полная Android release-компиляция прошли; фактические SHA и ограничения записаны в MOBILE_TEST_REPORT.md. Собранный автономный APK находится в release/android в корне проекта.

`scripts/verify-ui.mjs` запускает браузерную проверку настоящих React Native Web экранов с временным аккаунтом на указанном `AURALIS_QA_SERVER`; аккаунт удаляется в finally. `scripts/verify-shaders.mjs` отдельно проверяет GLSL в WebGL. Скрипты принимают путь к установленному Playwright и Chromium/Edge. Эти проверки не подменяют запуск APK на телефоне.

По состоянию на 18.10.2026 npm audit сообщает **19 high** и при обычном запуске, и с `--omit=dev`. Подробности, пути зависимостей и подтверждённая граница JS-бандла указаны в протоколе; нулевой аудит или готовность к публичной публикации не заявляются.


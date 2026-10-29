# Auralis: Linux и публикация в магазинах

Подготовка исходников 0.9.0, 29 октября 2026. Контакт: **auralishelp@mail.ru**.
Платный музыкальный сервер не нужен: на телефоне можно выбрать «Продолжить без аккаунта» и импортировать свои файлы.

## Linux: Ubuntu 24.04, x86_64

1. Установите JDK **25** (например, Temurin 25 из официального пакета Adoptium) и Maven. Проверьте `java -version`, `mvn -version`, `jpackage --version`: все должны использовать JDK 25. Затем:

   ```bash
   sudo apt update
   sudo apt install maven ffmpeg fakeroot libgtk-3-0t64 libasound2t64 libgl1 libxrandr2 libxinerama1 libxcursor1 libxi6 libxxf86vm1
   git clone https://github.com/Hollwestn/Auralis.git
   cd Auralis
   bash scripts/build-linux.sh deb
   sudo apt install ./release/linux/auralis_0.9.0_amd64.deb
   ```

2. Запустите **Auralis** из меню приложений. Имя DEB проверьте через `ls release/linux/*.deb`, если ваша версия jpackage формирует другое имя. Java включена в пакет; FFmpeg устанавливается как системная зависимость. GPU-графике нужен OpenGL 3.3 и рабочий драйвер. Если драйвер не поддерживает её, отключите графику звука в настройках.
3. Кнопкой «Добавить музыку» выберите файлы. Данные хранятся в `${XDG_DATA_HOME:-$HOME/.local/share}/auralis`. Обновление пакета их не удаляет. Для запуска из исходников: `bash scripts/run-desktop.sh`.
4. Переносимый архив: `bash scripts/build-linux.sh app-image`, затем распакуйте `release/linux/Auralis-0.9.0-linux-x86_64.tar.gz` и запустите `Auralis/bin/Auralis`. Системные библиотеки и FFmpeg всё равно нужны. `rpm` предусмотрен как дополнительный формат, но Fedora/RPM пока не проверены.

Сборка DEB также описана в GitHub Actions → **Linux packages** → **Run workflow**. Загрузите результат из раздела Artifacts успешного запуска. Linux-пакеты собираются на Linux, а не средствами Windows. На этом ПК Linux не установлен; результаты фактической проверки указаны в [отчёте](verification/UPDATE_090.md).

## Перед первой публикацией телефона

1. Зарегистрируйте аккаунты разработчика нужных магазинов и Expo. Проверьте выбранный идентификатор **io.auralis.mobile**: после первой публикации его нельзя произвольно менять. Для другого свободного идентификатора задайте `AURALIS_BUNDLE_IDENTIFIER` одинаково при всех сборках. Название и права на бренд проверяет владелец.
2. В репозитории GitHub откройте **Settings → Pages → Deploy from a branch → main → /docs → Save**. После успешной публикации проверьте без входа в аккаунт эти страницы:
   - `https://hollwestn.github.io/Auralis/privacy.html`
   - `https://hollwestn.github.io/Auralis/delete-account.html`
   - `https://hollwestn.github.io/Auralis/support.html`

   Исходники страниц уже включены в проект. Пока Pages не включён и ссылки не открываются, они не считаются опубликованной политикой. Не загружайте сборку с неработающими ссылками.
3. Откройте терминал в `apps/mobile`, выполните:

   ```powershell
   npm ci
   npx eas-cli@latest login
   npx eas-cli@latest init
   $env:AURALIS_PRIVACY_URL = 'https://hollwestn.github.io/Auralis/privacy.html'
   $env:AURALIS_DELETION_URL = 'https://hollwestn.github.io/Auralis/delete-account.html'
   npx eas-cli@latest env:create --environment production --visibility plaintext --name AURALIS_PRIVACY_URL --value $env:AURALIS_PRIVACY_URL
   npx eas-cli@latest env:create --environment production --visibility plaintext --name AURALIS_DELETION_URL --value $env:AURALIS_DELETION_URL
   npm run typecheck
   npm run lint
   npm test
   ```

   `eas init` привяжет проект к вашему Expo-аккаунту. Если CLI не может изменить динамический `app.config.ts`, скопируйте выданный UUID в `extra.eas.projectId`, сохранив существующий `extra.legal`, например `extra: { eas: { projectId: 'UUID-из-Expo' }, legal: { ... } }`. Не копируйте этот пример буквально: UUID должен быть вашим. Сохраните изменение в проекте. Для существующих переменных используйте `eas env:update`. Если адрес Pages отличается, укажите фактический адрес. Профили магазинов запрещают тестовую подпись и обычный HTTP. Локальная политика внутри приложения работает и без интернета.

## Google Play

1. В `apps/mobile` выполните `npx eas-cli@latest build --platform android --profile google-play`. Настройте постоянный ключ через предложенный менеджер credentials. Сохраните защищённую резервную копию ключа и паролей; не отправляйте их в GitHub.
2. Загрузите полученный **AAB** в Play Console → приложение Auralis → **Internal testing**. Текущие настройки: version 0.9.0, code 14, target API 36. При следующей загрузке увеличьте versionCode в `app.config.ts`.
3. Заполните карточку по [материалам](STORE_MATERIALS.md), возрастной рейтинг, Data safety и удаление аккаунта. Проверьте на телефоне импорт, фоновое воспроизведение, экран блокировки, графику, паузу и перезапуск. Для новых личных аккаунтов Google может требовать закрытый тест: 12 участников непрерывно 14 дней перед запросом доступа к production.
4. Проверьте поддержку страниц памяти 16 КБ через Play Console / App Bundle Explorer и тестовое устройство или эмулятор с 16 КБ. Проверка zipalign у APK сама по себе не доказывает совместимость всех ELF-библиотек в AAB.

## RuStore

1. `npx eas-cli@latest build --platform android --profile rustore` создаёт подписанный **APK**. Выберите тот же сохранённый ключ, который планируете использовать для последующих обновлений RuStore.
2. В консоли разработчика создайте Auralis, загрузите APK, описание, реальные скриншоты и ссылки на политику и поддержку. Заполните все запрошенные анкеты, проверьте сборку и отправьте на модерацию. RuStore поддерживает и AAB, но для первой публикации APK проще.
3. Для обновлений между Google Play и RuStore должны совпадать идентификатор и **сертификат подписи установленного приложения**. Ключ загрузки Google Play и ключ, которым Google подписывает доставляемый APK, могут отличаться. Решите схему Play App Signing до первой публикации; одинаковый upload key не гарантирует обновление между магазинами.

Локальная альтернатива EAS: в копии проекта с коротким латинским путём, с Android SDK и JDK 17/21, задайте четыре переменные `AURALIS_UPLOAD_STORE_FILE`, `AURALIS_UPLOAD_STORE_PASSWORD`, `AURALIS_UPLOAD_KEY_ALIAS`, `AURALIS_UPLOAD_KEY_PASSWORD`, а также реальные `AURALIS_PRIVACY_URL`, `AURALIS_DELETION_URL`. Из корня:

```powershell
./scripts/build-android.ps1 -Production -Store -Format aab -Architecture all
./scripts/build-android.ps1 -Production -Store -Format apk -Architecture all
```

Выход: `release/android/Auralis-GooglePlay.aab` и `Auralis-RuStore.apk`. Скрипт не создаёт production-ключ автоматически. Для его создания используйте интерактивный `keytool -genkeypair -v -keystore C:\Keys\auralis-upload.jks -alias auralis -keyalg RSA -keysize 2048 -validity 10000`. Пароли вводите в запросах keytool; храните ключ вне репозитория. Без `-Production` скрипт выпускает только тестовый APK, который не предназначен для магазинов.

## App Store / iPhone

1. Нужны Apple Developer Program, приложение в App Store Connect с тем же bundle ID и сертификаты/профиль Apple. Из Windows можно заказать сборку на macOS в EAS: `npx eas-cli@latest build --platform ios --profile app-store`. EAS запросит вашу авторизацию Apple и подготовит подпись. Стоимость/квоты облачных сборок зависят от аккаунта; музыкальный сервер для этого не нужен.
2. Полученный IPA загрузите через `npx eas-cli@latest submit --platform ios --profile app-store`. Команду запускает владелец после проверки, она отправляет сборку в App Store Connect. Затем добавьте её в TestFlight и проверьте на реальном iPhone. На собственном Mac с Xcode 26.2 / Swift 6.2 или новее можно вместо EAS выполнить `npx expo prebuild --platform ios`, открыть workspace в Xcode, настроить Signing и выбрать Product → Archive.
3. Проверьте импорт через Files, звук в фоне/при блокировке, собственный PCM-анализ, GPU-графику, расход батареи, iPad-разметку и удаление аккаунта на тестовом сервере. Нативное приложение и расширение виджета собраны для ARM64-симулятора на macOS с Xcode 26.2; подробности в [отчёте 0.9.0](verification/UPDATE_090.md). Подписанного IPA ещё нет; компиляция и Hermes export не заменяют запуск на iPhone.
4. Заполните App Privacy по фактическим данным и SDK, добавьте политику, поддержку, реальные скриншоты и [заметки проверяющему](STORE_MATERIALS.md). После TestFlight отправьте версию на App Review. Соответствие правилам и результат модерации заранее не гарантируются.

## Что ещё требуется от владельца

Включить публичные страницы, привязать Expo projectId, настроить production-подписи и аккаунты магазинов, пройти тесты на реальных устройствах и заполнить анкеты. В репозитории нет ваших production-ключей, подписанного IPA или готового магазинного AAB. Старые Preview/Test APK и Windows EXE не являются магазинными сборками 0.9.0.

Источники требований: [jpackage](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html), [EAS](https://docs.expo.dev/build/introduction/), [Google target API](https://support.google.com/googleplay/android-developer/answer/11926878), [тестирование Play](https://support.google.com/googleplay/android-developer/answer/14151465), [16 КБ](https://developer.android.com/guide/practices/page-sizes), [RuStore](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication), [правила App Store](https://developer.apple.com/app-store/review/guidelines/).

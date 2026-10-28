# Что скачать с GitHub

Готовые файлы находятся в [Releases Auralis](https://github.com/Hollwestn/Auralis/releases). Кнопка GitHub **Code → Download ZIP** скачивает исходники; установщики находятся отдельно в выпуске.

## Windows

Скачайте `Auralis-Setup.exe` и запустите установщик. Дополнительные Java и FFmpeg для самого приложения не нужны. MSI — альтернативный вариант. Если обновляете старую 0.1, сначала прочитайте [безопасное обновление](WINDOWS_INSTALLATION.md) и скачайте `Safe-Update.ps1`.

## Android

Скачайте `Auralis-Android-Test.apk`, откройте его на телефоне и установите обновление. Это arm64-сборка для Android7+ с тестовой подписью. Текущая 0.6.2 опубликована как Preview: автоматические проверки и сборка прошли, полный новый локальный сценарий на телефоне пока не подтверждён.

Для режима без сервера выберите «Продолжить без аккаунта» → «Добавить музыку с телефона». [Полная инструкция](MOBILE_OFFLINE_GUIDE.md).

## Сервер

Скачайте и распакуйте `Auralis-Server-Internet.zip`. Внутри есть `auralis-server.jar`, Docker/HTTPS-конфигурация, пример окружения и инструкция. Для VPS следуйте [SERVER_INTERNET_GUIDE.md](SERVER_INTERNET_GUIDE.md).

Для локального запуска на Windows установите Java25 и скачайте исходники проекта. Из серверного архива скопируйте `auralis-server.jar` в папку `release/server` исходников. Скрипт `start-dev.ps1` уже находится там. Откройте PowerShell в корне проекта:

```powershell
.\release\server\start-dev.ps1 -Lan -Port 8080
```

Ожидаемый результат: сервер слушает порт8080. В другом окне выполните `Invoke-RestMethod http://127.0.0.1:8080/api/v1/health` — ожидается `status: ok`. Для телефона в той же Wi-Fi-сети используйте `http://IPv4_КОМПЬЮТЕРА:8080`, не localhost. Для разных сетей настройте VPS и HTTPS.

Выпуск содержит `SHA256SUMS.txt` и `RELEASE_MANIFEST.json`. Проверить отдельный загруженный файл в Windows можно командой `Get-FileHash .\Auralis-Setup.exe -Algorithm SHA256`, сравнив результат со строкой этого файла в списке сумм.

## Linux 0.8.0 и публикация в магазинах

Для Ubuntu 24.04 x86_64: [DEB, контрольные суммы и скриншот](https://github.com/Hollwestn/Auralis/actions/runs/40727004279/artifacts/12834055712). Артефакт GitHub Actions доступен до 4 ноября 2026 и может требовать входа в GitHub; после этого запустите процесс Linux packages заново. [Сборка и установка, Google Play, RuStore и App Store](RELEASE_GUIDE.md). APK/EXE из прежних Releases имеют собственные версии и не заменяют подписанный AAB/IPA для магазина.

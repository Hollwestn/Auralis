# Что скачать с GitHub

Текущая версия — **0.7.0**, опубликована в [Releases](https://github.com/Hollwestn/Auralis/releases/tag/v0.7.0). Code → Download ZIP скачивает исходники, а установщики находятся в Assets выпуска.

## Windows

Скачайте [Auralis-0.7.0.exe](https://github.com/Hollwestn/Auralis/releases/download/v0.7.0/Auralis-0.7.0.exe) и запустите установщик. Java и FFmpeg включены. Для обновления старой 0.1 сначала прочитайте [инструкцию](WINDOWS_INSTALLATION.md).

## Android

Скачайте [Auralis-Android-0.7.0-Preview.apk](https://github.com/Hollwestn/Auralis/releases/download/v0.7.0/Auralis-Android-0.7.0-Preview.apk). ARM64, Android 7+, тестовая подпись, versionCode 12. Для музыки с телефона выберите «Продолжить без аккаунта» → «Добавить музыку с телефона». [Инструкция](MOBILE_OFFLINE_GUIDE.md).

Android блокирует обычное понижение версии поверх versionCode 13/14. Сохраните нужные локальные данные до любых действий с установленной более новой версией.

## Сервер

Скачайте и распакуйте `Auralis-Server-Internet.zip`. Внутри есть `auralis-server.jar`, Docker/HTTPS-конфигурация, пример окружения и инструкция. Для VPS следуйте [SERVER_INTERNET_GUIDE.md](SERVER_INTERNET_GUIDE.md).

Для локального запуска на Windows установите Java25 и скачайте исходники проекта. Из серверного архива скопируйте `auralis-server.jar` в папку `release/server` исходников. Скрипт `start-dev.ps1` уже находится там. Откройте PowerShell в корне проекта:

```powershell
.\release\server\start-dev.ps1 -Lan -Port 8080
```

Ожидаемый результат: сервер слушает порт8080. В другом окне выполните `Invoke-RestMethod http://127.0.0.1:8080/api/v1/health` — ожидается `status: ok`. Для телефона в той же Wi-Fi-сети используйте `http://IPv4_КОМПЬЮТЕРА:8080`, не localhost. Для разных сетей настройте VPS и HTTPS.

Выпуск содержит `SHA256SUMS.txt` и `RELEASE_MANIFEST.json`. Проверить отдельный загруженный файл в Windows можно командой `Get-FileHash .\Auralis-Setup.exe -Algorithm SHA256`, сравнив результат со строкой этого файла в списке сумм.

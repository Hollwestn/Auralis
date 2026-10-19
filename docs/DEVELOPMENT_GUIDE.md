# Разработка

## Зависимости

Windows Desktop: JDK 25, Maven 3.9+, FFmpeg 7/8 с FFprobe. `java`, `mvn`, `ffmpeg`, `ffprobe` должны находиться в PATH. Для EXE — WiX 3.11+; для portable app-image WiX не нужен.

Server: тот же JDK/Maven для исходников; Docker Desktop с Linux containers для Compose/PostgreSQL. Mobile: см. Android/iOS installation guide; Node, JDK 17 для Android, Android SDK, macOS/Xcode для iOS.

## Проверки

```powershell
mvn -B verify "-Dmaven.repo.local=$env:USERPROFILE/.m2/repository"
.\scripts\run-desktop.ps1 -SmokeTest
cd apps/mobile
npm ci
npm run typecheck
npm run lint
```

Если Maven сообщает repository `C:/.m2`, явный `-Dmaven.repo.local` выше исправляет путь окружения. В PowerShell аргументы Maven с точками в `-D...` передавайте в кавычках. Core tests создают синтезированный WAV в временной папке и требуют FFmpeg. Они не открывают личную коллекцию.

JavaFX тесты используют нативную графику/аудиоподсистему Windows; на headless runner их надо выделить в отдельный профиль/графическую job. `--smoke-test` создаёт снимок в папке данных и завершает приложение; для изоляции задайте `-Dauralis.data=<тестовая папка>`.

## Структура

`audio/core` — domain/library/editor/recommendation. `apps/desktop` — JavaFX shell/FXML/CSS, playback, server adapters. `backend/server` — Spring API/services/migrations. `apps/mobile` — Router screens, app state, audio and persistence. `infrastructure` — эксплуатация. Не добавляйте сетевые вызовы/сканирование в JavaFX Application Thread.

Соберите артефакты `scripts/build-desktop.ps1`, `scripts/build-android.ps1`; server JAR — Maven package. Отчёт о новой версии должен перечислять фактически выполненные проверки, не наследовать автоматически результат 0.1.

## Ограничения сборки

В текущем окружении присутствует Oracle JDK25. Перед публичным распространением runtime выберите подходящую лицензию и зафиксируйте vendor. Android SDK/Docker/macOS проверяются независимо; отсутствие любого из них означает «не проверено», а не успешную сборку платформы.

# Сторонние компоненты

Java runtime: JDK выбранного поставщика. В текущем окружении сборка выполняется Oracle JDK 25.0.3; условия его распространения и использования необходимо проверить перед публичной дистрибуцией. Для публичных сборок рекомендуется отдельно выбрать разрешённый к распространению OpenJDK runtime, например Eclipse Temurin, и выполнить сборку с его JAVA_HOME.

JavaFX — OpenJFX (GPLv2 with Classpath Exception). SQLite JDBC — Xerial (Apache 2.0 / BSD SQLite native components). Jackson — Apache 2.0. Spring Boot — Apache 2.0. Expo/React Native и npm dependencies сохраняют свои лицензии в node_modules/package metadata.

FFmpeg/FFprobe: binaries в текущей Windows среде собраны сторонним дистрибьютором; `ffmpeg -version` показывает параметры сборки. При упаковке скрипт копирует локальную `FFMPEG-LICENSE.txt` вместе с ними. Включённый FFmpeg может иметь GPL конфигурацию; перед распространением нужно обеспечить требования конкретной сборки, включая доступ к соответствующим исходникам и сведениям о сборке. Проект не меняет лицензии сторонних программ.

Музыка в поставку не включена. Тестовые WAV синтезируются тестами и не являются пользовательской музыкальной коллекцией. Figma Musium используется как референс композиции; сторонние обложки/логотип не включаются в продукт.

LWJGL 3.4.3 — BSD 3-Clause; GLFW — zlib/libpng license. Windows-поставка содержит lwjgl, lwjgl-glfw, lwjgl-opengl и их официальные natives-windows JAR. OpenGL использует установленный драйвер видеокарты. Общие процедурные GLSL-сцены написаны в проекте; сторонние шейдеры и видеозаписи не упакованы как визуализации.

Bouncy Castle bcpkix/bcprov/bcutil 1.83 — Bouncy Castle Licence (MIT-style), временные TLS-сертификаты локального обмена. ZXing core 3.5.3 — Apache 2.0, QR-коды. fflate 0.8.3 — MIT, потоковые ZIP-копии на телефоне. Expo Widgets, Expo UI, Camera, Sharing и Battery сохраняют лицензии своих пакетов (MIT).

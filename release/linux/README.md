# Linux

Сборка Ubuntu 24.04 x86_64: `bash scripts/build-linux.sh deb`.
Пакеты появляются здесь после успешной сборки на Linux. На Windows они не создаются.
В GitHub Actions предусмотрен процесс **Linux packages**.

Варианты: `deb`, `rpm` (дополнительный, не проверен), `app-image` (каталог с runtime, упакованный в tar.gz; это не формат AppImage).
Инструкция: [RELEASE_GUIDE](../../docs/RELEASE_GUIDE.md). Проверки и ограничения: [отчёт](../../docs/verification/STORE_LINUX_080.md).

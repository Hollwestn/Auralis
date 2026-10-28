# Linux

Сборка Ubuntu 24.04 x86_64: `bash scripts/build-linux.sh deb`.
Пакеты появляются здесь после успешной сборки на Linux. На Windows они не создаются.
В GitHub Actions предусмотрен процесс **Linux packages**.

Варианты: `deb`, `rpm` (дополнительный, не проверен), `app-image` (каталог с runtime, упакованный в tar.gz; это не формат AppImage).
Инструкция: [RELEASE_GUIDE](../../docs/RELEASE_GUIDE.md). Проверки и ограничения: [отчёт](../../docs/verification/STORE_LINUX_080.md).

DEB 0.8.0 собран, установлен и запущен в [Ubuntu CI](https://github.com/Hollwestn/Auralis/actions/runs/40727004279). [Артефакт](https://github.com/Hollwestn/Auralis/actions/runs/40727004279/artifacts/12834055712) хранится до 4 ноября 2026. RPM и переносимый tar.gz отдельно не проверены.

#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$root"
mvn -B -pl apps/desktop -am clean package -DskipTests
exec java --enable-native-access=ALL-UNNAMED -cp "$root/apps/desktop/target/auralis-desktop-0.1.0-SNAPSHOT.jar:$root/apps/desktop/target/lib/*" io.auralis.desktop.Launcher "$@"

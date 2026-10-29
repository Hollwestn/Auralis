#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
kind=${1:-deb}
case "$kind" in deb|rpm|app-image) ;; *) echo 'Usage: bash scripts/build-linux.sh [deb|rpm|app-image] [--skip-tests]' >&2; exit 2;; esac
[[ $(uname -s) == Linux ]] || { echo 'Linux packages must be built on Linux.' >&2; exit 2; }
[[ $(uname -m) == x86_64 ]] || { echo 'This packaging script currently targets Linux x86_64.' >&2; exit 2; }
for tool in java mvn jpackage ffmpeg ffprobe; do command -v "$tool" >/dev/null || { echo "Missing: $tool" >&2; exit 2; }; done
version=0.9.0
cd -- "$root"
tests=()
[[ ${2:-} != --skip-tests ]] || tests+=(-DskipTests)
# A clean target prevents Windows native JARs from leaking into a Linux package.
mvn -B -pl apps/desktop -am clean verify "${tests[@]}"
mkdir -p -- "$root/.runtime" "$root/release/linux"
staging=$(mktemp -d "$root/.runtime/linux-package.XXXXXX")
mkdir -p -- "$staging/input"
cp -- apps/desktop/target/auralis-desktop-0.1.0-SNAPSHOT.jar "$staging/input/"
cp -R -- apps/desktop/target/lib "$staging/input/lib"
if find "$staging/input/lib" \( -name '*natives-windows*' -o -name '*-win.jar' \) | grep -q .; then echo 'Foreign Windows native libraries in package' >&2; exit 1; fi
cp -- docs/THIRD_PARTY_NOTICES.md "$staging/input/"
args=(--type "$kind" --name Auralis --app-version "$version" --vendor Auralis --description 'Your sound. Your space.'
 --input "$staging/input" --main-jar auralis-desktop-0.1.0-SNAPSHOT.jar --main-class io.auralis.desktop.Launcher
 --dest "$staging/output" --icon "$root/assets/brand/auralis-symbol/png/auralis-256.png"
 --java-options '--enable-native-access=ALL-UNNAMED' --java-options '-Dfile.encoding=UTF-8' --java-options '-Dauralis.home=$APPDIR'
 --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.httpserver)
if [[ $kind != app-image ]]; then
 args+=(--linux-package-name auralis --linux-shortcut --linux-menu-group AudioVideo --linux-app-category sound)
 if [[ $kind == deb ]]; then
  args+=(--linux-package-deps "ffmpeg, libgtk-3-0t64, libasound2t64, libgl1, libx11-6, libxrandr2, libxi6, libxcursor1, libxinerama1, libxxf86vm1")
 else
  args+=(--linux-package-deps "ffmpeg, gtk3, alsa-lib, libGL, libX11, libXrandr, libXi, libXcursor, libXinerama, libXxf86vm")
 fi
fi
jpackage "${args[@]}"
if [[ $kind == app-image ]]; then
 tar -C "$staging/output" -czf "$root/release/linux/Auralis-$version-linux-x86_64.tar.gz" Auralis
 echo "Portable directory: $staging/output/Auralis"
else
 find "$staging/output" -maxdepth 1 -name "*.$kind" -exec cp -- {} "$root/release/linux/" \;
fi
(cd "$root/release/linux" && find . -maxdepth 1 -type f ! -name SHA256SUMS.txt -print0 | sort -z | xargs -0 -r sha256sum > SHA256SUMS.txt)
echo "Ready: $root/release/linux (system FFmpeg and desktop libraries are required)."


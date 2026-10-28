#!/usr/bin/env bash
set -euo pipefail
binary=${1:-/opt/auralis/bin/Auralis}
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
[[ -x $binary ]] || { echo "Missing installed launcher: $binary" >&2; exit 1; }
mkdir -p "$root/.runtime/linux-smoke" "$root/release/linux"
log="$root/.runtime/linux-smoke/launcher.log"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dauralis.data=$root/.runtime/linux-smoke/data"
"$binary" >"$log" 2>&1 &
app_pid=$!
trap 'kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true' EXIT
for ((attempt=0; attempt<40; attempt++)); do
 if ! kill -0 "$app_pid" 2>/dev/null; then cat "$log"; echo 'Installed application exited during startup' >&2; exit 1; fi
 if xdotool search --onlyvisible --name Auralis >/dev/null 2>&1; then
  sleep 5
  if ! kill -0 "$app_pid" 2>/dev/null; then cat "$log"; exit 1; fi
  read -r width height < <(xdotool getdisplaygeometry)
  ffmpeg -nostdin -v error -f x11grab -video_size "${width}x${height}" -i "$DISPLAY" -frames:v 1 -y "$root/release/linux/Auralis-linux-smoke.png"
  echo 'AURALIS_LINUX_INSTALLED_WINDOW_OK'
  exit 0
 fi
 sleep .5
done
cat "$log"
echo 'No visible Auralis window appeared' >&2
exit 1

#!/usr/bin/env bash
# Renders the docs diagrams (docs/tools/diagrams/*.html) to docs/assets/<name>.png with headless Chrome at 2x.
#
#   docs/tools/make-diagrams.sh
#
# @Author: Fred Feng
# @Date: 16/10/2026
# @Version 1.0.0
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
for f in "$ROOT"/docs/tools/diagrams/*.html; do
  name="$(basename "$f" .html)"
  size="$(grep -o 'data-size="[0-9]*x[0-9]*"' "$f" | head -1 | grep -o '[0-9]*x[0-9]*' || true)"
  w="${size%x*}"; h="${size#*x}"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
    --window-size="${w:-1400},${h:-400}" --screenshot="$ROOT/docs/assets/$name.png" "file://$f" >/dev/null 2>&1
  echo "docs/assets/$name.png"
done

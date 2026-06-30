#!/usr/bin/env bash
# Build self-contained desktop artifacts with jpackage (JavaFX bundled, no preinstalled JRE needed).
#
# Outputs into dist/:
#   - my-little-secrets-<version>-linux-x64.tar.gz   (portable app-image)
#   - my-little-secrets_<version>_amd64.deb          (if dpkg-deb is available)
#
# jlink (the default jpackage runtime builder) needs `objcopy` (binutils). When it's missing we fall
# back to bundling the full JDK as the runtime via --runtime-image (larger, but builds anywhere). CI
# runners have binutils, so they get the smaller jlinked runtime automatically.
set -euo pipefail

cd "$(dirname "$0")/.."
VERSION="${MLS_VERSION:-0.1.0}"
APP_NAME="my-little-secrets"
DIST="dist"
WORK="desktop/build/jpackage"

echo ">> Building desktop distribution (installDist)…"
./gradlew :desktop:installDist --no-daemon
LIB="desktop/build/install/desktop/lib"

RUNTIME_ARGS=()
if ! command -v objcopy >/dev/null 2>&1; then
  echo ">> objcopy not found — bundling the full JDK as the runtime (set up binutils for a smaller image)."
  RUNTIME_ARGS=(--runtime-image "${JAVA_HOME:?set JAVA_HOME to a JDK 21}")
fi

rm -rf "$WORK" && mkdir -p "$WORK" "$DIST"

common=(
  --name "$APP_NAME"
  --app-version "$VERSION"
  --vendor "$APP_NAME"
  --input "$LIB"
  --main-jar "desktop-${VERSION}.jar"
  --main-class app.mls.desktop.DesktopApp
  "${RUNTIME_ARGS[@]}"
)

echo ">> jpackage: app-image"
jpackage --type app-image "${common[@]}" --dest "$WORK"

TARBALL="$DIST/${APP_NAME}-${VERSION}-linux-x64.tar.gz"
tar -C "$WORK" -czf "$TARBALL" "$APP_NAME"
echo ">> wrote $TARBALL"

if command -v dpkg-deb >/dev/null 2>&1; then
  echo ">> jpackage: .deb"
  jpackage --type deb "${common[@]}" --dest "$DIST" \
    --linux-shortcut --linux-menu-group "Utility" || echo "!! .deb build failed (non-fatal)"
fi

echo ">> Desktop artifacts in $DIST/:"
ls -1 "$DIST"

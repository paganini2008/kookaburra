#!/usr/bin/env bash
# Packages Kookaburra Studio as a native app with its own Java runtime (jpackage), on the OS it runs on.
#
#   kookaburra-face/src/packaging/package.sh <version> <label> [release|snapshot]
#
#   version  1.2.3, v1.2.3 or 1.2.3-SNAPSHOT (a suffix is dropped: installers need plain numbers)
#   label    windows-x64 | linux-x64 | macos-arm64 | macos-x64 (part of the file names)
#   kind     release (a version tag): every package; snapshot (default): Windows gets the zip only, no msi
#
# Input:  kookaburra-face/target/dist/kookaburra-face.jar  (mvn -f kookaburra-face/pom.xml -P dist package
#         -Ddist.dir=$PWD/kookaburra-face/target/dist; for macos-x64 add -Djavafx.mac=mac)
# Output: kookaburra-face/target/release/kookaburra-studio-<label>.<msi (release only)|zip|deb|tar.gz|dmg>
#         (fixed names, so https://github.com/<repo>/releases/latest/download/<name> is always the newest)
#
# Needs JDK 17+ (jpackage); Windows also WiX 3 (preinstalled on GitHub's windows-2022 runners).
#
# @Author: Fred Feng
# @Date: 04/11/2026
# @Version 1.0.0
set -euo pipefail

VERSION="${1:?version}"
LABEL="${2:?label: windows-x64 | linux-x64 | macos-arm64 | macos-x64}"
KIND="${3:-snapshot}"
if [[ "$KIND" != "release" && "$KIND" != "snapshot" ]]; then
  echo "kind '$KIND' must be release or snapshot" >&2
  exit 1
fi
VERSION="${VERSION#v}"
VERSION="${VERSION%%-*}"
if ! [[ "$VERSION" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]]; then
  echo "version '$VERSION' must be numbers like 1.2.3" >&2
  exit 1
fi

FACE="$(cd "$(dirname "$0")/../.." && pwd)"
PACKAGING="$FACE/src/packaging"
JAR="$FACE/target/dist/kookaburra-face.jar"
WORK="$FACE/target/jpackage"
OUT="$FACE/target/release"
[[ -f "$JAR" ]] || { echo "missing $JAR: build it with -P dist first" >&2; exit 1; }
rm -rf "$WORK" "$OUT"
mkdir -p "$WORK/input" "$OUT"
cp "$JAR" "$WORK/input/"

# modules found by jdeps --print-module-deps, plus java.xml / java.management (logback) and accessibility
MODULES="java.base,java.desktop,java.logging,java.naming,java.prefs,java.xml,java.management,jdk.jfr,jdk.unsupported,jdk.charsets,jdk.accessibility"

common=(
  --app-version "$VERSION"
  --vendor "Fred Feng"
  --copyright "Copyright 2026 Fred Feng, Apache License 2.0"
  --description "Kookaburra Studio: browse, query and edit Kookaburra databases"
  --input "$WORK/input"
  --main-jar kookaburra-face.jar
  --main-class com.github.kookaburra.face.Launcher
  --java-options "-Xmx1g"
  --add-modules "$MODULES"
  --jlink-options "--strip-debug --no-header-files --no-man-pages --strip-native-commands"
)

case "$LABEL" in
  windows-*)
    NAME="Kookaburra Studio"
    jpackage "${common[@]}" --name "$NAME" --icon "$PACKAGING/kookaburra.ico" --type app-image --dest "$WORK/image"
    (cd "$WORK/image" && powershell -NoProfile -Command \
      "Compress-Archive -Path '$NAME' -DestinationPath 'kookaburra-studio-$LABEL.zip'")
    mv "$WORK/image/kookaburra-studio-$LABEL.zip" "$OUT/"
    if [[ "$KIND" != "release" ]]; then
      ls -l "$OUT"
      exit 0                                                  # snapshots: the zip only, the msi is for releases
    fi
    jpackage "${common[@]}" --name "$NAME" --icon "$PACKAGING/kookaburra.ico" --type msi --dest "$WORK/installer" \
      --win-menu --win-menu-group "Kookaburra" --win-shortcut --win-dir-chooser --win-per-user-install \
      --win-upgrade-uuid 16bb0fdb-c53e-4741-8493-47750543ac27
    mv "$WORK/installer/"*.msi "$OUT/kookaburra-studio-$LABEL.msi"
    ;;
  linux-*)
    NAME="kookaburra-studio"
    jpackage "${common[@]}" --name "$NAME" --icon "$PACKAGING/kookaburra.png" --type app-image --dest "$WORK/image"
    tar -C "$WORK/image" -czf "$OUT/kookaburra-studio-$LABEL.tar.gz" "$NAME"
    jpackage "${common[@]}" --name "$NAME" --icon "$PACKAGING/kookaburra.png" --type deb --dest "$WORK/installer" \
      --linux-shortcut --linux-menu-group "Development" --linux-app-category devel \
      --linux-deb-maintainer "paganini20080@gmail.com"
    mv "$WORK/installer/"*.deb "$OUT/kookaburra-studio-$LABEL.deb"
    ;;
  macos-*)
    NAME="Kookaburra Studio"
    if [[ "${VERSION%%.*}" == "0" ]]; then
      echo "macOS needs a version from 1.0.0 on (got $VERSION)" >&2
      exit 1
    fi
    jpackage "${common[@]}" --name "$NAME" --icon "$PACKAGING/kookaburra.icns" --type dmg --dest "$WORK/installer" \
      --mac-package-identifier com.github.kookaburra.studio --mac-package-name "Kookaburra"
    mv "$WORK/installer/"*.dmg "$OUT/kookaburra-studio-$LABEL.dmg"
    ;;
  *)
    echo "unknown label $LABEL" >&2
    exit 1
    ;;
esac
ls -l "$OUT"

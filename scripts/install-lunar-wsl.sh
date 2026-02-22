#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MC_PROFILE_VERSION="${MC_PROFILE_VERSION:-1.21}"
FABRIC_SUBDIR="${FABRIC_SUBDIR:-fabric-1.21.11}"

if command -v cmd.exe >/dev/null 2>&1; then
  WIN_USER_DEFAULT="$(cmd.exe /c "echo %USERNAME%" 2>/dev/null | tr -d '\r')"
else
  WIN_USER_DEFAULT=""
fi

WIN_USER="${WIN_USER:-$WIN_USER_DEFAULT}"
if [[ -z "${WIN_USER}" ]]; then
  echo "Could not detect Windows username."
  echo "Run again with WIN_USER set, for example:"
  echo "  WIN_USER=YourWindowsUser ./scripts/install-lunar-wsl.sh"
  exit 1
fi

TARGET_DIR="/mnt/c/Users/${WIN_USER}/.lunarclient/profiles/vanilla/${MC_PROFILE_VERSION}/mods/${FABRIC_SUBDIR}"

echo "Building jar..."
./gradlew clean build

JAR_PATH="$(
  ls -1t build/libs/minecraft-bladelow-*.jar 2>/dev/null \
    | grep -v -- '-sources\.jar$' \
    | head -n 1 || true
)"
if [[ -z "${JAR_PATH}" ]]; then
  echo "Build succeeded but no runtime jar was found in build/libs."
  exit 1
fi

mkdir -p "$TARGET_DIR"
cp "$JAR_PATH" "$TARGET_DIR/"

echo "Installed:"
echo "  ${JAR_PATH}"
echo "to:"
echo "  ${TARGET_DIR}"
echo
echo "Restart Lunar Client to load the update."

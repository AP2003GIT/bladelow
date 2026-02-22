#!/usr/bin/env bash
set -euo pipefail

# One-command WSL deploy helper: build + copy latest jar to Lunar Fabric mods.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"${ROOT_DIR}/scripts/install-lunar-wsl.sh"

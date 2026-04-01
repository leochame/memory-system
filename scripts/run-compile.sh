#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    return 0
  fi

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local candidate
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      return 0
    fi
    candidate="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      return 0
    fi
  fi

  if [[ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    return 0
  fi

  if [[ -d "/opt/homebrew/Cellar/openjdk@21" ]]; then
    local latest
    latest="$(ls -d /opt/homebrew/Cellar/openjdk@21/*/libexec/openjdk.jdk/Contents/Home 2>/dev/null | tail -n 1 || true)"
    if [[ -n "$latest" && -x "$latest/bin/java" ]]; then
      export JAVA_HOME="$latest"
      return 0
    fi
  fi

  return 1
}

if ! resolve_java_home; then
  echo "[ERROR] JAVA_HOME is not configured and no local JDK was detected." >&2
  echo "[HINT] Install JDK 21 and export JAVA_HOME before running compile." >&2
  exit 1
fi

echo "[INFO] JAVA_HOME=$JAVA_HOME"

if [[ $# -eq 0 ]]; then
  mvn -DskipTests compile
elif [[ "${1}" == -* ]]; then
  mvn "$@" compile
else
  mvn "$@"
fi

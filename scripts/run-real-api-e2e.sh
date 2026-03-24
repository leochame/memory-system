#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Fallback: load local .env when shell env is missing.
if [[ -z "${OPENAI_API_KEY:-}" && -f ".env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source ".env"
  set +a
fi

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "[ERROR] OPENAI_API_KEY is required." >&2
  exit 1
fi

export MEMSYS_RUN_REAL_API_E2E=true
export OPENAI_BASE_URL="${OPENAI_BASE_URL:-https://api.openai.com/v1}"
export OPENAI_MODEL="${OPENAI_MODEL:-gpt-4o-mini}"

# Fallback JAVA_HOME resolution for local shell runs.
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    CANDIDATE_JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [[ -n "${CANDIDATE_JAVA_HOME}" && -x "${CANDIDATE_JAVA_HOME}/bin/java" ]]; then
      export JAVA_HOME="${CANDIDATE_JAVA_HOME}"
    fi
  fi

  if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    if [[ -d "/opt/homebrew/Cellar/openjdk@21" ]]; then
      LATEST_CELLAR_JAVA_HOME="$(ls -d /opt/homebrew/Cellar/openjdk@21/*/libexec/openjdk.jdk/Contents/Home 2>/dev/null | tail -n 1 || true)"
      if [[ -n "${LATEST_CELLAR_JAVA_HOME}" && -x "${LATEST_CELLAR_JAVA_HOME}/bin/java" ]]; then
        export JAVA_HOME="${LATEST_CELLAR_JAVA_HOME}"
      fi
    fi
  fi

  if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    if [[ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]]; then
      export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    fi
  fi
fi

REPORT_DIR="logs/e2e"
mkdir -p "$REPORT_DIR"
REPORT_FILE="${REPORT_DIR}/real-api-e2e.$(date +%Y-%m-%d_%H-%M-%S).log"

{
  echo "[INFO] real-api-e2e start: $(date -Iseconds)"
  echo "[INFO] OPENAI_BASE_URL=${OPENAI_BASE_URL}"
  echo "[INFO] OPENAI_MODEL=${OPENAI_MODEL}"
  echo "[INFO] command: mvn -Dtest=RealApiE2ETest test"
} | tee "$REPORT_FILE"

mvn -Dtest=RealApiE2ETest test | tee -a "$REPORT_FILE"

echo "[INFO] report: $REPORT_FILE" | tee -a "$REPORT_FILE"

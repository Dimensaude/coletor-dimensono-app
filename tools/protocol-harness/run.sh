#!/usr/bin/env bash
# Runs the protocol code (android/app/src/main/java/.../huawei) against a simulated band on a plain JVM.
# Needs only a JDK (17+). Usage: tools/protocol-harness/run.sh [-v]
set -euo pipefail
OUT=$(mktemp -d)
bash "$(dirname "$0")/build.sh" "$OUT"
java -cp "$OUT" HarnessMain "$@"

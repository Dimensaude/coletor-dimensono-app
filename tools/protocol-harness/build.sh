#!/usr/bin/env bash
# Compiles the protocol code (android/app/src/main/java/.../huawei), the Android fakes and the harness
# (HarnessMain, BandServer) into <outdir>. Needs only a JDK (17+); works with macOS bash and Git Bash.
# Usage: tools/protocol-harness/build.sh <outdir>
#   then: java -cp <outdir> HarnessMain [-v]
#     or: java -cp <outdir> BandServer [--encrypt-method 1] ...   (line protocol on stdin/stdout)
set -euo pipefail
if [ $# -ne 1 ]; then
    echo "usage: $0 <outdir>" >&2
    exit 2
fi
mkdir -p "$1"
OUT=$(cd "$1" && pwd)
cd "$(dirname "$0")"
SRC=../../android/app/src/main/java/br/dimensono/bandsono
javac -nowarn -encoding UTF-8 -d "$OUT" $(find fakes test -name '*.java') $(find "$SRC/huawei" "$SRC/log" -name '*.java') "$SRC/Hrv.java" "$SRC/CalibrationPolicy.java" "$SRC/ParticipantCode.java"

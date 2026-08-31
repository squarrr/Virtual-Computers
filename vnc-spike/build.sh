#!/usr/bin/env bash
# Compiles and runs the spike. No build tool, no dependencies - just a JDK.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$root/out"
find "$root/src" -name '*.java' -print0 | xargs -0 javac -d "$root/out"
java -cp "$root/out" vncspike.Spike "$@"

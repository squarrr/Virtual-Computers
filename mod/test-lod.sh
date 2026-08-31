#!/bin/sh
# Runs the level-of-detail arithmetic tests. See test-lod.cmd for why they exist.
set -e
cd "$(dirname "$0")"
JOML=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.joml" -name 'joml-*.jar' ! -name '*sources*' | head -1)
[ -n "$JOML" ] || { echo "Could not find joml in the Gradle cache. Run ./gradlew build once first."; exit 1; }
rm -rf build/lodtest && mkdir -p build/lodtest
javac -d build/lodtest -cp "$JOML" \
  src/main/java/squarrr/virtualcomputers/lod/Rung.java \
  src/main/java/squarrr/virtualcomputers/lod/LodState.java \
  src/main/java/squarrr/virtualcomputers/lod/ScreenQuad.java \
  src/main/java/squarrr/virtualcomputers/screen/Resample.java \
  src/test/java/LodMathTest.java
java -cp "build/lodtest:$JOML" LodMathTest

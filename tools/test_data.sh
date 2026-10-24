#!/usr/bin/env bash
# Checks the pure parts of the home data layer (fuzzy search, Stremio library parsing, sunrise/sunset) on a plain JVM.
# Run ./build.sh once first: it writes out/gen/.../Config.java and finds the Android SDK this script compiles against.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/platforms" ]; then SDK="$d"; break; fi
  done
fi
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "Android platform not found."; exit 1; }
[ -f out/gen/io/freemytv/Config.java ] || { echo "Run ./build.sh first (it writes Config.java)."; exit 1; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"; JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
rm -rf out/test && mkdir -p out/test
# Compiled against android.jar (whose org.json only has stubs); run with the test's own tiny org.json first on the
# classpath, because the JVM has none. The pure classes used here never touch the rest of Android.
"$JAVAC" -nowarn -d out/test $(find tools/testshim -name '*.java')
"$JAVAC" -nowarn -d out/test -cp "$AJ:out/test" -sourcepath app/src:out/gen tools/test/io/freemytv/TestData.java
"$JAVA" -cp out/test io.freemytv.TestData

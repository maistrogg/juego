#!/usr/bin/env bash
# Compila Interprete.apk usando solo el Android SDK (sin Gradle).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets \
  --java "$OUT/gen" -o "$OUT/unsigned.apk" "$OUT/res/res.zip" --auto-add-overlay

javac -nowarn -encoding UTF-8 --release 8 -Xlint:-options -classpath "$JAR" \
  -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')

(cd "$OUT/classes" && jar cf ../classes.jar .)
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" "$OUT/classes.jar"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f interprete.keystore ]; then
  keytool -genkeypair -keystore interprete.keystore -storepass interprete -keypass interprete \
    -alias interprete -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Interprete" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks interprete.keystore --ks-pass pass:interprete --key-pass pass:interprete \
  --out Interprete.apk "$OUT/aligned.apk"
"$BT/apksigner" verify Interprete.apk
ls -la Interprete.apk

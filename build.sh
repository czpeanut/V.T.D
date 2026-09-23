#!/usr/bin/env bash
# Build V.T.D.: Java -> dex -> signed APK (minSdk 10, pure-Java, no native libs),
# then wrap it into a loadable .epk using the public OBU cert in keys/ (for the App Garage loader).
set -euo pipefail
cd "$(dirname "$0")"

# Cloners: point these at your own toolchain — set JAVA_HOME / ANDROID_SDK env vars, or edit here.
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot}"
SDK="${ANDROID_SDK:-/c/Users/raid2/scoop/apps/android-clt/current}"
BT="$SDK/build-tools/34.0.0"
ANDJAR="$SDK/platforms/android-34/android.jar"

# tool suffixes: Windows/Git-Bash uses .exe / .bat; Linux/macOS use none
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) X=.exe; B=.bat;; *) X=""; B="";; esac
AAPT="$BT/aapt$X"; ZIPALIGN="$BT/zipalign$X"; APKSIGNER="$BT/apksigner$B"; D8="$BT/d8$B"
JAVAC="$JAVA_HOME/bin/javac$X"; KEYTOOL="$JAVA_HOME/bin/keytool$X"; JAR="$JAVA_HOME/bin/jar$X"

# Monotonic versionCode (unix time) so a rebuild always > the installed one -> App Garage shows it
# as an update instead of hiding it (it hides entries whose versionCode <= what's installed).
# versionName ("1.0") stays the human version in AndroidManifest.xml.
VC=$(date +%s)

rm -rf build && mkdir -p build/classes build/dex
echo "== [1/6] javac (release 8) =="
# -encoding UTF-8 is not optional: the sources carry CJK labels, and without it javac
# silently decodes them with the platform default and the head unit shows mojibake.
"$JAVAC" --release 8 -encoding UTF-8 -g -d build/classes -classpath "$ANDJAR" src/vtd/dashboard/*.java
echo "  compiled: $(find build/classes -name '*.class' | wc -l) classes"

echo "== [2/6] d8 -> classes.dex (min-api 10) =="
"$D8" --min-api 10 --lib "$ANDJAR" --output build/dex $(find build/classes -name '*.class')
ls -l build/dex/classes.dex

echo "== [3/6] package APK (versionCode=$VC) =="
# stamp versionCode into a temp manifest (aapt honors the manifest value; --version-code is a no-op here)
sed "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VC\"/" AndroidManifest.xml > build/AndroidManifest.xml
# -A assets packages assets/ (car.png, the user-supplied car outline; optional at runtime)
"$AAPT" package -f -M build/AndroidManifest.xml -S res -A assets -I "$ANDJAR" -F build/vtd.unsigned.apk
( cd build/dex && "$AAPT" add ../vtd.unsigned.apk classes.dex >/dev/null )

echo "== [4/6] zipalign =="
"$ZIPALIGN" -f -p 4 build/vtd.unsigned.apk build/vtd.aligned.apk

echo "== [5/6] sign (v1 for API 10) =="
# keystore lives outside build/ so it survives `rm -rf build` -> stable signature -> updates install over old
if [ ! -f keystore.ks ]; then
  "$KEYTOOL" -genkeypair -keystore keystore.ks -alias vtd -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=VTD" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks keystore.ks --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 10 --v1-signing-enabled true --v2-signing-enabled true \
  --out build/vtd.apk build/vtd.aligned.apk
"$APKSIGNER" verify --min-sdk-version 10 build/vtd.apk >/dev/null 2>&1 && echo "  signature OK"

echo "== [6/6] wrap into .epk (uses the public OBU cert in keys/obu_cert.pem) =="
if [ -f tools/epk_tool.py ] && [ -f keys/obu_cert.pem ]; then
  if ! python tools/epk_tool.py build build/vtd.apk build/vtd.epk --cert keys/obu_cert.pem; then
    echo "  .epk wrap failed — need Python 3 + 'pip install cryptography'. The APK is still ready."
  fi
else
  echo "  skipped: keys/obu_cert.pem missing — APK is ready, but no .epk was produced."
fi

echo ""
echo "== VERIFY =="
"$AAPT" dump badging build/vtd.apk 2>/dev/null | grep -iE "package:|sdkVersion|native-code|launchable" || true
"$JAR" -tf build/vtd.apk | grep -viE "META-INF/" | head
echo "DONE -> build/vtd.apk"; ls -l build/vtd.apk build/vtd.epk 2>/dev/null

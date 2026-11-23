#!/usr/bin/env bash
# Builds out/hakiosk.apk with plain aapt2 + javac + d8 + apksigner (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-17.0.21+9}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK:-$HOME/tools/android-sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
B=build
rm -rf "$B"; mkdir -p "$B/res" "$B/classes" "$B/dex" out

"$BT/aapt2" compile --dir res -o "$B/res/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 21 --target-sdk-version 22 "$B/res/res.zip"

javac -source 8 -target 8 -encoding UTF-8 -Xlint:all,-options -Werror \
    -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -d "$B/classes" $(find src -name '*.java')

"$BT/d8" --release --min-api 21 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
python3 -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" \
    "$B/unsigned.apk" "$B/dex/classes.dex"

"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

# The installed app accepts an update only when the signature matches.
# The keystore and its password stay outside the repo; this script never creates them.
die() { echo "error: $*" >&2; exit 1; }
REPO_REAL=$(readlink -f ".")
outside_repo() {
    local real
    real=$(readlink -f "$1")
    case "$real" in
        "$REPO_REAL"|"$REPO_REAL"/*) return 1 ;;
    esac
}

KS="${HAKIOSK_KEYSTORE:-}"
if [ -z "$KS" ]; then
    for c in "${XDG_CONFIG_HOME:-$HOME/.config}/hakiosk/keystore.jks" "$HOME/.hakiosk/keystore.jks"; do
        [ -f "$c" ] && KS=$c && break
    done
fi
[ -n "$KS" ] && [ -f "$KS" ] || die "signing keystore not found.
Set HAKIOSK_KEYSTORE to a .jks file outside this repo, or place it at ~/.config/hakiosk/keystore.jks
An update must be signed with the same key as the app already on the tablet."
outside_repo "$KS" || die "keystore is inside the repo ($(readlink -f "$KS")). Move it outside and set HAKIOSK_KEYSTORE."

if [ -n "${HAKIOSK_KEYSTORE_PASS:-}" ]; then
    PASS_SPEC="env:HAKIOSK_KEYSTORE_PASS"
else
    PF="${HAKIOSK_KEYSTORE_PASS_FILE:-}"
    if [ -z "$PF" ]; then
        for c in "${XDG_CONFIG_HOME:-$HOME/.config}/hakiosk/keystore.pass" "$HOME/.hakiosk/keystore.pass"; do
            [ -f "$c" ] && PF=$c && break
        done
    fi
    [ -n "$PF" ] && [ -f "$PF" ] || die "keystore password not found.
Set HAKIOSK_KEYSTORE_PASS, or HAKIOSK_KEYSTORE_PASS_FILE, or use ~/.config/hakiosk/keystore.pass (first line is the password)."
    outside_repo "$PF" || die "password file is inside the repo ($(readlink -f "$PF")). Move it outside."
    PASS_SPEC="file:$PF"
fi

ALIAS="${HAKIOSK_KEY_ALIAS:-hakiosk}"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass "$PASS_SPEC" \
    --min-sdk-version 21 --v4-signing-enabled false --out out/hakiosk.apk "$B/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 21 out/hakiosk.apk
echo "OK: out/hakiosk.apk ($(stat -c %s out/hakiosk.apk) bytes)"

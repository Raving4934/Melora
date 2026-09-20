#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'Android release verification failed: %s\n' "$1" >&2
  exit 1
}

APK_PATH="${1:-}"
[[ -n "$APK_PATH" ]] || fail 'usage: verify-android-apk.sh /path/to/release.apk'
[[ -f "$APK_PATH" ]] || fail "APK does not exist: $APK_PATH"
[[ -s "$APK_PATH" ]] || fail 'APK is empty.'

APK_BASENAME="$(basename -- "$APK_PATH")"
APK_BASENAME_LOWER="${APK_BASENAME,,}"
case "$APK_BASENAME_LOWER" in
  *debug*|*unsigned*)
    fail "refusing to publish an APK named as debug or unsigned: $APK_BASENAME"
    ;;
esac

KEYSTORE_PATH="${MELORA_KEYSTORE_PATH:-}"
KEY_ALIAS="${MELORA_KEY_ALIAS:-}"
EXPECTED_VERSION="${MELORA_EXPECTED_VERSION:-}"
EXPECTED_VERSION_CODE="${MELORA_EXPECTED_VERSION_CODE:-}"
EXPECTED_PACKAGE="${MELORA_PACKAGE_ID:-com.leyu.melora}"

[[ -n "$KEYSTORE_PATH" ]] || fail 'MELORA_KEYSTORE_PATH is not set.'
[[ -f "$KEYSTORE_PATH" ]] || fail 'configured keystore file does not exist.'
[[ -s "$KEYSTORE_PATH" ]] || fail 'configured keystore file is empty.'
[[ -n "${MELORA_KEYSTORE_PASSWORD:-}" ]] || fail 'MELORA_KEYSTORE_PASSWORD is not set.'
[[ -n "$KEY_ALIAS" ]] || fail 'MELORA_KEY_ALIAS is not set.'
[[ -n "$EXPECTED_VERSION" ]] || fail 'MELORA_EXPECTED_VERSION is not set.'
[[ "$EXPECTED_VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || fail 'MELORA_EXPECTED_VERSION_CODE is not a positive integer.'
[[ "$APK_BASENAME" == "melora-android-v${EXPECTED_VERSION}-arm64-v8a.apk" ]] || fail 'APK filename must name the requested version and arm64-v8a ABI.'
[[ -n "$EXPECTED_PACKAGE" ]] || fail 'MELORA_PACKAGE_ID is empty.'

if [[ "${KEY_ALIAS,,}" == androiddebugkey || "${KEY_ALIAS,,}" == androiddebug* ]]; then
  fail 'the Android debug keystore alias is not permitted for a release APK.'
fi

KEYTOOL_BIN="${KEYTOOL:-}"
if [[ -z "$KEYTOOL_BIN" ]]; then
  KEYTOOL_BIN="$(command -v keytool || true)"
fi
APKSIGNER_BIN="${APKSIGNER:-}"
if [[ -z "$APKSIGNER_BIN" ]]; then
  APKSIGNER_BIN="$(command -v apksigner || true)"
fi
AAPT2_BIN="${AAPT2:-}"
if [[ -z "$AAPT2_BIN" ]]; then
  AAPT2_BIN="$(command -v aapt2 || true)"
fi
[[ -x "$KEYTOOL_BIN" ]] || fail 'keytool was not found; install a JDK before release verification.'
[[ -x "$APKSIGNER_BIN" ]] || fail 'apksigner was not found; install Android SDK build-tools before release verification.'
[[ -x "$AAPT2_BIN" ]] || fail 'aapt2 was not found; install Android SDK build-tools before release verification.'

TMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TMP_DIR"' EXIT

# Validate the alias and password without printing key material or command arguments.
if ! "$KEYTOOL_BIN" \
  -J-Duser.language=en \
  -J-Duser.country=US \
  -list \
  -keystore "$KEYSTORE_PATH" \
  -alias "$KEY_ALIAS" \
  -storepass:env MELORA_KEYSTORE_PASSWORD \
  > /dev/null 2>"$TMP_DIR/keytool-error"; then
  fail 'the configured keystore, alias, or keystore password is invalid.'
fi

KEYSTORE_DETAILS="$TMP_DIR/keystore-details"
if ! "$KEYTOOL_BIN" \
  -J-Duser.language=en \
  -J-Duser.country=US \
  -list \
  -v \
  -keystore "$KEYSTORE_PATH" \
  -alias "$KEY_ALIAS" \
  -storepass:env MELORA_KEYSTORE_PASSWORD \
  >"$KEYSTORE_DETAILS" 2>"$TMP_DIR/keytool-error"; then
  fail 'unable to inspect the configured release certificate.'
fi

if grep -Eq 'Owner:.*CN=Android Debug([, ]|$)' "$KEYSTORE_DETAILS"; then
  fail 'the configured certificate is an Android debug certificate.'
fi

APK_SIGNATURE="$TMP_DIR/apksigner-output"
if ! "$APKSIGNER_BIN" verify --verbose --print-certs "$APK_PATH" >"$APK_SIGNATURE" 2>&1; then
  fail 'apksigner rejected the APK.'
fi

KEYSTORE_DIGEST="$(sed -nE 's/^[[:space:]]*SHA256:[[:space:]]*([0-9A-Fa-f:]+).*$/\1/p' "$KEYSTORE_DETAILS" | head -n 1)"
APK_DIGEST="$(sed -nE 's/.*SHA-256 digest:[[:space:]]*([0-9A-Fa-f:]+).*$/\1/p' "$APK_SIGNATURE" | head -n 1)"
[[ -n "$KEYSTORE_DIGEST" ]] || fail 'could not read the release certificate fingerprint from the configured keystore.'
[[ -n "$APK_DIGEST" ]] || fail 'could not read the release certificate fingerprint from the APK.'

normalize_digest() {
  printf '%s' "$1" | tr -d '[:space:]:\r' | tr '[:lower:]' '[:upper:]'
}

if [[ "$(normalize_digest "$KEYSTORE_DIGEST")" != "$(normalize_digest "$APK_DIGEST")" ]]; then
  fail 'APK certificate does not match the configured release keystore; debug or unrelated signing keys are not accepted.'
fi

BADGING="$TMP_DIR/badging"
if ! "$AAPT2_BIN" dump badging "$APK_PATH" >"$BADGING" 2>&1; then
  fail 'aapt2 could not read the APK manifest.'
fi

grep -q "application-debuggable" "$BADGING" && fail 'debuggable APKs are not accepted for release.'
MANIFEST="$TMP_DIR/manifest"
if ! "$AAPT2_BIN" dump xmltree --file AndroidManifest.xml "$APK_PATH" >"$MANIFEST" 2>&1; then
  fail 'aapt2 could not inspect the APK application flags.'
fi
if grep -Eq 'android:testOnly.*(0xffffffff|true)' "$MANIFEST"; then
  fail 'testOnly APKs are not accepted for release.'
fi

PACKAGE_NAME="$(sed -nE "s/^package: name='([^']+)'.*$/\1/p" "$BADGING" | head -n 1)"
VERSION_CODE="$(sed -nE "s/^package:.*versionCode='([^']+)'.*$/\1/p" "$BADGING" | head -n 1)"
VERSION_NAME="$(sed -nE "s/^package:.*versionName='([^']+)'.*$/\1/p" "$BADGING" | head -n 1)"
[[ "$PACKAGE_NAME" == "$EXPECTED_PACKAGE" ]] || fail "APK package is '$PACKAGE_NAME', expected '$EXPECTED_PACKAGE'."
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || fail 'APK versionCode is missing or not a positive integer.'
[[ "$VERSION_CODE" == "$EXPECTED_VERSION_CODE" ]] || fail "APK versionCode is '$VERSION_CODE', expected '$EXPECTED_VERSION_CODE' from the build checkout."
[[ "$VERSION_NAME" == "$EXPECTED_VERSION" ]] || fail "APK versionName is '$VERSION_NAME', expected '$EXPECTED_VERSION' from the android-v tag."

NATIVE_CODE="$(sed -nE 's/^native-code:[[:space:]]*//p' "$BADGING" | sed -E 's/[[:space:]]+$//')"
[[ "$NATIVE_CODE" == "'arm64-v8a'" ]] || fail 'APK native libraries must be arm64-v8a only to match the release filename.'

printf 'Verified Android release APK: package=%s versionName=%s versionCode=%s abi=arm64-v8a certificateSha256=%s; release certificate matches the configured keystore.\n' \
  "$PACKAGE_NAME" "$VERSION_NAME" "$VERSION_CODE" "$(normalize_digest "$APK_DIGEST")"

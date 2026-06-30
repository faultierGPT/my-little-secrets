#!/usr/bin/env bash
# Generate the signing material for releases — ONCE, on a trusted machine. Nothing here is ever
# committed (.signing/ and all keystores are gitignored). Back the keys up securely; losing them
# means you can no longer publish verifiable updates under the same identity.
#
#   1. A GPG keypair  -> signs desktop/server artifacts (publisher integrity).
#   2. An Android keystore -> signs the APK for sideloading.
#
# For CI, export the secret key and keystore into the platform's encrypted secret store (see
# RELEASE.md); never paste them into logs.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT=".signing"
mkdir -p "$OUT"
NAME="${MLS_PUBLISHER_NAME:-my-little-secrets}"
EMAIL="${MLS_PUBLISHER_EMAIL:-releases@example.invalid}"

# ---- 1. GPG publisher key ----
if command -v gpg >/dev/null; then
  echo ">> Generating GPG signing key for \"$NAME <$EMAIL>\"…"
  cat > "$OUT/gpg-params" <<EOF
%no-protection
Key-Type: eddsa
Key-Curve: ed25519
Subkey-Type: ecdh
Subkey-Curve: cv25519
Name-Real: $NAME
Name-Email: $EMAIL
Expire-Date: 2y
%commit
EOF
  gpg --batch --gen-key "$OUT/gpg-params"
  rm -f "$OUT/gpg-params"
  FPR=$(gpg --list-keys --with-colons "$EMAIL" | awk -F: '/^fpr:/ {print $10; exit}')
  gpg --armor --export "$EMAIL" > "$OUT/publisher-pubkey.asc"
  echo ">> GPG fingerprint: $FPR"
  echo ">> Public key written to $OUT/publisher-pubkey.asc — publish it; record the fingerprint in RELEASE.md."
else
  echo "!! gpg not found — skipping GPG key generation."
fi

# ---- 2. Android keystore ----
if command -v keytool >/dev/null; then
  KS="$OUT/android-release.jks"
  ALIAS="${MLS_ANDROID_KEY_ALIAS:-mls-release}"
  STOREPASS="${MLS_ANDROID_KEYSTORE_PASSWORD:?set MLS_ANDROID_KEYSTORE_PASSWORD to create the keystore}"
  KEYPASS="${MLS_ANDROID_KEY_PASSWORD:-$STOREPASS}"
  if [ -f "$KS" ]; then
    echo ">> $KS already exists — leaving it untouched."
  else
    echo ">> Generating Android release keystore $KS (alias=$ALIAS)…"
    keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" \
      -keyalg RSA -keysize 4096 -validity 10000 \
      -storepass "$STOREPASS" -keypass "$KEYPASS" \
      -dname "CN=$NAME, O=$NAME"
    echo ">> Wrote $KS — back it up; reuse it for EVERY release (a new key = a different app identity)."
  fi
else
  echo "!! keytool not found — skipping Android keystore generation."
fi

echo ">> Done. .signing/ is gitignored; keep it offline + backed up."

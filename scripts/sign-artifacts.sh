#!/usr/bin/env bash
# Produce publisher-verifiable integrity material for the release artifacts in dist/:
#   - SHA256SUMS         checksums of every artifact
#   - SHA256SUMS.asc     detached GPG signature over SHA256SUMS (the chain of trust)
#   - <artifact>.asc     detached GPG signature per artifact (optional, convenient)
#
# The signing key NEVER lives in the repo. Supply it via the environment:
#   MLS_GPG_KEY_ID   the key id / fingerprint / email to sign with (required)
#   GNUPGHOME        optional, to use an ephemeral keyring (CI imports the secret key into it)
set -euo pipefail

cd "$(dirname "$0")/.."
DIST="${1:-dist}"
: "${MLS_GPG_KEY_ID:?set MLS_GPG_KEY_ID to the signing key id/fingerprint}"
command -v gpg >/dev/null || { echo "gpg not found"; exit 1; }

cd "$DIST"
echo ">> Hashing artifacts in $DIST/"
# Hash every regular file except the integrity files themselves.
: > SHA256SUMS
find . -maxdepth 1 -type f ! -name 'SHA256SUMS*' -printf '%P\n' | sort | while read -r f; do
  sha256sum "$f" >> SHA256SUMS
done
cat SHA256SUMS

echo ">> Signing SHA256SUMS with $MLS_GPG_KEY_ID"
rm -f SHA256SUMS.asc
gpg --batch --yes --local-user "$MLS_GPG_KEY_ID" --armor --detach-sign --output SHA256SUMS.asc SHA256SUMS

echo ">> Per-artifact detached signatures"
find . -maxdepth 1 -type f ! -name 'SHA256SUMS*' ! -name '*.asc' -printf '%P\n' | while read -r f; do
  gpg --batch --yes --local-user "$MLS_GPG_KEY_ID" --armor --detach-sign --output "$f.asc" "$f"
done

echo ">> Signed. Publish: artifacts + SHA256SUMS + SHA256SUMS.asc (+ per-artifact .asc) + your public key."

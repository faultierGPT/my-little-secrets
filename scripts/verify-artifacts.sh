#!/usr/bin/env bash
# Verify downloaded release artifacts: the publisher's GPG signature over SHA256SUMS, then that each
# artifact matches its checksum. Run this BEFORE trusting/installing anything.
#
# First time, import + verify the publisher's public key out-of-band, e.g.:
#   gpg --import my-little-secrets-pubkey.asc
#   gpg --fingerprint <key>          # compare against the fingerprint published in RELEASE.md
set -euo pipefail

DIR="${1:-.}"
cd "$DIR"
command -v gpg >/dev/null || { echo "gpg not found"; exit 1; }

[ -f SHA256SUMS ] || { echo "SHA256SUMS missing"; exit 1; }
[ -f SHA256SUMS.asc ] || { echo "SHA256SUMS.asc (signature) missing"; exit 1; }

echo ">> Verifying the publisher signature over SHA256SUMS…"
gpg --verify SHA256SUMS.asc SHA256SUMS

echo ">> Verifying artifact checksums…"
sha256sum --check --strict SHA256SUMS

echo "OK — signature valid and all checksums match."

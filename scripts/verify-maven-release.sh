#!/usr/bin/env bash
# Build the production release profile with a disposable identity. Never upload this test bundle.
set -euo pipefail

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
temporary_parent=$(cd "${TMPDIR:-/tmp}" && pwd -P)
verification_root=$(mktemp -d "$temporary_parent/shardpilot-release.XXXXXX")
export GNUPGHOME="$verification_root/gnupg"

cleanup() {
  gpgconf --homedir "$GNUPGHOME" --kill gpg-agent >/dev/null 2>&1 || true
  # Delete only this invocation's resolved temporary directory, including on Git Bash/Windows.
  if [[ "$verification_root" == "$temporary_parent"/shardpilot-release.* && ! -L "$verification_root" && -d "$verification_root" ]]; then
    resolved=$(cd "$verification_root" && pwd -P)
    if [[ "$resolved" == "$verification_root" ]]; then rm -rf -- "$verification_root"; fi
  fi
}
trap cleanup EXIT
mkdir "$GNUPGHOME"
# Git Bash relies on the current user's Windows temp-directory ACL rather than POSIX chmod.
case "$(uname -s)" in MINGW*|MSYS*) ;; *) chmod 700 "$GNUPGHOME" ;; esac
command -v gpg >/dev/null
command -v gpgconf >/dev/null

native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}

identity='ShardPilot release verification <release-verification@example.invalid>'
# This public test passphrase protects only the freshly generated, short-lived test identity.
export MAVEN_GPG_PASSPHRASE='shardpilot-disposable-verification-key'
printf '%s\n' "$MAVEN_GPG_PASSPHRASE" | gpg --no-options --batch --pinentry-mode loopback --passphrase-fd 0 \
  --quick-generate-key "$identity" rsa2048 sign 1d
MAVEN_GPG_KEY=$(printf '%s\n' "$MAVEN_GPG_PASSPHRASE" | gpg --no-options --batch --pinentry-mode loopback \
  --passphrase-fd 0 --armor --export-secret-keys "$identity")
export MAVEN_GPG_KEY
MAVEN_GPG_KEY_FINGERPRINT=$(gpg --no-options --batch --with-colons --list-secret-keys "$identity" | awk -F: '$1 == "fpr" { print $10; exit }')
export MAVEN_GPG_KEY_FINGERPRINT
unset MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_PASSWORD

cd "$repository_root"
mkdir -p build/release-verification
# Never retain an earlier green report when this invocation fails validation or signing checks.
rm -f -- build/release-verification/report.json
local_repository=$(native_path "$repository_root/build/release-verification/repository")
effective_pom=$(native_path "$repository_root/build/release-verification/effective-pom.xml")
# The repository is isolated from the developer's normal candidate installations. The explicit
# skipUpload flag is mandatory here, independently of the profile's safe default.
bash ./mvnw -B -ntp -Pcentral-release clean deploy help:effective-pom \
  -DskipTests -DskipITs -Dcentral.skipUpload=true \
  "-Dmaven.repo.local=$local_repository" "-Doutput=$effective_pom"

java_command=java
if [[ -n "${JAVA_HOME:-}" ]]; then java_command="$JAVA_HOME/bin/java"; fi
"$java_command" "$(native_path "$repository_root/scripts/VerifyReleaseArtifacts.java")" \
  "$(native_path "$repository_root")" "$(native_path "$verification_root/unpacked")" \
  > "$verification_root/report.json"
while IFS= read -r -d '' signature; do
  gpg --no-options --batch --verify "$signature" "${signature%.asc}"
done < <(find "$verification_root/unpacked" -type f -name '*.asc' -print0)
mv -- "$verification_root/report.json" build/release-verification/report.json
cat build/release-verification/report.json

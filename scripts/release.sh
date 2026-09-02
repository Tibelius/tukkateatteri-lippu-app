#!/usr/bin/env bash

set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gradle_file="$project_root/app/build.gradle.kts"
keystore_properties="$project_root/keystore.properties"
apk_path="$project_root/app/build/outputs/apk/release/Tukkateatteri-release.apk"

fail() {
    printf 'Error: %s\n' "$1" >&2
    exit 1
}

usage() {
    printf 'Usage: ./scripts/release.sh <version-name>\nExample: ./scripts/release.sh 1.1\n' >&2
    exit 1
}

if [[ $# -ne 1 ]]; then
    usage
fi

version_name="$1"
if [[ ! "$version_name" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]]; then
    fail "Version name must use the form 1.1 or 1.1.1."
fi

cd "$project_root"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Run this from a Git checkout."
[[ "$(git branch --show-current)" == "main" ]] || fail "Release from the main branch."
[[ -z "$(git status --porcelain)" ]] || fail "Commit, stash, or remove all changes before preparing a release."

release_tag="v$version_name"
if git rev-parse --verify --quiet "refs/tags/$release_tag" >/dev/null; then
    fail "Tag $release_tag already exists."
fi

[[ -f "$keystore_properties" ]] || fail "Create keystore.properties from keystore.properties.example."
for required_property in storeFile storePassword keyAlias keyPassword; do
    grep --quiet --extended-regexp "^$required_property=.+" "$keystore_properties" || \
        fail "keystore.properties is missing $required_property."
done

store_file="$(sed -n 's/^storeFile=//p' "$keystore_properties" | head -n 1)"
[[ -f "$store_file" ]] || fail "The configured keystore file does not exist."

current_version_code="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+)$/\1/p' "$gradle_file")"
[[ "$current_version_code" =~ ^[0-9]+$ ]] || fail "Could not read versionCode from $gradle_file."
current_version_name="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)"$/\1/p' "$gradle_file")"
[[ -n "$current_version_name" ]] || fail "Could not read versionName from $gradle_file."
next_version_code=$((current_version_code + 1))

sed -i -E \
    -e "s/^([[:space:]]*versionCode = )[0-9]+$/\\1$next_version_code/" \
    -e "s/^([[:space:]]*versionName = \")[^\"]*(\")$/\\1$version_name\\2/" \
    "$gradle_file"

restore_versions() {
    sed -i -E \
        -e "s/^([[:space:]]*versionCode = )[0-9]+$/\\1$current_version_code/" \
        -e "s/^([[:space:]]*versionName = \")[^\"]*(\")$/\\1$current_version_name\\2/" \
        "$gradle_file"
}
trap restore_versions EXIT

git diff --check
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleRelease

[[ -f "$apk_path" ]] || fail "Release APK was not created at $apk_path."

sdk_dir="$(sed -n 's/^sdk.dir=//p' "$project_root/local.properties" | head -n 1)"
[[ -n "$sdk_dir" ]] || fail "Could not read sdk.dir from local.properties."
apksigner_path="$(find "$sdk_dir/build-tools" -type f -name apksigner -print | sort -V | tail -n 1)"
[[ -n "$apksigner_path" ]] || fail "Could not find apksigner in the Android SDK."

"$apksigner_path" verify --verbose --print-certs "$apk_path"
sha256sum "$apk_path"
trap - EXIT

printf '\nRelease APK: %s\n\n' "$apk_path"
printf 'Next steps:\n'
printf '  git add app/build.gradle.kts\n'
printf '  git commit -m "Release %s"\n' "$version_name"
printf '  git tag -a %s -m "Tukkateatteri %s"\n' "$release_tag" "$version_name"
printf '  git push origin main --follow-tags\n'
printf '  Upload the APK to the GitHub release for %s.\n' "$release_tag"

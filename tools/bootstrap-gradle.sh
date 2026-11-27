#!/bin/sh
# Bootstrap Gradle from the Huawei Cloud mirror without contacting GitHub.
set -eu
PROJECT_ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
GRADLE_VERSION=8.9
GRADLE_DIR="$PROJECT_ROOT/.gradle-dist/gradle-$GRADLE_VERSION"
if [ -f "$GRADLE_DIR/bin/gradle" ]; then exit 0; fi
MIRROR='https://mirrors.huaweicloud.com/gradle'
mkdir -p "$PROJECT_ROOT/.gradle-bootstrap" "$PROJECT_ROOT/.gradle-dist"
DOWNLOAD_DIR=$(mktemp -d "$PROJECT_ROOT/.gradle-bootstrap/download.XXXXXXXX")
printf '%s\n' 'Downloading Gradle 8.9 from Huawei Cloud...'
curl --fail --location --retry 3 --retry-all-errors --connect-timeout 20 --max-time 120 --output "$DOWNLOAD_DIR/gradle.sha256" "$MIRROR/gradle-$GRADLE_VERSION-bin.zip.sha256"
EXPECTED=$(tr -d '\r\n ' < "$DOWNLOAD_DIR/gradle.sha256")
case "$EXPECTED" in ''|*[!0-9a-fA-F]*) printf '%s\n' 'Invalid Gradle checksum response.' >&2; exit 1 ;; esac
if [ "${#EXPECTED}" -ne 64 ]; then printf '%s\n' 'Invalid checksum length.' >&2; exit 1; fi
curl --fail --location --retry 3 --retry-all-errors --connect-timeout 20 --max-time 1800 --output "$DOWNLOAD_DIR/gradle.zip" "$MIRROR/gradle-$GRADLE_VERSION-bin.zip"
if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$DOWNLOAD_DIR/gradle.zip"); ACTUAL=${ACTUAL%% *}
else
    ACTUAL=$(shasum -a 256 "$DOWNLOAD_DIR/gradle.zip"); ACTUAL=${ACTUAL%% *}
fi
EXPECTED=$(printf '%s' "$EXPECTED" | tr 'A-F' 'a-f')
if [ "$ACTUAL" != "$EXPECTED" ]; then printf '%s\n' 'Gradle ZIP checksum mismatch.' >&2; exit 1; fi
EXTRACT_DIR=$(mktemp -d "$PROJECT_ROOT/.gradle-dist/extract.XXXXXXXX")
unzip -q "$DOWNLOAD_DIR/gradle.zip" -d "$EXTRACT_DIR"
if [ ! -f "$EXTRACT_DIR/gradle-$GRADLE_VERSION/bin/gradle" ]; then printf '%s\n' 'Gradle archive is incomplete.' >&2; exit 1; fi
mv "$EXTRACT_DIR/gradle-$GRADLE_VERSION" "$GRADLE_DIR"
printf '%s\n' 'Gradle 8.9 downloaded from Huawei Cloud and SHA-256 verified.'

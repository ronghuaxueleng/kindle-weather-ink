#!/bin/sh
# Project-local Gradle 8.9 launcher using the Huawei Cloud distribution mirror.
set -eu
APP_HOME=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
if [ ! -f "$APP_HOME/.gradle-dist/gradle-8.9/bin/gradle" ]; then
    sh "$APP_HOME/tools/bootstrap-gradle.sh"
fi
exec sh "$APP_HOME/.gradle-dist/gradle-8.9/bin/gradle" -p "$APP_HOME" "$@"

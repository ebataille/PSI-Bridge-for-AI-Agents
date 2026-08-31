#!/usr/bin/env bash
# Aucun JDK systeme sur cette machine : on emprunte le JBR livre avec WebStorm.
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-C:\Program Files\JetBrains\WebStorm 2025.2.3\jbr}"
exec ./gradlew "$@"

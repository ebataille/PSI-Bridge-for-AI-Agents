#!/usr/bin/env bash
#
# Gradle wrapper with a JDK 21 resolved automatically.
#
# Building an IntelliJ plugin needs a JDK 21. Rather than hardcoding one machine's path in the
# versioned gradle.properties, this script honours JAVA_HOME when it is set (CI does set it), and
# otherwise borrows the JBR shipped with a locally installed JetBrains IDE - which is a complete
# JDK, so no separate install is required just to build.
set -euo pipefail

find_jdk() {
  local candidate
  for candidate in \
    "${LOCALAPPDATA:-}/Programs"/*/jbr \
    "/c/Program Files/JetBrains"/*/jbr \
    "/Applications"/*.app/Contents/jbr/Contents/Home \
    "$HOME/.jdks"/* \
    "/usr/lib/jvm"/*21* ; do
    if [ -x "$candidate/bin/java" ] || [ -x "$candidate/bin/java.exe" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

if [ -z "${JAVA_HOME:-}" ]; then
  if JAVA_HOME="$(find_jdk)"; then
    export JAVA_HOME
    echo "build.sh: using JDK at $JAVA_HOME" >&2
  else
    echo "build.sh: no JDK 21 found. Set JAVA_HOME, or install any JetBrains IDE (its bundled" >&2
    echo "          JBR is a full JDK and will be picked up automatically)." >&2
    exit 1
  fi
fi

exec ./gradlew "$@"

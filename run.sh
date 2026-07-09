#!/usr/bin/env bash
# OmniPHR reference implementation - build & run (Java 8 source/target)
# usage: ./run.sh [demo | selftest | eval [--full] [--duration s] [--seed n]]
set -e
cd "$(dirname "$0")"

if command -v javac >/dev/null 2>&1; then
    COMPILE="javac"
else
    # JRE with the jdk.compiler module (no javac launcher installed)
    COMPILE="java -m jdk.compiler/com.sun.tools.javac.Main"
fi

# on JDK 9+ pin the Java 8 API and bytecode; on JDK 8 no flag is needed
RELEASE=""
if $COMPILE --release 8 -version >/dev/null 2>&1; then
    RELEASE="--release 8"
fi

mkdir -p bin
$COMPILE $RELEASE -d bin $(find src -name "*.java")
java -cp bin br.unisinos.omniphr.Main "${@:-demo}"

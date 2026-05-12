#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
REFS="${1:-resources/references.json.gz}"
OUT="${2:-resources/index.bin}"
rm -rf out app.jar sources.txt
find src/main/java -name '*.java' > sources.txt
javac --release 25 -d out @sources.txt
jar --create --file app.jar --main-class dev.marcelovitor.rinha.RinhaBackendApplication -C out .
if [[ -n "${JAVA_BUILD_OPTS:-}" ]]; then
  read -r -a JVM_OPTS <<< "$JAVA_BUILD_OPTS"
else
  JVM_OPTS=(-Xms512m -Xmx1400m -XX:+UseSerialGC)
fi
java "${JVM_OPTS[@]}" -cp app.jar dev.marcelovitor.rinha.store.IvfBuilder "$REFS" "$OUT"
ls -lh "$OUT"

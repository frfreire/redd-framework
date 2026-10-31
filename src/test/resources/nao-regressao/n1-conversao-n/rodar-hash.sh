#!/bin/bash
# Diagnostico de hash (N1). Uso: bash rodar-hash.sh LADO PROTO N DUR SEMENTE K [TIMEOUT_S]
#   LADO = base (/tmp/opencode/base-325f4e3, SEM mvn) | atual (raiz do repositorio)
#   JVMFLAGS e TAGEXTRA opcionais no ambiente (ex.: JVMFLAGS="-XX:+UnlockExperimentalVMOptions -XX:hashCode=2")
# UMA JVM de simulacao por execucao; o chamador roda em sequencia. Sem pipes.
# Saida em /tmp/opencode/n1-hash/runs/<tag>/: csv/, log.txt, hashes.txt (normalizado como nao_regressao.py), rc.txt
set -u
LADO=$1; PROTO=$2; N=$3; DUR=$4; SEM=$5; K=$6; TMAX=${7:-900}
REPO=/home/fabricio.freire/Projetos/Arq-REDD-Framework-main
BASE=/tmp/opencode/base-325f4e3
if [ "$LADO" = base ]; then RAIZ=$BASE; else RAIZ=$REPO; fi
TAG="${LADO}-${PROTO}-${N}-${DUR}-s${SEM}-k${K}${TAGEXTRA:-}"
OUT=/tmp/opencode/n1-hash/runs/$TAG
rm -rf "$OUT/csv"
mkdir -p "$OUT/csv"
# MVPComparison vem de $RAIZ/target/classes (primeiro no classpath); test-classes do repo so fornece HashPerturbado
CP="$RAIZ/target/classes:$(cat "$RAIZ/target/cp.txt"):$REPO/target/test-classes"
cd "$OUT" || exit 3
S=$(date +%s)
# shellcheck disable=SC2086
timeout "$TMAX" java ${JVMFLAGS:-} -cp "$CP" jabs.example.HashPerturbado "$K" \
  --protocols="$PROTO" --validators="$N" --trials=1 --duration="$DUR" --seed="$SEM" \
  --output="$OUT/csv" --csv-out="$OUT/csv/mvp-results.csv" > "$OUT/log.txt" 2>&1
RC=$?
E=$(date +%s)
echo "$RC" > "$OUT/rc.txt"
cd "$OUT/csv" || exit 3
java -cp "$REPO/target/test-classes" jabs.network.p2p.EvidenciaHashes . > "$OUT/hashes.txt" 2>&1
cd "$OUT" || exit 3
H=$(sha256sum hashes.txt)
echo "$TAG rc=$RC t=$((E-S))s agg=${H:0:16} JVMFLAGS='${JVMFLAGS:-}'"

#!/bin/bash
# Wrapper do orquestrador da matriz de nao-regressao do criterio 4 (tarefa B1).
# Pre-requisito: mvn -o test-compile e target/cp.txt (mvn dependency:build-classpath).
# Uso: bash src/test/scripts/assinaturas.sh ARQUIVO_SAIDA
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
exec java -cp "$ROOT/target/test-classes:$ROOT/target/classes:$(cat "$ROOT/target/cp.txt")" \
  jabs.scenario.SignatureMatrixMain "$1"

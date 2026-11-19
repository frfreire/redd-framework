#!/bin/bash
# Memoria persistente do enxame via Ruflo (CLI). O Ruflo NAO faz spawn de
# subagentes aqui: o paralelismo vem do Task tool do OpenCode.
#
# Shim fino: a implementacao (recusa de segredos, limites, chamada ao ruflo) vive
# em scripts/enxame/memoria.py. No Windows use diretamente:
#   python3 scripts/enxame/memoria.py <subcomando> ...
# (o `bash` do PATH e o do WSL e nao enxerga npx/ruflo do Windows).
#
# Uso:
#   scripts/enxame/memoria.sh init
#   scripts/enxame/memoria.sh store "<chave>" "<valor>"
#   scripts/enxame/memoria.sh search "<termo>"
#   scripts/enxame/memoria.sh get "<chave>"
#   scripts/enxame/memoria.sh list
#
# `store` recusa segredos e blocos grandes: so cabe resumo de decisao.

set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if command -v python3 >/dev/null 2>&1; then
    exec python3 "$AQUI/memoria.py" "$@"
elif command -v python >/dev/null 2>&1; then
    exec python "$AQUI/memoria.py" "$@"
else
    echo "[memoria] python3/python nao encontrado no PATH" >&2
    exit 1
fi

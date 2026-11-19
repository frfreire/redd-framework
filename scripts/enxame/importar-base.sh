#!/bin/bash
# Traz para ESTE repositorio as bases de redd+_blockchain (chaincode, api, rede),
# SEM segredos e SEM sobrescrever. O JABS NAO e importado: ele ja vive na raiz
# (src/main/java/jabs) e e evoluido no lugar. Importar outra copia criaria uma
# base concorrente.
# Uso: scripts/enxame/importar-base.sh [chaincode|api|rede|todos]
set -euo pipefail
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
P="${PROJETOS:-$HOME/Projetos}"

copiar() { # origem destino
    local o="$1" d="$RAIZ/$2"
    [ -d "$o" ] || { echo "[importar] origem ausente: $o" >&2; return 1; }
    if [ -e "$d" ] && [ -n "$(ls -A "$d" 2>/dev/null)" ]; then
        echo "[importar] $2 ja existe e nao esta vazio: nao sobrescrevo." >&2
        return 0
    fi
    mkdir -p "$d"
    rsync -a \
        --exclude '.git' --exclude 'target' --exclude 'output' --exclude '.idea' --exclude '.DS_Store' \
        --exclude 'organizations/peerOrganizations' --exclude 'organizations/ordererOrganizations' \
        --exclude 'organizations/fabric-ca' --exclude 'channel-artifacts/*.block' --exclude '.env' \
        --exclude '*_sk' --exclude '*.pem' --exclude '*.key' --exclude '*.crt' --exclude '*.db' \
        --exclude '__pycache__' --exclude '.enxame' --exclude 'agentdb.rvf*' \
        "$o"/ "$d"/
    echo "[importar] $o -> $2"
}

ALVO="${1:-todos}"
case "$ALVO" in chaincode|api|rede|todos) ;; jabs) echo "[importar] o JABS ja esta na raiz (src/main/java/jabs); nada a importar." >&2; exit 0 ;; *) echo "uso: $0 [chaincode|api|rede|todos]" >&2; exit 1 ;; esac
quer() { [ "$ALVO" = "todos" ] || [ "$ALVO" = "$1" ]; }

if quer chaincode; then copiar "$P/redd+_blockchain/chaincode-java" chaincode || true; fi
if quer api;       then copiar "$P/redd+_blockchain/api-gateway" api-gateway || true; fi
if quer rede; then
    copiar "$P/redd+_blockchain/docker/fabric-network" rede-fabric || true
    if [ -d "$P/redd+_blockchain/scripts/rede" ]; then
        mkdir -p "$RAIZ/scripts/rede"
        rsync -a --ignore-existing "$P/redd+_blockchain/scripts/rede/" "$RAIZ/scripts/rede/"
        echo "[importar] scripts/rede (sem sobrescrever)"
    fi
fi

# varredura final: material criptografico que tenha passado
ACHADOS="$(grep -rIl -E -- '-----BEGIN [A-Z ]*PRIVATE KEY-----' "$RAIZ" --exclude-dir=node_modules --exclude-dir=.git 2>/dev/null || true)"
if [ -n "$ACHADOS" ]; then
    echo "[importar] ALERTA: chave privada encontrada, remova antes de versionar:" >&2
    echo "$ACHADOS" >&2
    exit 4
fi
echo "[importar] concluido. Rode: python3 scripts/lint/lint-chaincode.py chaincode ; python3 scripts/lint/lint-jabs.py src/main/java"

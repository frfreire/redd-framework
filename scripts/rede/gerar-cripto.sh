#!/usr/bin/env bash
# scripts/rede/gerar-cripto.sh
#
# Gera todo o material criptografico (MSP + TLS) das 4 organizacoes
# (Orderer + Developer + Verifier + Marketplace) usando `cryptogen`
# executado DENTRO de um container fabric-tools via `docker run` com bind
# mount — nunca exige instalar os binarios do Fabric no host.
#
# IDEMPOTENCIA: a estrategia escolhida e "limpar e regenerar". Se o
# diretorio organizations/ ja existir e tiver conteudo, ele e removido e
# recriado do zero antes de rodar o cryptogen. Isso evita o cenario em que
# um cryptogen parcial/corrompido (ex.: rodado so para 1 das 4 orgs numa
# execucao anterior interrompida) fica misturado com certificados novos,
# o que causaria erros confusos de assinatura/TLS mais adiante no
# criar-canal.sh. O custo e que toda vez que este script roda, qualquer
# container que já esteja de pé com os certificados antigos monta
# material "velho" — por isso o fluxo recomendado e sempre:
#   gerar-cripto.sh  ->  docker compose down -v (se já havia rede de pé)
#   ->  docker compose up -d  ->  criar-canal.sh
#
# Re-executar este script é seguro: ele sempre produz o mesmo resultado
# determinístico de topologia (mesmos hostnames/MSPs), apenas com pares de
# chaves novos a cada regeneração.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_DIR="$(cd "${SCRIPT_DIR}/../../rede-fabric" && pwd)"
ORG_DIR="${NETWORK_DIR}/organizations"
CRYPTOGEN_DIR="${ORG_DIR}/cryptogen"
CRYPTOGEN_IMAGE="mirror.gcr.io/hyperledger/fabric-tools:2.5"

echo "==> Diretorio de organizacoes: ${ORG_DIR}"

if [ -d "${ORG_DIR}/ordererOrganizations" ] || [ -d "${ORG_DIR}/peerOrganizations" ]; then
  echo "==> Material criptografico existente encontrado — limpando antes de regenerar (ver nota de idempotencia no cabecalho deste script)."
  # Execucoes antigas deste script geravam arquivos com dono root (cryptogen
  # rodava como root no container); se o rm direto falhar, remove via container.
  if ! rm -rf "${ORG_DIR}/ordererOrganizations" "${ORG_DIR}/peerOrganizations" 2>/dev/null; then
    echo "==> Arquivos com outro dono; removendo via container ${CRYPTOGEN_IMAGE}."
    docker run --rm -v "${ORG_DIR}:/workspace/organizations" "${CRYPTOGEN_IMAGE}" \
      bash -c "rm -rf /workspace/organizations/ordererOrganizations /workspace/organizations/peerOrganizations"
  fi
fi

run_cryptogen() {
  local config_file="$1"
  echo "==> cryptogen generate --config=cryptogen/${config_file}"
  # --user: arquivos gerados pertencem ao usuario do host (reexecucao sem root).
  docker run --rm --user "$(id -u):$(id -g)" \
    -v "${ORG_DIR}:/workspace/organizations" \
    -w /workspace/organizations \
    "${CRYPTOGEN_IMAGE}" \
    cryptogen generate \
      --config="/workspace/organizations/cryptogen/${config_file}" \
      --output="/workspace/organizations"
}

run_cryptogen "crypto-config-orderer.yaml"
run_cryptogen "crypto-config-developer.yaml"
run_cryptogen "crypto-config-verifier.yaml"
run_cryptogen "crypto-config-marketplace.yaml"

echo "==> Ajustando permissões (containers Fabric rodam como usuário não-root 1000:1000 em algumas imagens; cryptogen roda como root dentro do container)."
docker run --rm --user "$(id -u):$(id -g)" \
  -v "${ORG_DIR}:/workspace/organizations" \
  "${CRYPTOGEN_IMAGE}" \
  bash -c "chmod -R a+rX /workspace/organizations"

echo "==> Material criptografico gerado com sucesso em: ${ORG_DIR}"
echo "    - ordererOrganizations/redd.network"
echo "    - peerOrganizations/developer.redd.network"
echo "    - peerOrganizations/verifier.redd.network"
echo "    - peerOrganizations/marketplace.redd.network"

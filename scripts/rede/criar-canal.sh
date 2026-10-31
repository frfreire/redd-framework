#!/usr/bin/env bash
# scripts/rede/criar-canal.sh
#
# Cria o bloco genesis do canal RedChannel (via configtxgen, sem system
# channel) e faz com que o orderer e os 3 peers entrem no canal, usando
# o container `cli` (fabric-tools) já de pé via docker-compose (acessado
# com `docker exec`, pois ele está na mesma rede `redd-network` dos
# peers/orderer).
#
# Pré-requisito: docker compose -f rede-fabric/docker-compose.yaml
# up -d já executado e todos os containers rodando.
set -euo pipefail

CHANNEL_NAME="redchannel"
CLI_CONTAINER="cli"
ORDERER_ADDR="orderer.redd.network:7050"
ORDERER_ADMIN_ADDR="orderer.redd.network:7053"
ORDERER_CA="/workspace/organizations/ordererOrganizations/redd.network/orderers/orderer.redd.network/tls/ca.crt"

cli_exec() {
  docker exec "${CLI_CONTAINER}" bash -c "$1"
}

echo "==> Verificando se o container '${CLI_CONTAINER}' está rodando..."
if ! docker ps --format '{{.Names}}' | grep -qx "${CLI_CONTAINER}"; then
  echo "ERRO: container '${CLI_CONTAINER}' não está rodando. Execute primeiro:" >&2
  echo "  docker compose -f rede-fabric/docker-compose.yaml up -d" >&2
  exit 1
fi

echo "==> Gerando bloco genesis do canal '${CHANNEL_NAME}' via configtxgen (idempotente: sobrescreve o bloco se já existir)..."
cli_exec "mkdir -p /workspace/channel-artifacts && \
  cd /workspace && FABRIC_CFG_PATH=/workspace/configtx \
  configtxgen -profile RedChannel -channelID ${CHANNEL_NAME} \
    -outputBlock /workspace/channel-artifacts/${CHANNEL_NAME}.block"

echo "==> Fazendo o orderer entrar no canal (osnadmin channel join)..."
# Idempotente: se o orderer já tiver entrado neste canal, osnadmin retorna
# HTTP 201 na primeira vez e 405/409 em re-execuções — tratamos isso como
# não-fatal (|| true) para permitir reexecução do script.
cli_exec "osnadmin channel join \
  --channelID ${CHANNEL_NAME} \
  --config-block /workspace/channel-artifacts/${CHANNEL_NAME}.block \
  -o ${ORDERER_ADMIN_ADDR} \
  --ca-file ${ORDERER_CA} \
  --client-cert /workspace/organizations/ordererOrganizations/redd.network/orderers/orderer.redd.network/tls/server.crt \
  --client-key /workspace/organizations/ordererOrganizations/redd.network/orderers/orderer.redd.network/tls/server.key" || true

echo "==> Confirmando que o orderer está no canal..."
cli_exec "osnadmin channel list \
  -o ${ORDERER_ADMIN_ADDR} \
  --ca-file ${ORDERER_CA} \
  --client-cert /workspace/organizations/ordererOrganizations/redd.network/orderers/orderer.redd.network/tls/server.crt \
  --client-key /workspace/organizations/ordererOrganizations/redd.network/orderers/orderer.redd.network/tls/server.key"

# ---------------------------------------------------------------------
# join_peer <ORG_MSP> <org-dir> <peer-host:porta>
# Exporta as env vars CORE_PEER_* corretas para a organizacao e executa
# `peer channel join`. Idempotente: se o peer já estiver no canal,
# "peer channel join" retorna erro "ledger already exists" — tratado
# como não-fatal.
# ---------------------------------------------------------------------
join_peer() {
  local org_msp="$1"
  local org_domain="$2"
  local peer_host_port="$3"
  local peer_short="$4"

  echo "==> ${org_msp}: peer channel join (${peer_host_port})"
  cli_exec "export FABRIC_CFG_PATH=/etc/hyperledger/fabric && export CORE_PEER_TLS_ENABLED=true && \
    export CORE_PEER_LOCALMSPID=${org_msp} && \
    export CORE_PEER_TLS_ROOTCERT_FILE=/workspace/organizations/peerOrganizations/${org_domain}/peers/${peer_short}.${org_domain}/tls/ca.crt && \
    export CORE_PEER_MSPCONFIGPATH=/workspace/organizations/peerOrganizations/${org_domain}/users/Admin@${org_domain}/msp && \
    export CORE_PEER_ADDRESS=${peer_host_port} && \
    peer channel join -b /workspace/channel-artifacts/${CHANNEL_NAME}.block" || \
    echo "    (aviso: join pode ter falhado por já existir — verifique com 'peer channel list' abaixo)"

  echo "==> ${org_msp}: peer channel list (confirmação)"
  cli_exec "export FABRIC_CFG_PATH=/etc/hyperledger/fabric && export CORE_PEER_TLS_ENABLED=true && \
    export CORE_PEER_LOCALMSPID=${org_msp} && \
    export CORE_PEER_TLS_ROOTCERT_FILE=/workspace/organizations/peerOrganizations/${org_domain}/peers/${peer_short}.${org_domain}/tls/ca.crt && \
    export CORE_PEER_MSPCONFIGPATH=/workspace/organizations/peerOrganizations/${org_domain}/users/Admin@${org_domain}/msp && \
    export CORE_PEER_ADDRESS=${peer_host_port} && \
    peer channel list"
}

join_peer "DeveloperMSP"   "developer.redd.network"   "peer0.developer.redd.network:7051"   "peer0"
join_peer "VerifierMSP"    "verifier.redd.network"    "peer0.verifier.redd.network:8051"    "peer0"
join_peer "MarketplaceMSP" "marketplace.redd.network" "peer0.marketplace.redd.network:9051" "peer0"

echo "==> Canal '${CHANNEL_NAME}' criado e os 3 peers confirmaram join (ver 'peer channel list' acima)."

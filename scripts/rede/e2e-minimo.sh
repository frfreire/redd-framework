#!/usr/bin/env bash
# scripts/rede/e2e-minimo.sh
#
# Prova ponta a ponta minima com a rede de pe e o chaincode commitado:
#   1. Developer registra um projeto (registrarProjeto), endossado por
#      Developer+Verifier (politica AND do chaincode), esperando o commit.
#   2. Marketplace consulta o projeto (consultarProjeto) no proprio peer:
#      prova que o estado foi replicado e que o chaincode responde.
#   3. Controle negativo: Marketplace tenta registrarProjeto e DEVE ser
#      recusado pelo contrato (INV de papeis: so DeveloperMSP registra).
# Reexecutavel: o id do projeto e unico por execucao (sufixo de timestamp).
# Pre-requisito: rede de pe + deploy-chaincode.sh concluido.
set -euo pipefail

CHANNEL="redchannel"
CC="redd-creditos"
CLI="cli"
O="/workspace/organizations"
ORD_CA="${O}/ordererOrganizations/redd.network/orderers/orderer.redd.network/tls/ca.crt"
DEV_TLS="${O}/peerOrganizations/developer.redd.network/peers/peer0.developer.redd.network/tls/ca.crt"
VER_TLS="${O}/peerOrganizations/verifier.redd.network/peers/peer0.verifier.redd.network/tls/ca.crt"
ID="proj-e2e-$(date +%s)"

org_env() { # <MSP> <dominio> <host:porta>
  echo "export FABRIC_CFG_PATH=/etc/hyperledger/fabric CORE_PEER_TLS_ENABLED=true CORE_PEER_LOCALMSPID=$1 \
CORE_PEER_TLS_ROOTCERT_FILE=${O}/peerOrganizations/$2/peers/peer0.$2/tls/ca.crt \
CORE_PEER_MSPCONFIGPATH=${O}/peerOrganizations/$2/users/Admin@$2/msp CORE_PEER_ADDRESS=$3"
}
DEV="$(org_env DeveloperMSP developer.redd.network peer0.developer.redd.network:7051)"
MKT="$(org_env MarketplaceMSP marketplace.redd.network peer0.marketplace.redd.network:9051)"

invoke() { # <env> <json>
  docker exec "${CLI}" bash -c "$1; peer chaincode invoke -o orderer.redd.network:7050 --tls --cafile ${ORD_CA} \
    -C ${CHANNEL} -n ${CC} \
    --peerAddresses peer0.developer.redd.network:7051 --tlsRootCertFiles ${DEV_TLS} \
    --peerAddresses peer0.verifier.redd.network:8051 --tlsRootCertFiles ${VER_TLS} \
    --waitForEvent -c '$2' 2>&1"
}

echo "==> 1/3 registrarProjeto (${ID}) como DeveloperMSP"
invoke "${DEV}" "{\"function\":\"registrarProjeto\",\"Args\":[\"${ID}\",\"Projeto E2E\",\"1500.5\",\"VM0007\"]}" | tail -n 3

echo "==> 2/3 consultarProjeto (${ID}) como MarketplaceMSP, no peer0.marketplace"
RES="$(docker exec "${CLI}" bash -c "${MKT}; peer chaincode query -C ${CHANNEL} -n ${CC} -c '{\"function\":\"consultarProjeto\",\"Args\":[\"${ID}\"]}' 2>&1")"
echo "${RES}"
echo "${RES}" | grep -q "\"id\":\"${ID}\"" || { echo "FALHA: consulta nao retornou o projeto" >&2; exit 1; }
echo "${RES}" | grep -q '"status":"REGISTRADO"' || { echo "FALHA: status inesperado" >&2; exit 1; }

echo "==> 3/3 controle negativo: Marketplace tenta registrarProjeto (deve ser recusado)"
NEG="$(invoke "${MKT}" "{\"function\":\"registrarProjeto\",\"Args\":[\"${ID}-neg\",\"Nao deve\",\"10\",\"VM0007\"]}" || true)"
echo "${NEG}" | tail -n 2
echo "${NEG}" | grep -q "Apenas identidades da organizacao proponente" || { echo "FALHA: contrato nao recusou o Marketplace" >&2; exit 1; }

echo "==> E2E minimo OK (registrar -> consultar; recusa de papel indevido confirmada)."

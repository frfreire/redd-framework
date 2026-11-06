#!/usr/bin/env bash
# scripts/rede/deploy-chaincode.sh
#
# Build (Docker image) -> package (por organizacao) -> install (nos 3
# peers) -> approve (nas 3 orgs) -> commit -> start dos containers CCaaS
# do chaincode Java do demo REDD+ no canal `redchannel`.
#
# DECISAO DE ARQUITETURA — Chaincode-as-a-Service (CCaaS), nao builder
# Docker nativo do Fabric:
#
# O builder Docker NATIVO do Fabric 2.5 (que compila/empacota o chaincode
# dentro do proprio peer, disparando "docker build" via socket montado) e
# INCOMPATIVEL com o Docker Engine 29.x deste host (falha de build por
# incompatibilidade de protocolo/formato entre o builder embutido no peer
# e o daemon do host). A solucao adotada e CCaaS (feature nativa do
# fabric-chaincode-shim >= 2.4): o chaincode roda como um container HTTP/
# gRPC *independente*, que o peer apenas invoca via rede
# (CHAINCODE_SERVER_ADDRESS), sem pedir ao Docker Engine para construir
# uma imagem on-the-fly. Ver chaincode/Dockerfile para o detalhe de
# como o modo servidor e ativado em runtime (ContractRouter.main() decide
# sozinho, via env vars, se inicia o NettyChaincodeServer).
#
# Esta decisao foi validada na pratica: o fluxo completo abaixo
# (package/install/approve/commit) executa sem o erro de encoding UTF-8
# que ocorria com o builder legado, e o ciclo de vida completo do
# chaincode (registrarProjeto, submeterRelatorioMRV,
# verificarRelatorioMRV, emitirCredito, transferirCredito,
# aposentarCredito, consultas) foi testado end-to-end com sucesso via
# `peer chaincode invoke/query` contra os containers CCaaS iniciados por
# este script.
#
# BUGS REAIS ENCONTRADOS E CORRIGIDOS NO CHAINCODE DURANTE A VALIDACAO
# DESTE FLUXO (ver docs/decisoes-arquitetura.md para o detalhe completo):
#   1. maven-shade-plugin sem ServicesResourceTransformer sobrescrevia
#      META-INF/services/io.grpc.NameResolverProvider de varios jars
#      gRPC, deixando so o provider "uds" (unix socket) e quebrando a
#      conexao reversa do shim ao peer (CORE_PEER_ADDRESS). Corrigido em
#      chaincode/pom.xml.
#   2. Classes de modelo sem @DataType/@Property do
#      fabric-chaincode-shim causavam NullPointerException no
#      JSONTransactionSerializer ao serializar o retorno de qualquer
#      funcao de transacao -- so detectavel em invocacao real via peer,
#      nao em testes unitarios Mockito. Corrigido adicionando as
#      anotacoes nas 4 classes de modelo.
#   3. Funcoes de leitura com retorno List<T> (nao array) causavam o
#      mesmo NPE por type erasure (method.getReturnType() so devolve
#      java.util.List sem o tipo parametrizado). Corrigido trocando
#      retorno para array (T[]), que o shim trata nativamente.
#   4. listarCreditosPorProjeto filtrava pelo campo de aplicacao "id"
#      (sem prefixo) em vez do campo reservado "_id" do CouchDB (chave
#      real do estado, com prefixo CREDITO_) -- query sempre retornava
#      vazia. Corrigido o selector e o indice
#      META-INF/statedb/couchdb/indexes/indexIdProjeto.json.
#
# O PACOTE CCaaS instalado precisa necessariamente incluir
# META-INF/statedb/couchdb/indexes (nao so connection.json) -- sem isso,
# os indices CouchDB nunca sao criados e queries rich (getQueryResult)
# ficam sem indice de suporte, gerando warning de performance do CouchDB
# (ou, em bases maiores, timeout).
#
# POLÍTICA DE ENDOSSO DO CHAINCODE — decisão e justificativa:
#
#   Escolhida: AND('DeveloperMSP.peer','VerifierMSP.peer')
#
# O Fabric aplica a política de endosso de um chaincode de forma UNIFORME
# a todas as transações daquele chaincode — não é possível, nativamente,
# ter uma policy por função (ex.: "emitirCredito exige as 3 orgs" mas
# "registrarProjeto exige só 2"). Isso é uma LIMITAÇÃO CONHECIDA do demo:
# o ideal de negócio seria exigir as 3 organizações (Developer, Verifier
# E Marketplace) especificamente para emissão/transferência de crédito,
# mas apenas Developer+Verifier para registro de projeto e submissão de
# MRV (o Marketplace não deveria precisar endossar um dado de MRV que
# ainda nem foi verificado).
#
# Decidimos usar a MESMA policy do canal (AND(DeveloperMSP.peer,
# VerifierMSP.peer), 2 de 3, SEM Marketplace) em vez de exigir as 3 orgs
# para TODAS as transações, pelos seguintes motivos:
#   1. Marketplace é comprador/mercado — não tem papel técnico na
#      validação de dados de MRV ou na lógica de emissão, apenas na
#      transferência/aposentadoria (que já está sob controle de posse do
#      asset, verificado em código, não via endosso de infraestrutura).
#   2. Exigir Marketplace para TODAS as transações (incluindo registro de
#      projeto, que acontece antes de qualquer comprador existir) criaria
#      uma dependência de disponibilidade de uma organização que não tem
#      papel de negócio naquele momento do ciclo de vida.
#   3. A regra de negócio crítica ("nenhuma organização decide sozinha a
#      validade de um crédito") já é satisfeita por AND(Developer,
#      Verifier) — a vedação de ação unilateral do Developer é o que
#      importa, e isso está garantido.
#
# Alternativa descartada: OutOf(2, 'DeveloperMSP.peer','VerifierMSP.peer',
# 'MarketplaceMSP.peer') permitiria Verifier+Marketplace endossarem uma
# transação SEM a participação do Developer, o que não faz sentido de
# negócio (o proponente do projeto deveria sempre estar envolvido em
# qualquer escrita que afete seu próprio projeto/crédito).
#
# Se, no futuro, a separação por função for necessária de fato, a solução
# correta no Fabric é dividir em múltiplos chaincodes (ex.:
# "redd-mrv" com policy AND(Dev,Verifier) e "redd-marketplace" com policy
# AND(Dev,Verifier,Marketplace)), não tentar simular isso com uma única
# policy — por ora, fora do escopo deste demo.
#
# IDEMPOTÊNCIA: este script detecta a sequence já commitada no canal (via
# querycommitted) e usa sequence+1 automaticamente se o chaincode já
# estiver commitado (upgrade) ou sequence=1 se for a primeira vez.
set -euo pipefail

# GATE DO LOOP 1 (obrigatorio, ver skill fabric-rede-infra): nao instala
# chaincode sem veredito APROVADO ou GAP_NAO_BLOQUEANTE registrado em
# .ia/rodadas/loop1-chaincode.jsonl. Codigo de saida != 0 aborta.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if ! python3 "${REPO_ROOT}/scripts/enxame/registrar-rodada.py" --verificar-gate loop1-chaincode; then
  echo "ERRO: gate do loop 1 fechado; chaincode NAO sera instalado. Avise o orquestrador." >&2
  exit 30
fi

CHANNEL_NAME="redchannel"
CLI_CONTAINER="cli"
CC_NAME="redd-creditos"
CC_VERSION="1.0"
CC_IMAGE="redd-chaincode-ccaas:1.0"
CC_SERVER_PORT="9999"
CC_ENDORSEMENT_POLICY="AND('DeveloperMSP.peer','VerifierMSP.peer')"
ORDERER_ADDR="orderer.redd.network:7050"
ORDERER_CA="/workspace/organizations/ordererOrganizations/redd.network/orderers/orderer.redd.network/tls/ca.crt"
HOST_CHAINCODE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../chaincode" && pwd)"
HOST_COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../rede-fabric" && pwd)"
HOST_COMPOSE_FILE="${HOST_COMPOSE_DIR}/docker-compose.yaml"
HOST_ENV_FILE="${HOST_COMPOSE_DIR}/.env"
CLI_PKG_DIR="/workspace/ccaas-pkg"
NETWORK_NAME="redd-network"

cli_exec() {
  docker exec "${CLI_CONTAINER}" bash -c "$1"
}

echo "==> Verificando se o container '${CLI_CONTAINER}' está rodando..."
if ! docker ps --format '{{.Names}}' | grep -qx "${CLI_CONTAINER}"; then
  echo "ERRO: container '${CLI_CONTAINER}' não está rodando. Execute primeiro:" >&2
  echo "  docker compose -f rede-fabric/docker-compose.yaml up -d" >&2
  exit 1
fi

# ---------------------------------------------------------------------
# env_for_org <ORG_MSP> <org-dir> <peer-host:porta>
# ---------------------------------------------------------------------
env_for_org() {
  local org_msp="$1"
  local org_domain="$2"
  local peer_host_port="$3"
  echo "export FABRIC_CFG_PATH=/etc/hyperledger/fabric && export CORE_PEER_TLS_ENABLED=true && \
    export CORE_PEER_LOCALMSPID=${org_msp} && \
    export CORE_PEER_TLS_ROOTCERT_FILE=/workspace/organizations/peerOrganizations/${org_domain}/peers/peer0.${org_domain}/tls/ca.crt && \
    export CORE_PEER_MSPCONFIGPATH=/workspace/organizations/peerOrganizations/${org_domain}/users/Admin@${org_domain}/msp && \
    export CORE_PEER_ADDRESS=${peer_host_port}"
}

DEV_ENV="$(env_for_org DeveloperMSP developer.redd.network peer0.developer.redd.network:7051)"
VER_ENV="$(env_for_org VerifierMSP verifier.redd.network peer0.verifier.redd.network:8051)"
MKT_ENV="$(env_for_org MarketplaceMSP marketplace.redd.network peer0.marketplace.redd.network:9051)"

echo "==> 1/7 — Buildando imagem Docker do chaincode CCaaS (${CC_IMAGE})..."
docker build -t "${CC_IMAGE}" "${HOST_CHAINCODE_DIR}"

echo "==> 2/7 — Determinando sequence (1 = primeira instalação, N+1 = upgrade)..."
EXISTING_SEQUENCE="$(docker exec "${CLI_CONTAINER}" bash -c "${DEV_ENV} && peer lifecycle chaincode querycommitted --channelID ${CHANNEL_NAME} --name ${CC_NAME} 2>/dev/null" \
  | sed -n 's/^Version: .*, Sequence: \([0-9]*\),.*/\1/p' | head -n1 || true)"
if [ -n "${EXISTING_SEQUENCE}" ]; then
  CC_SEQUENCE=$((EXISTING_SEQUENCE + 1))
  echo "    Chaincode já commitado com sequence=${EXISTING_SEQUENCE}; fazendo upgrade para sequence=${CC_SEQUENCE}."
else
  CC_SEQUENCE="1"
  echo "    Nenhum commit anterior encontrado; usando sequence=${CC_SEQUENCE}."
fi
CC_LABEL="${CC_NAME}_${CC_VERSION}"

# IDEMPOTENCIA: se a MESMA versao ja esta commitada, nao reinstala nem sobe a
# sequence (o hash do pacote CCaaS muda a cada empacotamento por causa do
# mtime do tar, entao reexecutar geraria um install/sequence novos sem mudanca
# real). Apenas garante que os 3 containers CCaaS estao no ar com o .env atual.
# Para forcar um upgrade: CC_FORCE_UPGRADE=1, ou aumente CC_VERSION.
COMMITTED_VERSION="$(docker exec "${CLI_CONTAINER}" bash -c "${DEV_ENV} && peer lifecycle chaincode querycommitted --channelID ${CHANNEL_NAME} --name ${CC_NAME} 2>/dev/null" \
  | sed -n 's/^Version: \([^,]*\), Sequence: .*/\1/p' | head -n1 || true)"
if [ -n "${EXISTING_SEQUENCE}" ] && [ "${COMMITTED_VERSION}" = "${CC_VERSION}" ] && [ "${CC_FORCE_UPGRADE:-0}" != "1" ]; then
  echo "==> '${CC_NAME}' v${COMMITTED_VERSION} ja commitado (sequence ${EXISTING_SEQUENCE}); nada a instalar/aprovar."
  if [ -f "${HOST_ENV_FILE}" ] && ! grep -q '=placeholder' "${HOST_ENV_FILE}"; then
    docker compose -f "${HOST_COMPOSE_FILE}" --env-file "${HOST_ENV_FILE}" --profile ccaas \
      up -d chaincode-ccaas-developer chaincode-ccaas-verifier chaincode-ccaas-marketplace
    echo "==> Containers CCaaS garantidos no ar. Use CC_FORCE_UPGRADE=1 (ou nova CC_VERSION) para forcar upgrade."
    exit 0
  fi
  echo "ERRO: chaincode commitado mas ${HOST_ENV_FILE} ausente/placeholder (PACKAGE_IDs perdidos)." >&2
  echo "      Rode com CC_FORCE_UPGRADE=1 para reinstalar com sequence+1." >&2
  exit 1
fi

echo "==> 3/7 — Empacotando chaincode CCaaS por organização (connection.json + META-INF)..."
cli_exec "rm -rf ${CLI_PKG_DIR} && mkdir -p ${CLI_PKG_DIR}"
for ORG in developer verifier marketplace; do
  cli_exec "
    set -e
    rm -rf ${CLI_PKG_DIR}/${ORG}-code
    mkdir -p ${CLI_PKG_DIR}/${ORG}-code/META-INF
    cp -r /workspace/chaincode/META-INF/* ${CLI_PKG_DIR}/${ORG}-code/META-INF/
    echo '{\"address\":\"redd-chaincode-ccaas-${ORG}:${CC_SERVER_PORT}\",\"dial_timeout\":\"10s\",\"tls_required\":false}' \
      > ${CLI_PKG_DIR}/${ORG}-code/connection.json
    tar -C ${CLI_PKG_DIR}/${ORG}-code -czf ${CLI_PKG_DIR}/code-${ORG}.tar.gz connection.json META-INF
    echo '{\"type\":\"ccaas\",\"label\":\"${CC_LABEL}\"}' > ${CLI_PKG_DIR}/metadata-${ORG}.json
    tar -C ${CLI_PKG_DIR} -czf ${CLI_PKG_DIR}/${CC_NAME}_${ORG}.tar.gz \
      --transform='s/metadata-${ORG}.json/metadata.json/' --transform='s/code-${ORG}.tar.gz/code.tar.gz/' \
      metadata-${ORG}.json code-${ORG}.tar.gz
  "
done

echo "==> 4/7 — Instalando chaincode nos 3 peers..."
PKG_DEV="$(docker exec "${CLI_CONTAINER}" bash -c "${DEV_ENV} && peer lifecycle chaincode install ${CLI_PKG_DIR}/${CC_NAME}_developer.tar.gz 2>&1" \
  | sed -n 's/^.*Chaincode code package identifier: \(.*\)$/\1/p' | tail -n1)"
PKG_VER="$(docker exec "${CLI_CONTAINER}" bash -c "${VER_ENV} && peer lifecycle chaincode install ${CLI_PKG_DIR}/${CC_NAME}_verifier.tar.gz 2>&1" \
  | sed -n 's/^.*Chaincode code package identifier: \(.*\)$/\1/p' | tail -n1)"
PKG_MKT="$(docker exec "${CLI_CONTAINER}" bash -c "${MKT_ENV} && peer lifecycle chaincode install ${CLI_PKG_DIR}/${CC_NAME}_marketplace.tar.gz 2>&1" \
  | sed -n 's/^.*Chaincode code package identifier: \(.*\)$/\1/p' | tail -n1)"

if [ -z "${PKG_DEV}" ] || [ -z "${PKG_VER}" ] || [ -z "${PKG_MKT}" ]; then
  echo "ERRO: não foi possível determinar algum dos PACKAGE_IDs instalados." >&2
  echo "  developer=${PKG_DEV} verifier=${PKG_VER} marketplace=${PKG_MKT}" >&2
  exit 1
fi
echo "    PACKAGE_ID developer=${PKG_DEV}"
echo "    PACKAGE_ID verifier=${PKG_VER}"
echo "    PACKAGE_ID marketplace=${PKG_MKT}"

echo "==> 5/7 — Aprovando definição do chaincode nas 3 organizações (sequence=${CC_SEQUENCE})..."
approve() {
  local env_vars="$1"
  local package_id="$2"
  cli_exec "${env_vars} && \
    peer lifecycle chaincode approveformyorg \
      -o ${ORDERER_ADDR} --tls --cafile ${ORDERER_CA} \
      --channelID ${CHANNEL_NAME} \
      --name ${CC_NAME} --version ${CC_VERSION} --sequence ${CC_SEQUENCE} \
      --package-id ${package_id} \
      --signature-policy \"${CC_ENDORSEMENT_POLICY}\""
}
approve "${DEV_ENV}" "${PKG_DEV}"
approve "${VER_ENV}" "${PKG_VER}"
approve "${MKT_ENV}" "${PKG_MKT}"

echo "==> Verificando prontidão para commit (checkcommitreadiness)..."
cli_exec "${DEV_ENV} && \
  peer lifecycle chaincode checkcommitreadiness \
    -o ${ORDERER_ADDR} --tls --cafile ${ORDERER_CA} \
    --channelID ${CHANNEL_NAME} \
    --name ${CC_NAME} --version ${CC_VERSION} --sequence ${CC_SEQUENCE} \
    --signature-policy \"${CC_ENDORSEMENT_POLICY}\" \
    --output json"

echo "==> 6/7 — Fazendo commit da definição do chaincode no canal..."
cli_exec "${DEV_ENV} && \
  peer lifecycle chaincode commit \
    -o ${ORDERER_ADDR} --tls --cafile ${ORDERER_CA} \
    --channelID ${CHANNEL_NAME} \
    --name ${CC_NAME} --version ${CC_VERSION} --sequence ${CC_SEQUENCE} \
    --signature-policy \"${CC_ENDORSEMENT_POLICY}\" \
    --peerAddresses peer0.developer.redd.network:7051 \
      --tlsRootCertFiles /workspace/organizations/peerOrganizations/developer.redd.network/peers/peer0.developer.redd.network/tls/ca.crt \
    --peerAddresses peer0.verifier.redd.network:8051 \
      --tlsRootCertFiles /workspace/organizations/peerOrganizations/verifier.redd.network/peers/peer0.verifier.redd.network/tls/ca.crt"

echo "==> Confirmando commit (querycommitted)..."
cli_exec "${DEV_ENV} && \
  peer lifecycle chaincode querycommitted --channelID ${CHANNEL_NAME} --name ${CC_NAME}"

echo "==> 7/7 — (Re)iniciando os containers CCaaS (via docker compose --profile ccaas) com os PACKAGE_IDs atuais..."
cat > "${HOST_ENV_FILE}" <<EOF
# Gerado automaticamente por scripts/rede/deploy-chaincode.sh — NAO EDITAR
# MANUALMENTE (sera sobrescrito na proxima execucao do script). Consumido
# pelos servicos chaincode-ccaas-* do profile "ccaas" em docker-compose.yaml.
CCAAS_PACKAGE_ID_DEVELOPER=${PKG_DEV}
CCAAS_PACKAGE_ID_VERIFIER=${PKG_VER}
CCAAS_PACKAGE_ID_MARKETPLACE=${PKG_MKT}
EOF
echo "    Escrito ${HOST_ENV_FILE} com os PACKAGE_IDs atuais."

docker compose -f "${HOST_COMPOSE_FILE}" --env-file "${HOST_ENV_FILE}" --profile ccaas \
  up -d --build chaincode-ccaas-developer chaincode-ccaas-verifier chaincode-ccaas-marketplace

echo ""
echo "==> Chaincode '${CC_NAME}' v${CC_VERSION} (sequence ${CC_SEQUENCE}) commitado no canal '${CHANNEL_NAME}'."
echo "    Policy de endosso efetiva: ${CC_ENDORSEMENT_POLICY} (ver justificativa no cabeçalho deste script)."
echo "    Containers CCaaS ativos: redd-chaincode-ccaas-{developer,verifier,marketplace} (porta ${CC_SERVER_PORT})."
echo "    Teste rápido:"
echo "      docker exec ${CLI_CONTAINER} bash -c \"${DEV_ENV} && peer chaincode query -C ${CHANNEL_NAME} -n ${CC_NAME} -c '{\\\"function\\\":\\\"consultarProjeto\\\",\\\"Args\\\":[\\\"proj-001\\\"]}'\""

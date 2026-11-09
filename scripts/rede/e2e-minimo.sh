#!/usr/bin/env bash
# scripts/rede/e2e-minimo.sh
#
# Prova ponta a ponta do fluxo REAL do contrato atual (RedContract.java), com a
# rede de pe e o chaincode commitado (scripts/rede/deploy-chaincode.sh).
#
# Assinaturas exercitadas (argumentos na ordem do contrato):
#   registrarProjeto(id, nome, areaHectares, metodologia)                [Developer]
#   submeterRelatorioMRV(idRel, idProj, inicioISO, fimISO,
#                        toneladasCO2eEstimadas:long, hashEvidencia)     [Developer]
#   verificarRelatorioMRV(idRel, aprovado, toneladasVerificadas:long,
#                         justificativa)                                 [Verifier]
#   emitirCredito(idCredito, idRel, toneladasCO2e:long)                  [Developer]
#   transferirCredito(idCredito, novoProprietarioMspId)                  [proprietario]
#   aposentarCredito(idCredito, motivo, beneficiario=sha256:<64 hex>)    [proprietario]
#   listarCreditosPorProjeto(idProj, pageSize, bookmark)                 [consulta]
#   historicoCredito(idCredito, offset, pageSize)                        [consulta]
# Unidade de toneladas: kgCO2e inteiro (long); fracao e recusada pelo shim.
#
# Cada rejeicao esperada confere o motivo na mensagem do contrato (nao basta
# "falhou") e, quando aplicavel, que o estado nao mudou. O resumo final lista
# aceitas e rejeitadas. Reexecutavel: ids unicos por execucao (timestamp).
# Politica de endosso AND(Developer,Verifier): toda invocacao e endossada pelos
# dois peers; a identidade que ASSINA a proposta e a do invocador (org_env).
set -uo pipefail

CHANNEL="redchannel"
CC="redd-creditos"
CLI="cli"
O="/workspace/organizations"
ORD_CA="${O}/ordererOrganizations/redd.network/orderers/orderer.redd.network/tls/ca.crt"
DEV_TLS="${O}/peerOrganizations/developer.redd.network/peers/peer0.developer.redd.network/tls/ca.crt"
VER_TLS="${O}/peerOrganizations/verifier.redd.network/peers/peer0.verifier.redd.network/tls/ca.crt"
SUF="$(date +%s)"
PROJ="proj-e2e-${SUF}"
REL="rel-e2e-${SUF}"
CRA="cred-e2e-${SUF}-a"   # sera emitido, transferido e aposentado
CRB="cred-e2e-${SUF}-b"   # sera emitido (segunda emissao parcial)
# Digest de beneficiario FICTICIO de teste (sha256 de um texto de teste, nao de pessoa real).
BENEF="sha256:$(printf 'beneficiario-e2e-%s' "${SUF}" | sha256sum | cut -d' ' -f1)"

ACEITAS=0; REJEITADAS_OK=0; FALHAS=0
RESUMO=()

org_env() { # <MSP> <dominio> <host:porta>
  echo "export FABRIC_CFG_PATH=/etc/hyperledger/fabric CORE_PEER_TLS_ENABLED=true CORE_PEER_LOCALMSPID=$1 \
CORE_PEER_TLS_ROOTCERT_FILE=${O}/peerOrganizations/$2/peers/peer0.$2/tls/ca.crt \
CORE_PEER_MSPCONFIGPATH=${O}/peerOrganizations/$2/users/Admin@$2/msp CORE_PEER_ADDRESS=$3"
}
DEV="$(org_env DeveloperMSP developer.redd.network peer0.developer.redd.network:7051)"
VER="$(org_env VerifierMSP verifier.redd.network peer0.verifier.redd.network:8051)"
MKT="$(org_env MarketplaceMSP marketplace.redd.network peer0.marketplace.redd.network:9051)"

# invoke <env> <json>: saida combinada (stdout+stderr); codigo de saida do peer.
invoke() {
  docker exec "${CLI}" bash -c "$1; peer chaincode invoke -o orderer.redd.network:7050 --tls --cafile ${ORD_CA} \
    -C ${CHANNEL} -n ${CC} \
    --peerAddresses peer0.developer.redd.network:7051 --tlsRootCertFiles ${DEV_TLS} \
    --peerAddresses peer0.verifier.redd.network:8051 --tlsRootCertFiles ${VER_TLS} \
    --waitForEvent -c '$2' 2>&1"
}
# query <env> <json>
query() {
  docker exec "${CLI}" bash -c "$1; peer chaincode query -C ${CHANNEL} -n ${CC} -c '$2' 2>&1"
}
# query_retry <env> <json> <regex>: o peer consultado pode estar 1-2 blocos atras.
query_retry() {
  local out i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    out="$(query "$1" "$2")"
    if echo "${out}" | grep -Eq -- "$3"; then echo "${out}"; return 0; fi
    sleep 1
  done
  echo "${out}"; return 1
}

passo_ok()  { ACEITAS=$((ACEITAS+1)); RESUMO+=("ACEITA    $1"); echo "    [OK] $1"; }
rej_ok()    { REJEITADAS_OK=$((REJEITADAS_OK+1)); RESUMO+=("REJEITADA $1  (motivo conferido)"); echo "    [OK] rejeitada como esperado: $1"; }
falhou()    { FALHAS=$((FALHAS+1)); RESUMO+=("FALHA     $1"); echo "    [FALHA] $1" >&2; [ -n "${2:-}" ] && echo "${2}" | tail -n 4 | cut -c1-300 >&2; }

# aceita <rotulo> <env> <json>: a transacao DEVE ser confirmada (VALID).
aceita() {
  local out; out="$(invoke "$2" "$3")"
  if echo "${out}" | grep -q "Chaincode invoke successful" && echo "${out}" | grep -q "committed with status (VALID)"; then
    passo_ok "$1"
  else
    falhou "$1" "${out}"
  fi
}
# rejeita <rotulo> <regex-do-motivo> <env> <json>: NAO pode ser confirmada e a
# mensagem deve casar o motivo. Falha no endosso => nada foi enviado ao orderer.
rejeita() {
  local out; out="$(invoke "$3" "$4")"
  if echo "${out}" | grep -q "committed with status (VALID)"; then
    falhou "$1 (FOI ACEITA; deveria ser recusada)" "${out}"
  elif echo "${out}" | grep -Eq -- "$2"; then
    rej_ok "$1"
  else
    falhou "$1 (recusada, mas por motivo diferente do esperado: /$2/)" "${out}"
  fi
}
# confere <rotulo> <env> <json> <regex>: consulta com retry; resposta deve casar.
confere() {
  local out; out="$(query_retry "$2" "$3" "$4")"
  if echo "${out}" | grep -Eq -- "$4"; then passo_ok "$1"; else falhou "$1" "${out}"; fi
}

echo "==> Ids: projeto=${PROJ} relatorio=${REL} creditos=${CRA},${CRB}"

echo "==> 1. registrarProjeto"
aceita  "registrarProjeto (Developer)" "${DEV}" "{\"function\":\"registrarProjeto\",\"Args\":[\"${PROJ}\",\"Projeto E2E\",\"1500.5\",\"VM0007\"]}"
rejeita "registrarProjeto por Marketplace" "Apenas identidades da organizacao proponente" "${MKT}" \
  "{\"function\":\"registrarProjeto\",\"Args\":[\"${PROJ}-neg\",\"Nao deve\",\"10\",\"VM0007\"]}"
confere "consultarProjeto no peer do Marketplace (replicado, REGISTRADO)" "${MKT}" \
  "{\"function\":\"consultarProjeto\",\"Args\":[\"${PROJ}\"]}" "\"status\":\"REGISTRADO\""

echo "==> 2. submeterRelatorioMRV"
rejeita "submeterRelatorioMRV por Verifier" "Apenas identidades da organizacao proponente" "${VER}" \
  "{\"function\":\"submeterRelatorioMRV\",\"Args\":[\"${REL}-neg\",\"${PROJ}\",\"2024-01-01T00:00:00Z\",\"2024-12-31T23:59:59Z\",\"2000000\",\"sha256-evidencia\"]}"
rejeita "submeterRelatorioMRV com toneladas fracionarias (2000000.5)" "Unexpected error|NumberFormat|invalid|For input" "${DEV}" \
  "{\"function\":\"submeterRelatorioMRV\",\"Args\":[\"${REL}-frac\",\"${PROJ}\",\"2024-01-01T00:00:00Z\",\"2024-12-31T23:59:59Z\",\"2000000.5\",\"sha256-evidencia\"]}"
aceita  "submeterRelatorioMRV (Developer, estimado=2000000 kgCO2e)" "${DEV}" \
  "{\"function\":\"submeterRelatorioMRV\",\"Args\":[\"${REL}\",\"${PROJ}\",\"2024-01-01T00:00:00Z\",\"2024-12-31T23:59:59Z\",\"2000000\",\"sha256-evidencia\"]}"
rejeita "submeterRelatorioMRV com periodo sobreposto (INV-04)" "sobrepoe o periodo" "${DEV}" \
  "{\"function\":\"submeterRelatorioMRV\",\"Args\":[\"${REL}-dup\",\"${PROJ}\",\"2024-06-01T00:00:00Z\",\"2025-01-31T00:00:00Z\",\"1000\",\"sha256-evidencia\"]}"

echo "==> 3. verificarRelatorioMRV"
rejeita "verificarRelatorioMRV pela organizacao errada (Developer)" "Apenas identidades da organizacao verificadora" "${DEV}" \
  "{\"function\":\"verificarRelatorioMRV\",\"Args\":[\"${REL}\",\"true\",\"1000000\",\"auto-verificacao\"]}"
rejeita "verificarRelatorioMRV pela organizacao errada (Marketplace)" "Apenas identidades da organizacao verificadora" "${MKT}" \
  "{\"function\":\"verificarRelatorioMRV\",\"Args\":[\"${REL}\",\"true\",\"1000000\",\"intruso\"]}"
rejeita "verificarRelatorioMRV com toneladasVerificadas fracionaria (1000000.5)" "Unexpected error|NumberFormat|invalid|For input" "${VER}" \
  "{\"function\":\"verificarRelatorioMRV\",\"Args\":[\"${REL}\",\"true\",\"1000000.5\",\"ok\"]}"
rejeita "verificarRelatorioMRV aprovado com toneladasVerificadas=0" "toneladasVerificadas deve ser inteiro positivo" "${VER}" \
  "{\"function\":\"verificarRelatorioMRV\",\"Args\":[\"${REL}\",\"true\",\"0\",\"ok\"]}"
rejeita "emitirCredito antes da verificacao (relatorio SUBMETIDO)" "credito so pode ser emitido a partir de relatorio VERIFICADO" "${DEV}" \
  "{\"function\":\"emitirCredito\",\"Args\":[\"${CRA}\",\"${REL}\",\"600000\"]}"
aceita  "verificarRelatorioMRV (Verifier, aprovado, toneladasVerificadas=1000000 kgCO2e)" "${VER}" \
  "{\"function\":\"verificarRelatorioMRV\",\"Args\":[\"${REL}\",\"true\",\"1000000\",\"Relatorio conferido em campo\"]}"
# O contrato nao expoe consulta de relatorio MRV; a prova de que a verificacao
# gravou toneladasVerificadas=1000000 e o limite da emissao no passo 4
# (1000001 recusado, 600000 + 400000 aceitos).

echo "==> 4. emitirCredito"
rejeita "emitirCredito por Marketplace" "Apenas identidades da organizacao proponente" "${MKT}" \
  "{\"function\":\"emitirCredito\",\"Args\":[\"${CRA}-neg\",\"${REL}\",\"600000\"]}"
rejeita "emitirCredito acima do verificado (1000001 > 1000000)" "Emissao excede as toneladas verificadas" "${DEV}" \
  "{\"function\":\"emitirCredito\",\"Args\":[\"${CRA}-neg\",\"${REL}\",\"1000001\"]}"
aceita  "emitirCredito A (Developer, 600000 kgCO2e)" "${DEV}" \
  "{\"function\":\"emitirCredito\",\"Args\":[\"${CRA}\",\"${REL}\",\"600000\"]}"
rejeita "emitirCredito B excedendo o saldo restante (500000 > 400000)" "Emissao excede as toneladas verificadas" "${DEV}" \
  "{\"function\":\"emitirCredito\",\"Args\":[\"${CRB}\",\"${REL}\",\"500000\"]}"
aceita  "emitirCredito B (Developer, 400000 kgCO2e; fecha o saldo)" "${DEV}" \
  "{\"function\":\"emitirCredito\",\"Args\":[\"${CRB}\",\"${REL}\",\"400000\"]}"

echo "==> 5. transferirCredito"
rejeita "transferirCredito por quem nao e proprietario (Marketplace)" "Apenas o proprietario atual do credito pode transferi-lo" "${MKT}" \
  "{\"function\":\"transferirCredito\",\"Args\":[\"${CRA}\",\"MarketplaceMSP\"]}"
aceita  "transferirCredito A: Developer -> MarketplaceMSP" "${DEV}" \
  "{\"function\":\"transferirCredito\",\"Args\":[\"${CRA}\",\"MarketplaceMSP\"]}"
rejeita "transferirCredito A de novo pelo antigo dono (Developer)" "Apenas o proprietario atual do credito pode transferi-lo" "${DEV}" \
  "{\"function\":\"transferirCredito\",\"Args\":[\"${CRA}\",\"MarketplaceMSP\"]}"

echo "==> 6. aposentarCredito"
rejeita "aposentarCredito com beneficiario em texto livre" "fora do formato de digest" "${MKT}" \
  "{\"function\":\"aposentarCredito\",\"Args\":[\"${CRA}\",\"Compensacao 2024\",\"Empresa Exemplo Ltda\"]}"
rejeita "aposentarCredito com beneficiario em maiusculas (formato canonico e minusculo)" "fora do formato de digest" "${MKT}" \
  "{\"function\":\"aposentarCredito\",\"Args\":[\"${CRA}\",\"Compensacao 2024\",\"sha256:$(echo "${BENEF#sha256:}" | tr 'a-f' 'A-F')\"]}"
rejeita "aposentarCredito por quem nao e proprietario (Developer)" "Apenas o proprietario atual do credito pode aposenta-lo" "${DEV}" \
  "{\"function\":\"aposentarCredito\",\"Args\":[\"${CRA}\",\"Compensacao 2024\",\"${BENEF}\"]}"
rejeita "aposentarCredito com motivo contendo e-mail" "identificador pessoal direto" "${MKT}" \
  "{\"function\":\"aposentarCredito\",\"Args\":[\"${CRA}\",\"contato fulano@example.org\",\"${BENEF}\"]}"
aceita  "aposentarCredito A (Marketplace, beneficiario sha256:<64 hex>)" "${MKT}" \
  "{\"function\":\"aposentarCredito\",\"Args\":[\"${CRA}\",\"Compensacao de emissoes 2024\",\"${BENEF}\"]}"
rejeita "aposentarCredito A de novo (INV-05, terminal)" "ja esta APOSENTADO" "${MKT}" \
  "{\"function\":\"aposentarCredito\",\"Args\":[\"${CRA}\",\"Compensacao de emissoes 2024\",\"${BENEF}\"]}"
rejeita "transferirCredito de credito APOSENTADO" "esta APOSENTADO e nao pode mais ser transferido" "${MKT}" \
  "{\"function\":\"transferirCredito\",\"Args\":[\"${CRA}\",\"DeveloperMSP\"]}"
confere "consultarCredito A: APOSENTADO com beneficiario digest" "${MKT}" \
  "{\"function\":\"consultarCredito\",\"Args\":[\"${CRA}\"]}" "\"status\":\"APOSENTADO\".*\"beneficiarioAposentadoria\":\"${BENEF}\"|\"beneficiarioAposentadoria\":\"${BENEF}\".*\"status\":\"APOSENTADO\""

echo "==> 7. listarCreditosPorProjeto (paginacao com bookmark)"
rejeita "listarCreditosPorProjeto pageSize=0" "pageSize deve estar entre 1 e" "${MKT}" \
  "{\"function\":\"listarCreditosPorProjeto\",\"Args\":[\"${PROJ}\",\"0\",\"\"]}"
rejeita "listarCreditosPorProjeto pageSize=101 (acima do maximo)" "pageSize deve estar entre 1 e" "${MKT}" \
  "{\"function\":\"listarCreditosPorProjeto\",\"Args\":[\"${PROJ}\",\"101\",\"\"]}"
P1="$(query_retry "${MKT}" "{\"function\":\"listarCreditosPorProjeto\",\"Args\":[\"${PROJ}\",\"1\",\"\"]}" '"quantidade":1')"
if echo "${P1}" | grep -q '"quantidade":1'; then passo_ok "listarCreditosPorProjeto pagina 1 (pageSize=1) devolve 1 credito"; else falhou "listarCreditosPorProjeto pagina 1" "${P1}"; fi
BM="$(echo "${P1}" | python3 -c 'import sys,json; print(json.loads(sys.stdin.read()).get("bookmark",""))' 2>/dev/null || true)"
if [ -n "${BM}" ] && [ "${BM}" != "nil" ]; then
  # Bookmark vai como argumento JSON; o proprio python escapa.
  ARGS="$(python3 -c 'import json,sys; print(json.dumps({"function":"listarCreditosPorProjeto","Args":[sys.argv[1],"1",sys.argv[2]]}))' "${PROJ}" "${BM}")"
  P2="$(query_retry "${MKT}" "${ARGS}" '"quantidade":1')"
  ID1="$(echo "${P1}" | python3 -c 'import sys,json; print(json.loads(sys.stdin.read())["creditos"][0]["id"])' 2>/dev/null || true)"
  ID2="$(echo "${P2}" | python3 -c 'import sys,json; print(json.loads(sys.stdin.read())["creditos"][0]["id"])' 2>/dev/null || true)"
  if [ -n "${ID1}" ] && [ -n "${ID2}" ] && [ "${ID1}" != "${ID2}" ]; then
    passo_ok "listarCreditosPorProjeto pagina 2 via bookmark devolve outro credito (${ID1##*-} e ${ID2##*-})"
  else falhou "listarCreditosPorProjeto pagina 2 via bookmark" "${P2}"; fi
else
  falhou "listarCreditosPorProjeto: bookmark vazio na pagina 1 (esperado bookmark para a pagina seguinte)" "${P1}"
fi

echo "==> 8. historicoCredito (paginacao por offset)"
rejeita "historicoCredito offset negativo" "offset deve ser maior ou igual a 0" "${MKT}" \
  "{\"function\":\"historicoCredito\",\"Args\":[\"${CRA}\",\"-1\",\"2\"]}"
rejeita "historicoCredito pageSize=101" "pageSize deve estar entre 1 e" "${MKT}" \
  "{\"function\":\"historicoCredito\",\"Args\":[\"${CRA}\",\"0\",\"101\"]}"
# Credito A tem 3 versoes: EMITIDO, TRANSFERIDO, APOSENTADO.
confere "historicoCredito(A, 0, 2): 2 versoes e temMais=true" "${MKT}" \
  "{\"function\":\"historicoCredito\",\"Args\":[\"${CRA}\",\"0\",\"2\"]}" '"quantidade":2.*"temMais":true'
confere "historicoCredito(A, 2, 2): 1 versao e temMais=false" "${MKT}" \
  "{\"function\":\"historicoCredito\",\"Args\":[\"${CRA}\",\"2\",\"2\"]}" '"quantidade":1.*"temMais":false'
confere "historicoCredito(A, 5, 2): offset alem do fim => pagina vazia" "${MKT}" \
  "{\"function\":\"historicoCredito\",\"Args\":[\"${CRA}\",\"5\",\"2\"]}" '"quantidade":0.*"temMais":false'

echo
echo "==================== RESUMO E2E ===================="
printf '%s\n' "${RESUMO[@]}"
echo "----------------------------------------------------"
echo "aceitas=${ACEITAS} rejeitadas-conforme-esperado=${REJEITADAS_OK} falhas=${FALHAS}"
if [ "${FALHAS}" -eq 0 ]; then
  echo "E2E OK: fluxo registrar -> MRV -> verificar -> emitir -> transferir -> aposentar -> consultas, com rejeicoes conferidas."
  exit 0
fi
echo "E2E FALHOU (${FALHAS} verificacao(oes))." >&2
exit 1

#!/usr/bin/env bash
# scripts/rede/validar-rede.sh
#
# Validacao ESTATICA da rede Fabric REDD+. NAO sobe containers, NAO faz pull,
# NAO gera cripto, NAO instala chaincode. Verifica:
#   1. configtx.yaml parseavel; OrdererOrg + 3 orgs (Developer, Verifier,
#      Marketplace); Application com Endorsement e LifecycleEndorsement;
#      profile RedChannel.
#   2. cryptogen config das 4 orgs presente e coerente com o configtx.
#   3. docker-compose valido (`docker compose config`, nao precisa do
#      daemon): orderer, 3 peers, 3 CouchDB, imagens via mirror.gcr.io.
#   4. Politica de endosso consistente entre configtx e deploy-chaincode.sh.
#   5. Higiene de segredos: .gitignore cobre material gerado e nenhum
#      arquivo versionavel contem chave privada.
#
# Uso:   scripts/rede/validar-rede.sh
# Saida: 0 = nenhuma FALHA; 1 = ao menos uma FALHA. AVISO nao altera a saida.
#
# Passar aqui NAO significa que a rede sobe. O teste de ponta a ponta (subir,
# criar canal, instalar chaincode, invocar registrarProjeto/consultarProjeto,
# derrubar) exige Docker em execucao E o gate do loop 1 liberado
# (python3 scripts/enxame/registrar-rodada.py --verificar-gate loop1-chaincode).
# Isso NAO e feito por este script.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
REDE_DIR="${REPO_ROOT}/rede-fabric"
CONFIGTX="${REDE_DIR}/configtx/configtx.yaml"
COMPOSE="${REDE_DIR}/docker-compose.yaml"
ENV_EXAMPLE="${REDE_DIR}/.env.example"
CRYPTOGEN_DIR="${REDE_DIR}/organizations/cryptogen"
DEPLOY_SH="${SCRIPT_DIR}/deploy-chaincode.sh"
GITIGNORE="${REDE_DIR}/.gitignore"

FALHAS=0
AVISOS=0
ok()    { printf '  [OK]    %s\n' "$*"; }
falha() { printf '  [FALHA] %s\n' "$*"; FALHAS=$((FALHAS + 1)); }
aviso() { printf '  [AVISO] %s\n' "$*"; AVISOS=$((AVISOS + 1)); }
secao() { printf '\n== %s\n' "$*"; }

# Traduz linhas "OK|FALHA|AVISO <texto>" emitidas por um script Python (stdin
# do chamador = heredoc com o codigo; argumentos = argv do Python).
rodar_py() {
  local saida linha
  if ! saida="$(python3 - "$@" 2>&1)"; then
    falha "erro ao executar verificacao Python:"
    printf '%s\n' "${saida}" | sed 's/^/            /'
    return
  fi
  while IFS= read -r linha; do
    case "${linha}" in
      OK\ *)    ok "${linha#OK }" ;;
      FALHA\ *) falha "${linha#FALHA }" ;;
      AVISO\ *) aviso "${linha#AVISO }" ;;
      "")       ;;
      *)        printf '            %s\n' "${linha}" ;;
    esac
  done <<< "${saida}"
}

echo "validar-rede.sh - validacao estatica (nenhum container sera iniciado)"

# ---------------------------------------------------------------------------
secao "0. Pre-requisitos"
if command -v python3 >/dev/null 2>&1; then ok "python3 presente"; else
  falha "python3 ausente"; echo; echo "RESULTADO: FALHAS=${FALHAS}"; exit 1; fi
if python3 -c 'import yaml' 2>/dev/null; then ok "PyYAML presente"; else
  falha "PyYAML ausente (apt install python3-yaml)"; echo; echo "RESULTADO: FALHAS=${FALHAS}"; exit 1; fi
TEM_COMPOSE=0
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  ok "docker compose presente (v$(docker compose version --short 2>/dev/null))"
  TEM_COMPOSE=1
else
  falha "docker compose ausente: validacao do compose nao pode ser feita"
fi
for f in "${CONFIGTX}" "${COMPOSE}" "${ENV_EXAMPLE}" "${DEPLOY_SH}" "${GITIGNORE}"; do
  if [[ -f "${f}" ]]; then ok "existe ${f#"${REPO_ROOT}"/}"; else falha "arquivo ausente: ${f#"${REPO_ROOT}"/}"; fi
done

# ---------------------------------------------------------------------------
secao "1. configtx.yaml (3 orgs + orderer, politicas, profile)"
rodar_py "${CONFIGTX}" <<'PY'
import sys, yaml
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        d = yaml.safe_load(fh)
except Exception as e:
    print(f"FALHA configtx.yaml nao parseavel: {e}")
    sys.exit(0)
print("OK configtx.yaml parseavel (ancoras e merge keys resolvidos)")

orgs = {o.get("ID"): o for o in d.get("Organizations", [])}
for msp in ("OrdererMSP", "DeveloperMSP", "VerifierMSP", "MarketplaceMSP"):
    print(("OK organizacao %s declarada" if msp in orgs else "FALHA organizacao %s ausente") % msp)
for msp in ("DeveloperMSP", "VerifierMSP", "MarketplaceMSP"):
    o = orgs.get(msp)
    if not o:
        continue
    faltam = [p for p in ("Readers", "Writers", "Admins", "Endorsement") if p not in o.get("Policies", {})]
    print(("OK %s: politicas Readers/Writers/Admins/Endorsement" % msp) if not faltam
          else "FALHA %s: politicas ausentes %s" % (msp, faltam))
    if not o.get("AnchorPeers"):
        print("AVISO %s sem AnchorPeers" % msp)

pol = d.get("Application", {}).get("Policies", {})
for p in ("Readers", "Writers", "Admins", "Endorsement", "LifecycleEndorsement"):
    print(("OK Application.Policies.%s presente" if p in pol else "FALHA Application.Policies.%s AUSENTE") % p)
le = pol.get("LifecycleEndorsement")
if le:
    ok = le.get("Type") in ("ImplicitMeta", "Signature") and le.get("Rule")
    print(("OK LifecycleEndorsement: %s %s" % (le.get("Type"), le.get("Rule"))) if ok
          else "FALHA LifecycleEndorsement malformada: %s" % le)

prof = d.get("Profiles", {}).get("RedChannel")
if not prof:
    print("FALHA Profiles.RedChannel ausente")
else:
    papp = prof.get("Application", {})
    ids = sorted(o.get("ID") for o in papp.get("Organizations", []))
    esp = ["DeveloperMSP", "MarketplaceMSP", "VerifierMSP"]
    print(("OK RedChannel.Application.Organizations = %s" if ids == esp
           else "FALHA RedChannel.Application.Organizations = %s (esperado " + str(esp) + ")") % ids)
    pp = papp.get("Policies", {})
    print("OK RedChannel herda Endorsement e LifecycleEndorsement" if "Endorsement" in pp and "LifecycleEndorsement" in pp
          else "FALHA RedChannel nao herda Endorsement/LifecycleEndorsement")
    od = prof.get("Orderer", {})
    print(("OK RedChannel.Orderer.OrdererType = %s" if od.get("OrdererType") == "etcdraft"
           else "FALHA RedChannel.Orderer.OrdererType = %s (esperado etcdraft)") % od.get("OrdererType"))
    n = len(od.get("EtcdRaft", {}).get("Consenters", []))
    if n < 3:
        print("AVISO orderer Raft com %d consenter(s): so demo; producao exige 3+ (registrar no ADR)" % n)
    if "Consortiums" in prof:
        print("FALHA profile com Consortiums: esta rede opera sem system channel")
PY

# ---------------------------------------------------------------------------
secao "2. cryptogen config (4 organizacoes)"
rodar_py "${CRYPTOGEN_DIR}" "${CONFIGTX}" <<'PY'
import os, sys, yaml
cdir, cfgtx = sys.argv[1], sys.argv[2]
esperado = {
    "crypto-config-developer.yaml": ("PeerOrgs", "developer.redd.network"),
    "crypto-config-verifier.yaml": ("PeerOrgs", "verifier.redd.network"),
    "crypto-config-marketplace.yaml": ("PeerOrgs", "marketplace.redd.network"),
    "crypto-config-orderer.yaml": ("OrdererOrgs", "redd.network"),
}
with open(cfgtx, encoding="utf-8") as fh:
    ctx = yaml.safe_load(fh)
mspdirs = " ".join(o.get("MSPDir", "") for o in ctx.get("Organizations", []))
for nome, (chave, dominio) in esperado.items():
    p = os.path.join(cdir, nome)
    if not os.path.isfile(p):
        print("FALHA %s ausente" % nome); continue
    try:
        with open(p, encoding="utf-8") as fh:
            d = yaml.safe_load(fh)
    except Exception as e:
        print("FALHA %s nao parseavel: %s" % (nome, e)); continue
    orgs = d.get(chave) or []
    if not orgs:
        print("FALHA %s: lista %s vazia" % (nome, chave)); continue
    o = orgs[0]
    problemas = []
    if o.get("Domain") != dominio:
        problemas.append("Domain=%s (esperado %s)" % (o.get("Domain"), dominio))
    if not o.get("EnableNodeOUs"):
        problemas.append("EnableNodeOUs ausente (papeis admin/peer/client dos MSPs do configtx dependem disso)")
    if chave == "PeerOrgs" and not (o.get("Template", {}).get("Count", 0) >= 1):
        problemas.append("Template.Count < 1 (sem peer)")
    if chave == "OrdererOrgs" and not any(s.get("Hostname") == "orderer" for s in o.get("Specs", [])):
        problemas.append("Specs sem Hostname 'orderer'")
    if dominio not in mspdirs:
        problemas.append("dominio nao referenciado em MSPDir do configtx")
    print(("OK %s: %s dominio %s" % (nome, o.get("Name"), dominio)) if not problemas
          else "FALHA %s: %s" % (nome, "; ".join(problemas)))
PY

# ---------------------------------------------------------------------------
secao "3. docker-compose (docker compose config, sem daemon)"
if [[ "${TEM_COMPOSE}" -eq 1 ]]; then
  # --env-file .env.example: o compose exige CCAAS_PACKAGE_ID_* mesmo nos
  # servicos de profile; o .env real so existe apos deploy-chaincode.sh.
  COMPOSE_JSON="$(docker compose --env-file "${ENV_EXAMPLE}" -f "${COMPOSE}" --profile '*' config --format json 2>/tmp/validar-rede-compose.err)"
  if [[ $? -ne 0 || -z "${COMPOSE_JSON}" ]]; then
    falha "docker compose config falhou:"
    sed 's/^/            /' /tmp/validar-rede-compose.err
  else
    ok "docker compose config valido (todos os profiles, interpolacao com .env.example)"
    COMPOSE_JSON_FILE="$(mktemp)"
    printf '%s' "${COMPOSE_JSON}" > "${COMPOSE_JSON_FILE}"
    rodar_py "${COMPOSE_JSON_FILE}" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    c = json.load(fh)
svc = c.get("services", {})

def env(s):
    e = svc[s].get("environment", {})
    return e if isinstance(e, dict) else dict(x.split("=", 1) for x in e)

req = {
    "orderer.redd.network": "orderer",
    "peer0.developer.redd.network": "peer Developer",
    "peer0.verifier.redd.network": "peer Verifier",
    "peer0.marketplace.redd.network": "peer Marketplace",
    "couchdb0.developer": "CouchDB Developer",
    "couchdb0.verifier": "CouchDB Verifier",
    "couchdb0.marketplace": "CouchDB Marketplace",
    "cli": "cli (fabric-tools)",
}
for s, desc in req.items():
    print(("OK servico %s presente (%s)" % (s, desc)) if s in svc else "FALHA servico %s ausente (%s)" % (s, desc))

# Imagens: tudo que vem de registry deve usar mirror.gcr.io (Docker Hub falha aqui).
for nome, s in sorted(svc.items()):
    img = s.get("image", "")
    if "build" in s:
        continue  # imagem construida localmente
    if img.startswith("mirror.gcr.io/"):
        print("OK %s: imagem %s" % (nome, img))
    else:
        print("FALHA %s: imagem '%s' fora de mirror.gcr.io (Docker Hub direto falha neste host)" % (nome, img))

# Peers x CouchDB x MSP
pares = {
    "peer0.developer.redd.network": ("DeveloperMSP", "couchdb0.developer"),
    "peer0.verifier.redd.network": ("VerifierMSP", "couchdb0.verifier"),
    "peer0.marketplace.redd.network": ("MarketplaceMSP", "couchdb0.marketplace"),
}
for p, (msp, db) in pares.items():
    if p not in svc:
        continue
    e = env(p)
    probs = []
    if e.get("CORE_PEER_LOCALMSPID") != msp:
        probs.append("LOCALMSPID=%s (esperado %s)" % (e.get("CORE_PEER_LOCALMSPID"), msp))
    if e.get("CORE_LEDGER_STATE_STATEDATABASE") != "CouchDB":
        probs.append("STATEDATABASE != CouchDB")
    if not e.get("CORE_LEDGER_STATE_COUCHDBCONFIG_COUCHDBADDRESS", "").startswith(db + ":"):
        probs.append("COUCHDBADDRESS nao aponta para %s" % db)
    if e.get("CORE_PEER_TLS_ENABLED") != "true":
        probs.append("TLS desabilitado")
    if db not in svc[p].get("depends_on", {}):
        probs.append("sem depends_on %s" % db)
    print(("OK %s: %s + CouchDB %s + TLS" % (p, msp, db)) if not probs
          else "FALHA %s: %s" % (p, "; ".join(probs)))

if "orderer.redd.network" in svc:
    e = env("orderer.redd.network")
    probs = []
    if e.get("ORDERER_GENERAL_LOCALMSPID") != "OrdererMSP":
        probs.append("LOCALMSPID != OrdererMSP")
    if e.get("ORDERER_GENERAL_BOOTSTRAPMETHOD") != "none":
        probs.append("BOOTSTRAPMETHOD != none (Fabric 2.5 sem system channel)")
    if e.get("ORDERER_CHANNELPARTICIPATION_ENABLED") != "true":
        probs.append("CHANNELPARTICIPATION desabilitado (osnadmin nao funciona)")
    if e.get("ORDERER_GENERAL_TLS_ENABLED") != "true":
        probs.append("TLS desabilitado")
    print("OK orderer: OrdererMSP, BOOTSTRAPMETHOD=none, participation API, TLS" if not probs
          else "FALHA orderer: " + "; ".join(probs))

# Portas de host duplicadas
vistas = {}
for nome, s in svc.items():
    for p in s.get("ports", []):
        pub = str(p.get("published")) if isinstance(p, dict) else str(p).split(":")[0]
        vistas.setdefault(pub, []).append(nome)
dup = {k: v for k, v in vistas.items() if len(v) > 1}
print("OK sem portas de host duplicadas" if not dup else "FALHA portas de host duplicadas: %s" % dup)

# Credenciais embutidas no compose
for nome in ("couchdb0.developer", "couchdb0.verifier", "couchdb0.marketplace"):
    if nome in svc and env(nome).get("COUCHDB_PASSWORD") == "adminpw":
        print("AVISO %s: COUCHDB_PASSWORD='adminpw' fixa no compose (credencial de demo; em producao vir de segredo fora do git)" % nome)
        break
if "ca.redd.network" in svc and "adminpw" in str(svc["ca.redd.network"].get("command", "")):
    print("AVISO ca.redd.network: bootstrap admin:adminpw fixo no command (credencial de demo)")
PY
    rm -f "${COMPOSE_JSON_FILE}"
    # Contextos de build referenciados (so aviso: nao bloqueiam a rede base)
    for ctx in chaincode api-gateway web-ui; do
      if [[ -d "${REPO_ROOT}/${ctx}" ]]; then ok "contexto de build ${ctx}/ existe"
      else aviso "contexto de build ${ctx}/ ausente (so afeta profile ccaas/app, nao a rede base)"; fi
    done
  fi
  rm -f /tmp/validar-rede-compose.err
else
  falha "compose nao validado (docker compose indisponivel)"
fi

# ---------------------------------------------------------------------------
secao "4. politica de endosso (configtx x deploy-chaincode.sh)"
rodar_py "${CONFIGTX}" "${DEPLOY_SH}" <<'PY'
import re, sys, yaml
with open(sys.argv[1], encoding="utf-8") as fh:
    d = yaml.safe_load(fh)
cfg = d["Application"]["Policies"].get("Endorsement", {}).get("Rule", "")
sh = open(sys.argv[2], encoding="utf-8").read()
m = re.search(r'^CC_ENDORSEMENT_POLICY="(.*)"\s*$', sh, re.M)
dep = m.group(1) if m else None
alvo = "AND('DeveloperMSP.peer','VerifierMSP.peer')"
norm = lambda s: re.sub(r"\s+", "", s or "")
print(("OK configtx Application.Endorsement = %s" % cfg) if norm(cfg) == norm(alvo)
      else "FALHA configtx Application.Endorsement = '%s' (esperado %s)" % (cfg, alvo))
if dep is None:
    print("FALHA CC_ENDORSEMENT_POLICY nao encontrada em deploy-chaincode.sh")
else:
    print(("OK deploy-chaincode.sh CC_ENDORSEMENT_POLICY = %s" % dep) if norm(dep) == norm(alvo)
          else "FALHA deploy-chaincode.sh CC_ENDORSEMENT_POLICY = '%s' (esperado %s)" % (dep, alvo))
    print("OK configtx e deploy-chaincode.sh coerentes" if norm(dep) == norm(cfg)
          else "FALHA politica divergente entre configtx e deploy-chaincode.sh")
print("OK deploy-chaincode.sh consulta o gate do loop 1 antes de instalar"
      if "--verificar-gate loop1-chaincode" in sh
      else "FALHA deploy-chaincode.sh nao consulta o gate do loop 1")
print("AVISO consequencia da politica AND(Developer,Verifier): transferencias Developer<->Marketplace tambem exigem o Verifier (registrar no ADR; alternativa = state-based endorsement)")
PY

# ---------------------------------------------------------------------------
secao "5. segredos (.gitignore e varredura)"
# Caminhos hipoteticos que o fluxo gera; precisam estar ignorados.
GERADOS=(
  "rede-fabric/organizations/peerOrganizations/developer.redd.network/users/Admin@developer.redd.network/msp/keystore/priv_sk"
  "rede-fabric/organizations/peerOrganizations/developer.redd.network/msp/signcerts/cert.pem"
  "rede-fabric/organizations/peerOrganizations/verifier.redd.network/peers/peer0.verifier.redd.network/tls/server.key"
  "rede-fabric/organizations/ordererOrganizations/redd.network/orderers/orderer.redd.network/tls/server.crt"
  "rede-fabric/organizations/fabric-ca/fabric-ca-server.db"
  "rede-fabric/crypto-config/peerOrganizations/x/msp/keystore/abc_sk"
  "rede-fabric/channel-artifacts/redchannel.block"
  "rede-fabric/channel-artifacts/redchannel.tx"
  "rede-fabric/channel-artifacts/genesis.pb"
  "rede-fabric/.env"
  "rede-fabric/ccaas-pkg/code.tar.gz"
  "rede-fabric/redd-creditos.tar.gz"
  "rede-fabric/wallet/admin.id"
)
# Versionados de proposito: nao podem estar ignorados.
VERSIONADOS=(
  "rede-fabric/.env.example"
  "rede-fabric/channel-artifacts/.gitkeep"
  "rede-fabric/organizations/cryptogen/crypto-config-developer.yaml"
  "rede-fabric/organizations/cryptogen/crypto-config-orderer.yaml"
  "rede-fabric/configtx/configtx.yaml"
  "rede-fabric/docker-compose.yaml"
)
if command -v git >/dev/null 2>&1 && git -C "${REPO_ROOT}" rev-parse --git-dir >/dev/null 2>&1; then
  for p in "${GERADOS[@]}"; do
    if git -C "${REPO_ROOT}" check-ignore -q --no-index "${p}"; then ok "ignorado: ${p#rede-fabric/}"
    else falha "NAO ignorado pelo git (risco de segredo/artefato versionado): ${p}"; fi
  done
  for p in "${VERSIONADOS[@]}"; do
    if git -C "${REPO_ROOT}" check-ignore -q --no-index "${p}"; then falha "versionado de proposito mas IGNORADO: ${p}"
    else ok "versionavel: ${p#rede-fabric/}"; fi
  done
  # Varredura: arquivos rastreados ou nao-ignorados em rede-fabric/ e scripts/rede/
  ACHADOS=0
  while IFS= read -r arq; do
    [[ -f "${REPO_ROOT}/${arq}" ]] || continue
    if grep -Iqs -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' "${REPO_ROOT}/${arq}"; then
      falha "chave privada em arquivo versionavel: ${arq}"; ACHADOS=1
    fi
    case "${arq}" in
      *.pem|*_sk|*.key|*.p12|*.pfx|*.jks|*.block) falha "arquivo de material criptografico versionavel: ${arq}"; ACHADOS=1 ;;
    esac
  done < <(git -C "${REPO_ROOT}" ls-files -co --exclude-standard -- rede-fabric scripts/rede)
  if [[ "${ACHADOS}" -eq 0 ]]; then ok "nenhuma chave privada nem material criptografico em arquivos versionaveis (rede-fabric/, scripts/rede/)"; fi
  # .env real nao pode estar rastreado
  if git -C "${REPO_ROOT}" ls-files --error-unmatch rede-fabric/.env >/dev/null 2>&1; then
    falha "rede-fabric/.env esta rastreado pelo git"
  else ok "rede-fabric/.env nao rastreado"; fi
else
  falha "git indisponivel: higiene de segredos nao verificada"
fi
# O validador nao deve ter lido nenhum conteudo de chave: so padroes foram testados.

# ---------------------------------------------------------------------------
echo
echo "RESULTADO: FALHAS=${FALHAS} AVISOS=${AVISOS}"
if [[ "${FALHAS}" -eq 0 ]]; then
  echo "Validacao ESTATICA aprovada. A rede NAO foi levantada nem testada em execucao."
  exit 0
fi
echo "Validacao ESTATICA reprovada."
exit 1

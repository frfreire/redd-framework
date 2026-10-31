#!/usr/bin/env python3
"""Lint mecanico para chaincode Java (Hyperledger Fabric).

Acha padroes que quebram o endosso deterministico e regras da bar do loop1.
NAO prova corretude: o critic ainda precisa ler a logica.

Uso: lint-chaincode.py <dir-ou-arquivo> [--json]
Saida: 0 sem achados bloqueantes; 1 com achados; 2 uso incorreto.
"""
import json
import re
import sys
from pathlib import Path

# (id, criterio_da_bar, severidade, regex, mensagem)
REGRAS = [
    ("CC-001", 1, "BLOQUEANTE", r"\bInstant\.now\s*\(|\bLocalDateTime\.now\s*\(|\bLocalDate\.now\s*\(|\bZonedDateTime\.now\s*\(|\bSystem\.currentTimeMillis\s*\(|\bSystem\.nanoTime\s*\(|\bnew\s+Date\s*\(\s*\)|\bCalendar\.getInstance\s*\(",
     "relogio local: peers divergem. Use ctx.getStub().getTxTimestamp()."),
    ("CC-002", 1, "BLOQUEANTE", r"\bUUID\.randomUUID\s*\(|\bnew\s+(java\.util\.)?Random\s*\(|\bMath\.random\s*\(|\bSecureRandom\b|\bThreadLocalRandom\b",
     "aleatoriedade no endosso: peers divergem. Id vem do argumento ou de getTxId()."),
    ("CC-003", 1, "BLOQUEANTE", r"\bnew\s+(java\.net\.)?URL\s*\(|\bHttpClient\b|\bHttpURLConnection\b|\bnew\s+Socket\s*\(|\bFiles\.(read|write|new)|\bnew\s+File(Reader|InputStream|Writer|OutputStream)\s*\(",
     "I/O externo dentro do contrato: nao deterministico."),
    ("CC-004", 1, "BLOQUEANTE", r"\bSystem\.getenv\s*\(|\bSystem\.getProperty\s*\(",
     "ambiente do peer influenciando logica: nao deterministico entre orgs."),
    ("CC-005", 1, "AVISO", r"\bnew\s+(java\.util\.)?(HashMap|HashSet)\s*<",
     "HashMap/HashSet: se for iterado para montar estado ou JSON, use LinkedHashMap/TreeMap."),
    ("CC-006", 1, "AVISO", r"\bString\.format\s*\(\s*\"[^\"]*%[0-9.]*[fdeEgG]|\bNumberFormat\.|\bDecimalFormat\b",
     "formatacao numerica sensivel a Locale do peer. Passe Locale.ROOT ou evite."),
    ("CC-007", 5, "BLOQUEANTE", r"\b(private|protected|public)\s+(final\s+)?(double|float|Double|Float)\s+\w*(tonelada|co2|credito|saldo|valor|quantidade)\w*",
     "toneladas em ponto flutuante: soma e comparacao imprecisas. Use BigDecimal ou long em milesimos."),
    ("CC-008", 8, "BLOQUEANTE", r"getStateByRange\s*\(\s*\"\"\s*,\s*\"\"\s*\)",
     "varredura total do ledger. Use chave composta, range limitado ou rich query com indice."),
    ("CC-009", 8, "AVISO", r"getQueryResult\s*\(",
     "rich query sem paginacao? Prefira getQueryResultWithPagination e confirme o indice em META-INF/statedb/couchdb/indexes."),
    ("CC-010", 2, "AVISO", r"final\s+String\s+(remetente|sender|caller|owner|solicitante|usuario|user)\b",
     "parametro de identidade vindo do cliente: nao pode decidir autorizacao (use ClientIdentity)."),
    ("CC-011", 6, "AVISO", r"APOSENTADO[^\n]{0,80}\breturn\b(?!\s+(new|credito|null))",
     "retorno silencioso em ativo aposentado? Deve lancar excecao explicita."),
    ("CC-012", 1, "BLOQUEANTE", r"\bThread\.sleep\s*\(|\bnew\s+Thread\s*\(|\bExecutorService\b|\bCompletableFuture\b",
     "concorrencia/espera dentro do contrato."),
    ("CC-013", 1, "AVISO", r"\bstatic\s+(?!final)[A-Za-z<>\[\], ]+\s+\w+\s*(=|;)",
     "estado estatico mutavel persiste entre transacoes e pode divergir entre peers."),
    ("CC-014", 9, "AVISO", r"\b(cpf|cnpj|nomeCompleto|email|telefone|endereco)\b",
     "possivel dado pessoal no estado publico. Use colecao de dados privados e guarde hash."),
]

FUNCAO_ESCRITA = re.compile(r"@Transaction\s*\(\s*intent\s*=\s*Transaction\.TYPE\.SUBMIT\s*\)\s*public\s+[\w<>\[\], ]+\s+(\w+)\s*\(")


def comentario_ou_string_simples(linha):
    s = linha.strip()
    return s.startswith("//") or s.startswith("*") or s.startswith("/*")


def verificar(arq: Path):
    achados = []
    texto = arq.read_text(encoding="utf-8", errors="replace")
    linhas = texto.splitlines()
    em_teste = "/src/test/" in str(arq)
    for i, ln in enumerate(linhas, 1):
        if comentario_ou_string_simples(ln):
            continue
        for rid, crit, sev, rx, msg in REGRAS:
            if em_teste and rid not in ("CC-007",):
                continue
            if re.search(rx, ln, re.I if rid == "CC-014" else 0):
                achados.append({"regra": rid, "criterio": crit, "severidade": sev,
                                "arquivo": str(arq), "linha": i, "trecho": ln.strip()[:140], "mensagem": msg})
    if not em_teste:
        # regras estruturais por funcao de escrita
        for m in FUNCAO_ESCRITA.finditer(texto):
            nome = m.group(1)
            ini = m.end()
            prox = FUNCAO_ESCRITA.search(texto, ini)
            fim = prox.start() if prox else len(texto)
            corpo = texto[ini:fim]
            linha = texto.count("\n", 0, m.start()) + 1
            if "setEvent" not in corpo and "emitirEvento" not in corpo:
                achados.append({"regra": "CC-020", "criterio": 7, "severidade": "BLOQUEANTE",
                                "arquivo": str(arq), "linha": linha, "trecho": nome,
                                "mensagem": f"funcao de escrita '{nome}' nao emite evento de dominio (setEvent)."})
            if "getClientIdentity" not in corpo and "verificarPapel" not in corpo and "exigirPapel" not in corpo:
                achados.append({"regra": "CC-021", "criterio": 2, "severidade": "AVISO",
                                "arquivo": str(arq), "linha": linha, "trecho": nome,
                                "mensagem": f"funcao de escrita '{nome}' nao consulta ClientIdentity (autorizacao?)."})
            if "RuntimeException(" in corpo and "ChaincodeException" not in corpo and "ContractException" not in corpo and "Exception(" in corpo:
                pass
    return achados


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    como_json = "--json" in sys.argv
    if not args:
        print(__doc__)
        return 2
    alvo = Path(args[0])
    if not alvo.exists():
        print(f"ERRO: {alvo} nao existe")
        return 2
    arqs = [alvo] if alvo.is_file() else sorted(alvo.rglob("*.java"))
    if not arqs:
        print(f"ERRO: nenhum .java em {alvo} (lint nao avaliou nada)")
        return 2
    todos = []
    for a in arqs:
        todos.extend(verificar(a))
    bloq = [a for a in todos if a["severidade"] == "BLOQUEANTE"]
    if como_json:
        print(json.dumps({"arquivos": len(arqs), "achados": todos}, ensure_ascii=False, indent=1))
    else:
        for a in todos:
            print(f"{a['severidade']:10s} {a['regra']} (criterio {a['criterio']}) {a['arquivo']}:{a['linha']}  {a['mensagem']}\n           > {a['trecho']}")
        print(f"\n{len(arqs)} arquivo(s); {len(bloq)} bloqueante(s), {len(todos) - len(bloq)} aviso(s)")
    return 1 if bloq else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Lint mecanico para o simulador JABS (Java).

Acha as classes de defeito que invalidam resultado de pesquisa:
 - aleatoriedade fora do RandomnessEngine (quebra reprodutibilidade)
 - trafego estimado por formula em vez de medido
 - protocolo de consenso sem hook de trafego em PeerBlockchainNode
 - quorum com comparacao estrita ou divisao inteira suspeita
 - excecao engolida em hook bizantino
 NAO prova corretude.

Uso: lint-jabs.py <dir-java> [--json]
Saida: 0 sem bloqueantes; 1 com bloqueantes; 2 uso incorreto.
"""
import json
import re
import sys
from pathlib import Path

REGRAS = [
    ("JB-001", 2, "BLOQUEANTE", r"\bMath\.random\s*\(",
     "Math.random(): fora da semente. Use RandomnessEngine do cenario."),
    ("JB-002", 2, "BLOQUEANTE", r"new\s+(java\.util\.)?Random\s*\(\s*\d+[Ll]?\s*\)",
     "Random com semente literal: toda replicacao sai igual. Derive da semente do cenario."),
    ("JB-003", 2, "AVISO", r"new\s+(java\.util\.)?Random\s*\(\s*[A-Za-z_]",
     "Random derivado de variavel: reprodutivel, mas fora do RandomnessEngine. Prefira o engine."),
    ("JB-004", 2, "AVISO", r"\bSystem\.currentTimeMillis\s*\(|\bSystem\.nanoTime\s*\(|\bInstant\.now\s*\(",
     "relogio real dentro da simulacao: nao deterministico. So aceitavel para medir tempo de parede."),
    ("JB-005", 1, "BLOQUEANTE", r"(addBlockTraffic|addConsensusMessageTraffic)\s*\([^)]*\b(numAllParticipants|numNodes|numValidators|n)\s*\*",
     "trafego calculado como produto por n: estimativa, nao medicao. Use packet.getSize()."),
    ("JB-006", 1, "BLOQUEANTE", r"\b(numAllParticipants|numNodes|numValidators)\s*\*\s*\d+\s*\*\s*1024|(?i:traffic|trafego|bytes)\w*\s*[+*]?=[^;]*\bnextGaussian\s*\(|(?i:noise|ruido)\s*=\s*\w+\.nextGaussian\s*\(",
     "formula fechada/ruido gaussiano em calculo de trafego: foi o defeito original do PBFT."),
    ("JB-007", 3, "AVISO", r"(currentVotes|votes\w*|count)\s*>\s*(requiredVotes|quorum\w*|threshold\w*)\b",
     "comparacao estrita com quorum: o quorum efetivo fica 1 acima do teorico. Confirme se e intencional (2f+1 usa >=)."),
    ("JB-008", 3, "AVISO", r"\(\s*\w+\s*/\s*3\s*\)\s*\*\s*2",
     "(n/3)*2 com divisao inteira: para n nao multiplo de 3 difere de 2f+1. Calcule f=(n-1)/3 e use 2f+1."),
    ("JB-009", 4, "BLOQUEANTE", r"catch\s*\(\s*(final\s+)?(java\.lang\.)?(Exception|Throwable)\s+\w+\s*\)\s*\{\s*(//[^\n]*)?\s*\}",
     "catch vazio de Exception: engole falha de hook bizantino ou de medicao."),
    ("JB-010", 4, "AVISO", r"catch\s*\(\s*(final\s+)?Exception\s+(ignored|ignore)\s*\)",
     "excecao ignorada: se estiver em hook bizantino ou contabilizacao, o resultado fica silenciosamente errado."),
    ("JB-011", 5, "AVISO", r"(consensusMessageTraffic|trafficUntilFinalization)\s*[<>=!]=?\s*0\b",
     "o sentinela de 'nao medido' e -1: conferir se -1 nao esta sendo tratado como zero."),
    ("JB-012", 2, "AVISO", r"(HashMap|HashSet)\s*<\s*(Node|Block|Vote|Tx|\w*Block\w*|\w*Vote\w*)\s*[,>]",
     "colecao hash com chave sem equals/hashCode: ordem depende do endereco do objeto. Nao itere para decidir comportamento."),
]

PROTOCOLOS_REF = ("Parlia", "CasperFFG", "NakamotoConsensus", "PBFT")


def verificar_arquivo(arq: Path):
    achados = []
    texto = arq.read_text(encoding="utf-8", errors="replace")
    em_teste = "/src/test/" in str(arq)
    for i, ln in enumerate(texto.splitlines(), 1):
        s = ln.strip()
        if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
            continue
        if em_teste:
            continue
        for rid, crit, sev, rx, msg in REGRAS:
            if re.search(rx, ln):
                if rid == "JB-010" and any(a["regra"] == "JB-009" and a["linha"] == i for a in achados):
                    continue  # mesma linha ja reportada como JB-009
                achados.append({"regra": rid, "criterio": crit, "severidade": sev, "arquivo": str(arq),
                                "linha": i, "trecho": s[:140], "mensagem": msg})
    return achados


def verificar_hooks(raiz: Path):
    """Todo X em consensus/algorithm que e Voting/Deterministic precisa aparecer em PeerBlockchainNode."""
    achados = []
    nos = list(raiz.rglob("PeerBlockchainNode.java"))
    algo = raiz / "jabs" / "consensus" / "algorithm"
    if not nos or not algo.exists():
        return achados
    no_txt = nos[0].read_text(encoding="utf-8", errors="replace")
    for f in sorted(algo.glob("*.java")):
        t = f.read_text(encoding="utf-8", errors="replace")
        nome = f.stem
        if nome.startswith("Abstract") or nome in ("ConsensusAlgorithm", "VotingBasedConsensus", "QueryingBasedConsensus",
                                                    "DeterministicFinalityConsensus", "package-info"):
            continue
        decl = re.search(rf"class\s+{nome}\b(.*?)\{{", t, re.S)
        usa_voto = bool(decl and re.search(r"\bVotingBasedConsensus\b", decl.group(1)))
        if usa_voto and not re.search(rf"instanceof\s+(?:[\w]+\.)*{nome}\b", no_txt):
            achados.append({"regra": "JB-020", "criterio": 1, "severidade": "BLOQUEANTE", "arquivo": str(nos[0]),
                            "linha": 0, "trecho": nome,
                            "mensagem": f"protocolo de votacao '{nome}' nao aparece em instanceof de PeerBlockchainNode: trafego sairia 0."})
    return achados


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    como_json = "--json" in sys.argv
    if not args:
        print(__doc__)
        return 2
    raiz = Path(args[0])
    if not raiz.exists():
        print(f"ERRO: {raiz} nao existe")
        return 2
    arqs = sorted(raiz.rglob("*.java"))
    if not arqs:
        print(f"ERRO: nenhum .java em {raiz} (lint nao avaliou nada)")
        return 2
    todos = []
    for a in arqs:
        todos.extend(verificar_arquivo(a))
    todos.extend(verificar_hooks(raiz))
    bloq = [a for a in todos if a["severidade"] == "BLOQUEANTE"]
    if como_json:
        print(json.dumps({"arquivos": len(arqs), "achados": todos}, ensure_ascii=False, indent=1))
    else:
        for a in todos:
            loc = f"{a['arquivo']}:{a['linha']}" if a["linha"] else a["arquivo"]
            print(f"{a['severidade']:10s} {a['regra']} (criterio {a['criterio']}) {loc}  {a['mensagem']}\n           > {a['trecho']}")
        print(f"\n{len(arqs)} arquivo(s); {len(bloq)} bloqueante(s), {len(todos) - len(bloq)} aviso(s)")
    return 1 if bloq else 0


if __name__ == "__main__":
    sys.exit(main())

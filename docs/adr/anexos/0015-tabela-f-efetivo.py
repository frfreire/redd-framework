#!/usr/bin/env python3
"""Tabela de f efetivo por n e protocolo (ADR-0015, fatia 2, 17/11/2026). Biblioteca padrao apenas.

Universo de validadores por protocolo (Q32 A; ByzantineSweep.configFor/universeSize; NetworkSizing):
  pbft, hotstuff, tendermint : n                     (todos os nos votam)
  casperffg                  : n no modo nodes       (todos os nos votam; NetworkSizing.Decomposition.casperVoters)
  parlia                     : so mineradores. Modo nodes: max(1, (3n+5)//10)  [NetworkSizing.nodes, :133-139]
                               Modo legacy (so contraste): int(n*0.3) em double [NetworkSizing.mvpLegacy, :113-115]
  nakamoto                   : n mineradores (ByzantineSweep POW); o limiar BFT 1/3 NAO se aplica (bftApplies).
f = ceil(pct * u / 100) em aritmetica inteira: (pct*u + 99)//100 (pct inteiro).
Limite BFT = floor((u-1)/3) (ByzantineConfig.bftLimit :180-183). Fora da faixa: f > limite.
Nada aqui escolhe n: 16, 64 e 100 sao ilustracao declarada (n do E2 a definir, Q33 A).

Uso: python3 0015-tabela-f-efetivo.py            (imprime a tabela em Markdown e o contraste)
"""
import sys

PCTS = [0, 10, 20, 33, 40, 51]  # .experimento/bateria2.yaml:16
PROTOS = ["pbft", "hotstuff", "tendermint", "casperffg", "parlia", "nakamoto"]  # bateria2.yaml:15
NS = [16, 64, 100]  # ilustracao declarada


def miners_nodes(n):
    """NetworkSizing.nodes: floor(0,3 n + 0,5) em inteiros, minimo 1."""
    return max(1, (3 * n + 5) // 10)


def miners_legacy(n):
    """(int)(n * 0.3) com double IEEE (Python float == Java double); so para contraste."""
    return int(n * 0.3)


def universo(proto, n, modo="nodes"):
    if proto == "parlia":
        return miners_nodes(n) if modo == "nodes" else miners_legacy(n)
    return n


def f_efetivo(pct, u):
    return (pct * u + 99) // 100


def limite_bft(u):
    return (u - 1) // 3


def main():
    out = []
    out.append("| n | protocolo | universo u | limite BFT floor((u-1)/3) | "
               + " | ".join("f=%d%%" % p for p in PCTS) + " |")
    out.append("|---|---|---|---|" + "---|" * len(PCTS))
    for n in NS:
        for proto in PROTOS:
            u = universo(proto, n)
            lim = "n/a" if proto == "nakamoto" else str(limite_bft(u))
            cels = []
            for p in PCTS:
                f = f_efetivo(p, u)
                if proto == "nakamoto":
                    cels.append("%d (n/a)" % f)
                elif f <= limite_bft(u):
                    cels.append("%d dentro" % f)
                else:
                    cels.append("**%d fora**" % f)
            out.append("| %d | %s | %d | %s | %s |" % (n, proto, u, lim, " | ".join(cels)))
    print("\n".join(out))
    print()
    print("Contraste do universo do Parlia (nodes = semantica do estudo; legacy = int(n*0.3), NAO usar):")
    for n in NS:
        a, b = miners_nodes(n), miners_legacy(n)
        print("  n=%d: nodes=%d legacy=%d%s" % (n, a, b, "" if a == b else "  <- DIFERE"))
    print()
    print("Menor pct inteiro (0..100) cujo f efetivo excede o limite BFT, por universo:")
    for n in NS:
        for proto in ("pbft", "parlia"):
            u = universo(proto, n)
            lim = limite_bft(u)
            pct = next(p for p in range(0, 101) if f_efetivo(p, u) > lim)
            print("  n=%d %-6s u=%d limite=%d -> %d%%" % (n, proto, u, lim, pct))
    print()
    print("Universos de Parlia (nodes) para n = 4..128, f por pct e limite:")
    for n in (4, 7, 10, 13, 16, 25, 64, 100, 128):
        u = universo("parlia", n)
        print("  n=%3d u=%2d limite=%2d f=%s" % (n, u, limite_bft(u), [f_efetivo(p, u) for p in PCTS]))
    return 0


if __name__ == "__main__":
    sys.exit(main())

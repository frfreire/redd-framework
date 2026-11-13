#!/usr/bin/env python3
"""Propoe --hs-view-timeout = m x D^ p99,99(n) e verifica o criterio A3 (D_max <= T/2) sobre a medicao.

Uso: propor-timeout.py --analise-L100 a.json [--analise-L10 b.json --analise-L500 c.json] --saida propostas.json
D^(n, L) = MAIOR p99,99 (nearest-rank) entre as politicas ROTATING e STABLE; D_max = maior D entre as duas.
T e arredondado PARA CIMA a 3 casas (ms). m = 2; se A3 falhar para qualquer n do caso de aceite (L=100), m = 3.
Nao decide m por n: a regra e global (um m para o caso de aceite), como no registro 12/11.
Saida: propostas por (L, n, m), com folga D_max/T e se A3 passa; e `m_adotado`.
"""
import argparse
import json
import math
from pathlib import Path


def teto3(x):
    return math.ceil(x * 1000 - 1e-9) / 1000


def carregar(p):
    d = json.loads(Path(p).read_text())
    out = {}
    for g in d["grupos"]:
        if g["ataque"] != "NONE":
            continue
        k = (g["L"], g["n"])
        e = out.setdefault(k, {"p9999": {}, "max": {}, "N": {}, "p99": {}, "boot": {}})
        e["p9999"][g["politica"]] = g["D"]["p99_99_nearest_rank"]
        e["max"][g["politica"]] = g["D"]["max"]
        e["N"][g["politica"]] = g["D"]["N"]
        e["p99"][g["politica"]] = g["D"]["p99"]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--analise", nargs="+", required=True)
    ap.add_argument("--saida", required=True)
    ap.add_argument("--L-aceite", type=int, default=100)
    a = ap.parse_args()
    dados = {}
    for p in a.analise:
        dados.update(carregar(p))
    prop = []
    for (L, n), e in sorted(dados.items()):
        Dh = max(e["p9999"].values())
        Dmax = max(e["max"].values())
        linha = {"L": L, "n": n, "D_hat_p99_99_max_entre_politicas": Dh, "D_max": Dmax,
                 "N_por_politica": e["N"], "p9999_por_politica": e["p9999"], "propostas": {}}
        for m in (2, 3):
            T = teto3(m * Dh)
            linha["propostas"][str(m)] = {"T_s": T, "folga_Dmax_sobre_T": Dmax / T, "A3_passa": Dmax <= T / 2}
        prop.append(linha)
    aceite = [p for p in prop if p["L"] == a.L_aceite]
    m_adotado = 2 if all(p["propostas"]["2"]["A3_passa"] for p in aceite) else 3
    if not all(p["propostas"]["3"]["A3_passa"] for p in aceite) and m_adotado == 3:
        m_adotado = "A3 falha tambem com m=3: parar e pedir decisao humana"
    saida = {"L_aceite": a.L_aceite, "m_adotado": m_adotado, "propostas": prop}
    Path(a.saida).write_text(json.dumps(saida, indent=1))
    for p in prop:
        print(f"L={p['L']:>3} n={p['n']:>3} D^={p['D_hat_p99_99_max_entre_politicas']:.4f} Dmax={p['D_max']:.4f} "
              + " ".join(f"m={m}: T={v['T_s']:.3f} Dmax/T={v['folga_Dmax_sobre_T']:.3f} A3={'ok' if v['A3_passa'] else 'FALHA'}"
                         for m, v in p["propostas"].items()))
    print("m_adotado:", m_adotado)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""EMENDA A3: N minimo para que o MAXIMO da amostra seja limite superior do IC95 bilateral (2,5 % por cauda) de um
quantil q, por estatistica de ordem. Somente aritmetica binomial: NAO le nenhum dado de simulacao.

Teoria (independencia e continuidade assumidas, declaradas na EMENDA):
  E = numero de observacoes acima do quantil verdadeiro x_q  ~  Bin(N, r), r = 1 - q.
  X_(u) (u-esima menor) e limite superior de x_q com confianca P(E >= N-u+1) = 1 - P(E <= N-u).
  Limite superior bilateral a 95 %: maior e tal que P(E <= e) <= 0,025; u = N - e.  Com e = 0 (o maximo): (1-r)^N <= 0,025
  => N >= ln(0,025) / ln(1 - r).
Tambem calcula, para um N dado, o posto do limite superior (N - e) e a esperanca de excedentes N*r.
Saida: emenda-nmin.json e stdout.
"""
import json
import math
import sys
from pathlib import Path

ALFA2 = 0.025


def n_min(r, alfa2=ALFA2):
    return math.ceil(math.log(alfa2) / math.log1p(-r))


def e_sup(N, r, alfa2=ALFA2):
    """Maior e com P(Bin(N,r) <= e) <= alfa2; -1 se nem e = 0 satisfaz (maximo nao limita)."""
    p = math.exp(N * math.log1p(-r))
    acc, e = 0.0, -1
    k = 0
    while True:
        acc += p
        if acc > alfa2:
            return e
        e = k
        p = p * (N - k) / (k + 1) * r / (1 - r)
        k += 1
        if k > N:
            return e


def main():
    quantis = {"p99,9": 1e-3, "p99,98": 2e-4, "p99,99": 1e-4, "p99,995": 5e-5, "p99,999": 1e-5}
    out = {"nivel": "IC95 bilateral, 2,5 % por cauda", "n_min_maximo_como_limite_superior": {}, "exemplos_N": {}}
    for nome, r in quantis.items():
        nm = n_min(r)
        out["n_min_maximo_como_limite_superior"][nome] = {"r=1-q": r, "N_min": nm}
        print(f"{nome:8s} r={r:g}  N_min = {nm}")
    for N in (21600, 43200, 86400, 200000, 368887, 368888, 400000, 410000, 600000):
        linha = {}
        for nome, r in quantis.items():
            e = e_sup(N, r)
            linha[nome] = {"e_sup": e, "posto_sup": (N - e) if e >= 0 else None, "excedentes_esperados": N * r}
        out["exemplos_N"][str(N)] = linha
        print(N, {k: (v["posto_sup"] and f"N-{v['e_sup']}") or "sem limite" for k, v in linha.items()})
    Path(__file__).resolve().parent.parent.joinpath("emenda-nmin.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()

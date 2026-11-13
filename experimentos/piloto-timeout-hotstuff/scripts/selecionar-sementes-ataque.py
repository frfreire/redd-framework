#!/usr/bin/env python3
"""Seleciona, so pelo sorteio bizantino (nunca por resultado), as replicacoes para as celulas com ataque.

Para cada (n, f): percorre replicacoes 1..R e, com `ObservadorHotStuff --listar-byz-faixa` (usa exatamente
ByzantineConfig.withExactCount(n, f, ataque, runSeed) do repositorio), classifica cada replicacao em
  LIDER_BIZANTINO : o no 0 (lider fixo em STABLE) esta no conjunto bizantino
  LIDER_HONESTO   : o no 0 NAO esta no conjunto
e guarda as K primeiras de cada estrato. runSeed = semente_base + n*1000 + replicacao (contrato de rodar_celula.py).
Saida: config/selecao-ataque.json
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

DEST = Path("/tmp/opencode/piloto-hs")


def listar(n, f, base, r):
    cp = f"{DEST}/harness-classes:{DEST}/target/classes:" + (DEST / "cp.txt").read_text().strip()
    out = subprocess.run(["java", "-cp", cp, "ObservadorHotStuff", f"--listar-byz-faixa=1:{r}",
                          f"--run-seed-base={base + n * 1000}", f"--validators={n}", f"--byzantine-count={f}"],
                         capture_output=True, text=True, check=True).stdout.strip().splitlines()
    res = {}
    for l in out:
        s, resto = l.split(" ", 1)
        res[int(s)] = sorted(int(x) for x in resto.strip("[]").replace(" ", "").split(",") if x)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--semente-base", type=int, default=32345)
    ap.add_argument("--k", type=int, default=4)
    ap.add_argument("--r-max", type=int, default=4000)
    ap.add_argument("--saida", required=True)
    a = ap.parse_args()
    sel = {}
    for n in (4, 16, 64, 128):
        for f in sorted({1, (n - 1) // 3}):
            lst = listar(n, f, a.semente_base, a.r_max)
            bz = [s for s in sorted(lst) if 0 in lst[s]][:a.k]
            ho = [s for s in sorted(lst) if 0 not in lst[s]][:a.k]
            sel[f"{n}:{f}"] = {"n": n, "f": f, "LIDER_BIZANTINO": bz, "LIDER_HONESTO": ho,
                               "conjuntos": {str(s): lst[s] for s in bz + ho}}
            print(n, f, "bizantino:", bz, "honesto:", ho)
            if len(bz) < a.k:
                print(f"  AVISO: so {len(bz)} replicacoes com lider bizantino em R={a.r_max}", file=sys.stderr)
    Path(a.saida).write_text(json.dumps({"semente_base": a.semente_base, "k": a.k, "r_max": a.r_max,
                                         "criterio": "so sorteio bizantino; nunca resultado", "selecao": sel},
                                        indent=1))


if __name__ == "__main__":
    main()

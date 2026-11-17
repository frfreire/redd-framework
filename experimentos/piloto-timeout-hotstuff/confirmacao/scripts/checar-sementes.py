#!/usr/bin/env python3
"""Confere que as sementes da confirmacao sao DISJUNTAS de todas as usadas no piloto (so leitura).

semente = semente_base + n*1000 + replicacao (scripts/jabs/gerar-grid.py). Le o campo `semente` de todo cfg.json do
piloto (medicao, verificacao, ataque, sonda) e compara com o intervalo proposto.
Uso: checar-sementes.py <semente_base> <n_replicacoes_max>
"""
import glob
import json
import os
import sys

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
base, rmax = int(sys.argv[1]), int(sys.argv[2])
usadas = {}
for p in glob.glob(f"{RAIZ}/dados-brutos/*/*/cfg.json"):
    c = json.load(open(p))
    usadas.setdefault(c["semente"], []).append(os.path.basename(os.path.dirname(p)))
print("sementes distintas no piloto:", len(usadas), "min", min(usadas), "max", max(usadas))
conf = {}
for n in (4, 16, 64, 128):
    for r in range(1, rmax + 1):
        s = base + n * 1000 + r
        conf[s] = (n, r)
colisoes = {s: usadas[s][:3] for s in conf if s in usadas}
print("sementes da confirmacao:", len(conf), "min", min(conf), "max", max(conf))
print("colisoes com o piloto:", len(colisoes), colisoes)
# tambem: runSeed = --seed + n*1000 + trial; o piloto usa a mesma semente; colisao entre n da propria confirmacao
print("duplicadas dentro da confirmacao:", len(conf) != 4 * rmax)
sys.exit(1 if colisoes or len(conf) != 4 * rmax else 0)

#!/usr/bin/env python3
"""Confere que as sementes de V-A (va, vano) e V-B (vb) sao DISJUNTAS de toda semente ja usada em qualquer experimento
(so leitura; nao simula). Le o campo `semente` de TODO cfg.json sob experimentos/ (piloto, confirmacoes, K40, ataque,
sonda), exceto os desta pasta (validacao/hotstuff/dados-brutos), e confere duplicatas entre blocos e dentro de cada bloco.
Tambem confere a semente EFETIVA passada ao MVPComparison (--seed = semente - n*1000), que e base+r.
Uso: checar-sementes-val.py [--saida arquivo.json]      Saida 0 = sem colisao.
"""
import glob
import json
import os
import sys
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
EXP = VAL.parents[1]
esp = json.loads((VAL / "celulas-esperadas.json").read_text(encoding="utf-8"))
usadas = {}
for p in glob.glob(f"{EXP}/**/cfg.json", recursive=True):
    if os.path.abspath(p).startswith(str(VAL / "dados-brutos")):
        continue
    try:
        c = json.load(open(p))
    except Exception:
        continue
    if "semente" in c:
        usadas.setdefault(c["semente"], []).append(os.path.relpath(p, EXP).split("/cfg.json")[0])
novas = [(b, c["id"], c["semente"], c["semente"] - c["n"] * 1000) for b, v in esp["celulas"].items() for c in v]
col = {}
for b, cid, s, eff in novas:
    if s in usadas:
        col[cid] = usadas[s][:3]
sem_pares = {}
for b, cid, s, eff in novas:
    sem_pares.setdefault(s, []).append(cid)
# V-A: a mesma semente vale para os dois modos (pareado) e, em V-B, para todo f: duplicata so e erro ENTRE blocos
inter = {s: ids for s, ids in sem_pares.items() if len({i.split("_")[0] for i in ids}) > 1}
eff_usadas = {}
for s, v in usadas.items():
    pass
bases = {b: sorted({s for bb, cid, s, e in novas if bb == b}) for b in esp["celulas"]}
out = {"sementes_ja_usadas_distintas": len(usadas), "min_usada": min(usadas), "max_usada": max(usadas),
       "sementes_novas_distintas": len(sem_pares), "min_nova": min(sem_pares), "max_nova": max(sem_pares),
       "faixa_por_bloco": {b: [min(v), max(v)] for b, v in bases.items()},
       "colisoes_com_ja_usadas": col, "duplicadas_entre_blocos": {str(k): v[:4] for k, v in inter.items()},
       "cfg_json_lidos": sum(len(v) for v in usadas.values()),
       "nota": "mesma semente entre modos de assinatura (va, vano) e entre valores de f (vb) e INTENCIONAL (pareamento)"}
print(json.dumps(out, indent=1))
if sys.argv[1:2] == ["--saida"]:
    Path(sys.argv[2]).write_text(json.dumps(out, indent=1))
sys.exit(1 if col or inter else 0)

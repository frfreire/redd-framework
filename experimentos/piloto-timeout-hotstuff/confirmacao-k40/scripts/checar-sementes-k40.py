#!/usr/bin/env python3
"""Confere que as sementes da rodada K=40 sao DISJUNTAS de toda semente ja usada em qualquer experimento do repositorio
(so leitura; nao simula). Le o campo `semente` de TODO cfg.json sob experimentos/ (piloto, confirmacao K=20, replicacao,
ataque, sonda, validacao), exceto os da propria rodada K=40 (confirmacao-k40/dados-brutos), e confere tambem as sementes
efetivas do MVPComparison (runSeed = --seed + n*1000 + trial0 = semente) e duplicatas dentro da especificacao.
Uso: checar-sementes-k40.py [--saida arquivo.json]      Saida 0 = sem colisao.
"""
import glob
import json
import os
import sys
from pathlib import Path

K40 = Path(__file__).resolve().parent.parent
RAIZ_EXP = K40.parent.parent  # experimentos/
esp = json.loads((K40 / "celulas-esperadas.json").read_text(encoding="utf-8"))
usadas = {}
for p in glob.glob(f"{RAIZ_EXP}/**/cfg.json", recursive=True):
    if os.path.abspath(p).startswith(str(K40 / "dados-brutos")):
        continue
    try:
        c = json.load(open(p))
    except Exception:
        continue
    if "semente" in c:
        usadas.setdefault(c["semente"], []).append(os.path.relpath(p, RAIZ_EXP).split("/dados-brutos/")[-1].split("/cfg.json")[0])
novas = [c["semente"] for c in esp["celulas"]]
col = {s: usadas[s][:3] for s in novas if s in usadas}
dup = len(novas) != len(set(novas))
out = {"sementes_ja_usadas_distintas": len(usadas), "min_usada": min(usadas), "max_usada": max(usadas),
       "sementes_novas": len(novas), "min_nova": min(novas), "max_nova": max(novas),
       "colisoes_com_ja_usadas": col, "duplicadas_na_especificacao": dup,
       "cfg_json_lidos": sum(len(v) for v in usadas.values())}
print(json.dumps(out, indent=1))
if sys.argv[1:2] == ["--saida"]:
    Path(sys.argv[2]).write_text(json.dumps(out, indent=1))
sys.exit(1 if col or dup else 0)

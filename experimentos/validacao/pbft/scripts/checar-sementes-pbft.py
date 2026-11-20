#!/usr/bin/env python3
"""Confere que as sementes do V-A do PBFT puro sao DISJUNTAS de TODA semente ja usada em qualquer experimento (so leitura; nao simula).
Le o campo `semente` de TODO cfg.json sob experimentos/ (piloto, K40, V-ABC-hotstuff, V-ABC-tendermint e o que mais houver), exceto os
desta pasta (validacao/pbft/dados-brutos), e as listas `celulas-esperadas.json` do HotStuff e do Tendermint. A semente que o simulador usa
e `semente` (runSeed = --seed + n*1000 + trial 0 = semente).
Uso: checar-sementes-pbft.py [--saida arquivo.json]      Saida 0 = sem colisao.
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
        usadas.setdefault(c["semente"], []).append(os.path.relpath(p, EXP).replace("\\", "/").split("/cfg.json")[0])
for outro in ("hotstuff", "tendermint"):
    arq = EXP / "validacao" / outro / "celulas-esperadas.json"
    if arq.exists():
        for b, v in json.loads(arq.read_text(encoding="utf-8"))["celulas"].items():
            for c in v:
                usadas.setdefault(c["semente"], []).append(f"validacao/{outro}/esperadas/{b}")
novas = [(b, c["id"], c["semente"]) for b, v in esp["celulas"].items() for c in v]
col = {cid: usadas[s][:3] for b, cid, s in novas if s in usadas}
dup = {}
for b, cid, s in novas:
    dup.setdefault(s, []).append(cid)
dup = {str(s): v for s, v in dup.items() if len(v) > 1}
out = {"sementes_ja_usadas_distintas": len(usadas), "min_usada": min(usadas), "max_usada": max(usadas),
       "sementes_novas": len(novas), "min_nova": min(s for _, _, s in novas), "max_nova": max(s for _, _, s in novas),
       "colisoes_com_ja_usadas": col, "duplicadas_entre_celulas": dup,
       "cfg_json_lidos": sum(len(v) for v in usadas.values())}
print(json.dumps(out, indent=1))
if sys.argv[1:2] == ["--saida"]:
    Path(sys.argv[2]).write_text(json.dumps(out, indent=1))
sys.exit(1 if col or dup else 0)

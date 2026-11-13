#!/usr/bin/env python3
"""Custo real da rodada K=40 (parede e memoria), lido de resultado.json e execucao.json. Mesmo formato de
confirmacao/custo.json. Saida: custo-k40.json."""
import glob
import json
from pathlib import Path

K40 = Path(__file__).resolve().parent.parent
B = K40 / "dados-brutos" / "k40"
r = [json.loads(Path(f).read_text()) for f in glob.glob(str(B / "*" / "resultado.json"))]
e = json.loads((B / "execucao.json").read_text())
out = {"celulas": len(r), "max_rss_mb": max(x["max_rss_mb"] for x in r),
       "soma_parede_cpu_s": sum(x["tempo_parede_mvp_s"] + x["tempo_parede_observador_s"] for x in r),
       "parede_do_lote_s": [x["parede_total_s"] for x in e["execucoes"]],
       "rc_mvp_nao0": sum(1 for x in r if x["rc_mvp"] != 0), "rc_obs_nao0": sum(1 for x in r if x["rc_observador"] != 0)}
(K40 / "custo-k40.json").write_text(json.dumps(out, indent=1))
print(out)

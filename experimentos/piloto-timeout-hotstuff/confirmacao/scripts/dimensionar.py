#!/usr/bin/env python3
"""Dimensiona a confirmacao a partir do PILOTO (so leitura dos dados do piloto; nenhum dado novo).

Mede, nas celulas ROTATING L=100 da MEDICAO do piloto, quantas visoes pos-transiente (h > 50) cada celula rende,
e calcula quantas sementes (celulas) sao necessarias por n para N >= N_ALVO visoes. Grava dimensionamento.json.
Uso: python3 experimentos/piloto-timeout-hotstuff/confirmacao/scripts/dimensionar.py
"""
import csv
import glob
import gzip
import json
import math
import os
import statistics as st

AQUI = os.path.dirname(os.path.abspath(__file__))
CONF = os.path.dirname(AQUI)
PIL = os.path.dirname(CONF)
N_ALVO = 36887  # emenda-nmin.json, p99,99, IC95 bilateral
SEMENTES_ADOTADAS = 20
out = {"N_alvo_por_grupo": N_ALVO, "fonte_N_alvo": "experimentos/piloto-timeout-hotstuff/emenda-nmin.json",
       "origem": "piloto, medicao, ROTATING, L=100, 10 sementes por n, alturas h>50", "por_n": {}}
for n in (4, 16, 64, 128):
    cel = sorted(glob.glob(f"{PIL}/dados-brutos/medicao/med_rotating_L100_n{n}_*"))
    vis, dur, par, rss = [], [], [], []
    for d in cel:
        cfg = json.load(open(d + "/cfg.json"))
        res = json.load(open(d + "/resultado.json"))
        with gzip.open(d + "/obs/observador-por-altura.csv.gz", "rt") as f:
            hs = [int(r["height"]) for r in csv.DictReader(l for l in f if not l.startswith("#"))]
        vis.append(sum(1 for h in hs if h > 50))
        dur.append(cfg["duracao_s"])
        par.append(res["tempo_parede_mvp_s"] + res["tempo_parede_observador_s"])
        rss.append(res["max_rss_mb"])
    vmin = min(vis)
    out["por_n"][n] = {
        "celulas_piloto": len(cel), "duracao_s_piloto": dur[0],
        "visoes_pos_t0_por_celula": {"min": vmin, "media": st.fmean(vis), "max": max(vis)},
        "sementes_minimas_para_N_alvo": math.ceil(N_ALVO / vmin),
        "sementes_adotadas": SEMENTES_ADOTADAS,
        "N_esperado_minimo": SEMENTES_ADOTADAS * vmin,
        "margem_sobre_N_alvo": SEMENTES_ADOTADAS * vmin / N_ALVO - 1,
        "parede_s_por_celula_media_2JVMs": st.fmean(par), "parede_s_max": max(par), "rss_mb_max": max(rss)}
tot = sum(v["parede_s_por_celula_media_2JVMs"] * SEMENTES_ADOTADAS for v in out["por_n"].values())
out["custo_estimado"] = {"cpu_s_por_lote_de_80_celulas": tot, "lotes": 2,
                         "parede_estimada_min_com_8_paralelos": 2 * tot / 8 / 60,
                         "cpus_da_maquina": os.cpu_count(), "limite_enxame_paralelo": 8}
json.dump(out, open(f"{CONF}/dimensionamento.json", "w"), indent=1)
print(json.dumps(out, indent=1))

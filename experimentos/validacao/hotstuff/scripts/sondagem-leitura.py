#!/usr/bin/env python3
"""SONDAGEM SO DE LEITURA (nao simula, nao grava dado): cabecalho do CSV por bloco, Tb por n e duracoes ja usadas.
Serve so para dimensionar durações e conferir colunas ANTES do pre-registro. Saida: stdout.
"""
import glob
import gzip
import json
import os
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[4]
EXP = RAIZ / "experimentos"

# 1) cabecalho e 3 primeiras linhas do CSV por bloco (K40, n=64)
p = sorted(glob.glob(str(EXP / "piloto-timeout-hotstuff/confirmacao-k40/dados-brutos/k40/*n64*s16/hotstuff-64v-t0.csv.gz")))[0]
with gzip.open(p, "rt") as f:
    linhas = [l.rstrip() for l in f if not l.startswith("#")]
print("CSV por bloco:", p.split("/")[-2])
print(linhas[0])
print(linhas[1])
print(linhas[2])
print("linhas de dados:", len(linhas) - 1)

# 2) Tb_avg por n (mvp-results.csv) das rodadas ja existentes, L=100 ROTATING sem falha: media por n
import csv
acc = {}
for r in glob.glob(str(EXP / "piloto-timeout-hotstuff/**/mvp-results.csv"), recursive=True):
    cfgp = Path(r).parent / "cfg.json"
    if not cfgp.exists():
        continue
    cfg = json.loads(cfgp.read_text())
    v = cfg.get("variante", {})
    if v.get("L") != 100 or v.get("leader_policy") != "ROTATING" or v.get("attack"):
        continue
    rows = [l for l in open(r) if not l.startswith("#")]
    d = list(csv.DictReader(rows, delimiter=";"))
    if not d:
        continue
    acc.setdefault(cfg["n"], []).append((float(d[0]["Tb_avg"]), cfg["duracao_s"]))
for n in sorted(acc):
    tb = [x[0] for x in acc[n]]
    print(f"n={n:4d} cel={len(tb):4d} Tb_avg medio={sum(tb)/len(tb):.4f}s min={min(tb):.4f} max={max(tb):.4f} duracoes_usadas={sorted(set(x[1] for x in acc[n]))}")

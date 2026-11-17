#!/usr/bin/env python3
"""Custo ESTIMADO do V-ABC do Tendermint (so leitura; NAO simula; RASCUNHO). NAO existe nenhuma celula de Tendermint medida em validacao/:
o custo por segundo simulado do Tendermint e DESCONHECIDO. Proxy declarado e PESSIMISTA: custo MEDIDO do HotStuff (custo-vb-e7.json: V-A/V-C em
76932b9, 2 JVMs, -XX:hashCode=2) multiplicado por r(n) = pacotes por altura do Tendermint / pacotes por altura do HotStuff = (2n+1)/8, supondo a
MESMA taxa de alturas por segundo (o Tendermint decide em 3 passos, o HotStuff basico em 4 fases: a taxa real do Tendermint tende a ser >= a do
HotStuff, o que PIORA o custo; por isso o proxy NAO e um teto garantido). n=13 e interpolado/maximo como no HotStuff (nao medido).
Eficiencia de paralelismo medida no HotStuff: 7,43 com 8 processos. V-B so tem 1 JVM (ObservadorTm): o proxy conta 2 JVMs (pessimista).
Saida: custo-estimado-tm.json.
"""
import json
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
hs = json.loads((VAL.parent / "hotstuff" / "custo-vb-e7.json").read_text())
c = {int(n): v for n, v in hs["custo_cpu_s_por_s_simulado_media"].items()}
cmax = {int(n): v for n, v in hs["custo_cpu_s_por_s_simulado_max"].items()}
EFIC, PAR = hs["eficiencia_de_paralelismo_usada"], 8


def r(n):
    return (2 * n + 1) / 8


def fmax(n):
    return (n - 1) // 3


blocos = {"va": [(n, 5, 120) for n in (4, 7, 10, 16, 31)],
          "vc": [(n, 5, 120) for n in (4, 16)],
          "vb": [(n, 10 * (fmax(n) + 2), 300) for n in (4, 7, 10, 13)]}
out = {"fonte_proxy": "validacao/hotstuff/custo-vb-e7.json (MEDIDO no HotStuff) x r(n)=(2n+1)/8", "fator_r": {str(n): round(r(n), 3) for n in (4, 7, 10, 13, 16, 31)},
       "limites": ["NENHUMA celula de Tendermint medida", "taxa de alturas/s do Tendermint desconhecida (assumida igual a do HotStuff)",
                   "custo com ataque nao medido", "n=13 nao medido (max entre n=10 e n=16 no HotStuff)", "build nao incluido"], "blocos": {}}
tot = {"media": 0.0, "teto": 0.0}
for b, itens in blocos.items():
    cm = sum(k * d * c[n] * r(n) for n, k, d in itens)
    ct = sum(k * d * cmax[n] * r(n) for n, k, d in itens)
    sem_fator = sum(k * d * c[n] for n, k, d in itens)
    out["blocos"][b] = {"celulas": sum(k for _, k, _ in itens), "cpu_s_media_x_r": round(cm), "cpu_s_teto_x_r": round(ct),
                        "cpu_s_sem_fator_r(HotStuff)": round(sem_fator),
                        "parede_min_8_paralelos_media": round(cm / EFIC / 60, 1), "parede_min_8_paralelos_teto": round(ct / EFIC / 60, 1)}
    tot["media"] += cm
    tot["teto"] += ct
out["total"] = {"celulas": sum(v["celulas"] for v in out["blocos"].values()), "cpu_s_media": round(tot["media"]), "cpu_s_teto": round(tot["teto"]),
                "parede_min_8_paralelos_media": round(tot["media"] / EFIC / 60, 1), "parede_min_8_paralelos_teto": round(tot["teto"] / EFIC / 60, 1),
                "parede_min_teto_x3": round(3 * tot["teto"] / EFIC / 60, 1)}
out["determinismo_extra"] = {"celulas_repetidas": 4, "nota": "va_n31_s01, va_n7_s01, vc_n16_s01, vb_n13_f05_s01 (mesma semente, 2a execucao), fora das estatisticas"}
out["memoria"] = "RSS maximo medido no HotStuff 501 MB; o Tendermint retem O(n) votos por (altura, round): 8 paralelos de n<=31 devem caber; NAO medido"
print(json.dumps(out, indent=1, ensure_ascii=False))
(VAL / "custo-estimado-tm.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))

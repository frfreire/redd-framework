#!/usr/bin/env python3
"""Custo estimado de V-A/V-C a partir de tempos de parede JA MEDIDOS (so leitura; nao simula).
Fonte: resultado.json (tempo_parede_mvp_s + tempo_parede_observador_s) das celulas L=100 ROTATING sem ataque de
confirmacao/, confirmacao-k40/ e piloto (dados-brutos/medicao). Modelo: custo por segundo simulado c(n) = parede/duracao,
interpolado LINEARMENTE em n entre os n medidos (4,16,64,128); n=7,10,13,31 sao interpolacao (declarar).
Fator de -XX:hashCode=2: medido no piloto em L=100 = 4 a 5,5x (HEAD anterior a a65bf83). O commit 76932b9 (pinado na Emenda 1b; contem a65bf83) inclui a correcao
do logger (ADR-0016); o custo atual com a flag NAO foi medido. Reporta o intervalo [1x, 5,5x].
Saida: stdout + custo-estimado.json. Paralelismo: enxame.config.json maxCelulasDeSimulacaoParalelas = 8 (eficiencia 7,43x medida).
"""
import glob
import json
import statistics as st
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
EXP = VAL.parents[1]
EFIC = 7.43  # medido: 3092 s de CPU em 416 s de parede com 8 paralelos (confirmacao-k40/PREREGISTRO.md, sec. 4)
PAR = 8
por_n = {}
for rj in glob.glob(f"{EXP}/piloto-timeout-hotstuff/**/resultado.json", recursive=True):
    d = Path(rj).parent
    try:
        cfg = json.loads((d / "cfg.json").read_text())
        res = json.loads(Path(rj).read_text())
    except Exception:
        continue
    v = cfg.get("variante", {})
    if v.get("L") != 100 or v.get("leader_policy") != "ROTATING" or v.get("attack") or res.get("rc_mvp") != 0:
        continue
    dur = cfg["duracao_s"]
    if dur < 100:
        continue  # celulas curtas superestimam o custo fixo de JVM
    por_n.setdefault(cfg["n"], []).append((res["tempo_parede_mvp_s"] + res["tempo_parede_observador_s"]) / dur)
c = {n: st.fmean(v) for n, v in por_n.items()}
print("custo medido (s de CPU por segundo simulado, 2 JVMs):", {n: round(x, 4) for n, x in sorted(c.items())},
      {n: len(v) for n, v in sorted(por_n.items())})


def interp(n):
    ks = sorted(c)
    if n <= ks[0]:
        return c[ks[0]]
    for a, b in zip(ks, ks[1:]):
        if a <= n <= b:
            return c[a] + (c[b] - c[a]) * (n - a) / (b - a)
    return c[ks[-1]]


def fmax(n):
    return (n - 1) // 3


blocos = {
    "va": [(m, n, 5, 120) for m in (1, 2) for n in (4, 7, 10, 16, 31)],
    "vano": [(m, n, 3, 120) for m in (1, 2) for n in (4, 7, 10, 16, 31)],
    "vc": [(1, n, 5, 120) for n in (4, 16)],   # EMENDA 1b: so L=100 (L=400 era do confronto numerico, inviavel); 10 celulas
    "vc_antigo_L100_e_L400": [(1, n, 5, 120) for _ in (100, 400) for n in (4, 16)],   # so para o diff de custo
    "vb": [(1, n, 10 * (fmax(n) + 2), 300) for n in (4, 7, 10, 13)],
}
out = {"custo_cpu_por_s_sim": {str(n): x for n, x in c.items()}, "blocos": {}}
for b, itens in blocos.items():
    cpu = sum(k * dur * interp(n) for _, n, k, dur in itens)
    cel = sum(k for _, _, k, _ in itens)
    out["blocos"][b] = {"celulas": cel, "cpu_s_sem_flag": round(cpu), "cpu_s_flag_5_5x": round(cpu * 5.5),
                        "parede_s_8_paralelos_sem_flag": round(cpu / EFIC), "parede_s_8_paralelos_flag_5_5x": round(cpu * 5.5 / EFIC)}
    print(b, out["blocos"][b])
tot_cpu = sum(out["blocos"][b]["cpu_s_sem_flag"] for b in ("va", "vano", "vc"))
tot_cpu_antigo = sum(out["blocos"][b]["cpu_s_sem_flag"] for b in ("va", "vano", "vc_antigo_L100_e_L400"))
out["antes_da_emenda_1b"] = {"celulas": sum(out["blocos"][b]["celulas"] for b in ("va", "vano", "vc_antigo_L100_e_L400")),
                             "cpu_s_sem_flag": tot_cpu_antigo, "parede_min_flag_5_5x": round(tot_cpu_antigo * 5.5 / EFIC / 60, 1)}
out["determinismo_extra"] = {"celulas_repetidas": 4, "nota": "4 execucoes extras (mesma semente), fora das estatisticas"}
out["va_mais_vano"] = {"cpu_s_sem_flag": tot_cpu, "parede_min_sem_flag": round(tot_cpu / EFIC / 60, 1),
                       "parede_min_flag_5_5x": round(tot_cpu * 5.5 / EFIC / 60, 1)}
print("V-A + V-A por no + V-C:", out["va_mais_vano"])
(VAL / "custo-estimado.json").write_text(json.dumps(out, indent=1))

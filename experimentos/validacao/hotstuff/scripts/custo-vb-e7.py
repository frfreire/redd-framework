#!/usr/bin/env python3
"""E7: custo estimado de V-B (180 celulas, 300 s simulados, 8 paralelos). SO LEITURA de resultado.json/cfg.json ja commitados de V-A/V-C
(26c98f6); NAO simula. Substitui o numero de custo-estimado.json (956 s), que usava o fator pessimista 5,5x de hashCode=2: os dados de
V-A/V-C mostram o custo REAL (com a flag, em 76932b9).
Modelo: custo por s simulado c(n) = (parede_mvp + parede_observador)/duracao, media das celulas INDIVIDUAL honestas (va, vc) por n medido
(4, 7, 10, 16, 31). n=13 NAO foi medido: uso o MAIOR entre n=10 e n=16 (conservador; a interpolacao linear daria menos).
Limite declarado: celulas de V-B tem ataque (EQUIVOCATION) e duracao 300 s (V-A: 120 s); o custo de celula COM ataque nao foi medido em
76932b9. Celula que perde vivacidade tende a ser MAIS barata (poucos eventos), mas celula viva com equivocacao tem mais pacotes: por isso
reporto [media, teto com o maximo observado por n, teto x 3].
Uso: custo-vb-e7.py   (grava custo-vb-e7.json)
"""
import glob
import json
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
EFIC = 7.43
PAR = 8
por_n = {}
for bloco in ("va", "vc"):
    for rj in glob.glob(str(VAL / "dados-brutos" / bloco / "*" / "resultado.json")):
        d = Path(rj).parent
        cfg, res = json.loads((d / "cfg.json").read_text()), json.loads(Path(rj).read_text())
        if cfg["variante"]["signature_mode"] != "INDIVIDUAL" or res["rc_mvp"] != 0 or "-XX:hashCode=2" not in cfg["jvm_flags"]:
            continue
        por_n.setdefault(cfg["n"], []).append((res["tempo_parede_mvp_s"] + res["tempo_parede_observador_s"]) / cfg["duracao_s"])
med = {n: sum(v) / len(v) for n, v in por_n.items()}
mx = {n: max(v) for n, v in por_n.items()}
med[13] = max(med[10], med[16])
mx[13] = max(mx[10], mx[16])
esp = json.loads((VAL / "celulas-esperadas.json").read_text())["celulas"]["vb"]
cont = {}
for c in esp:
    cont[c["n"]] = cont.get(c["n"], 0) + 1
cpu_med = sum(cont[n] * 300 * med[n] for n in cont)
cpu_max = sum(cont[n] * 300 * mx[n] for n in cont)
out = {"fonte": "dados-brutos/va e vc (INDIVIDUAL, rc_mvp=0, com -XX:hashCode=2, commit 76932b9)", "celulas_vb": len(esp),
       "celulas_por_n": cont, "n_medidos": {str(n): len(v) for n, v in sorted(por_n.items())},
       "custo_cpu_s_por_s_simulado_media": {str(n): round(med[n], 5) for n in sorted(med)},
       "custo_cpu_s_por_s_simulado_max": {str(n): round(mx[n], 5) for n in sorted(mx)},
       "n13": "NAO medido: max(n=10, n=16)",
       "cpu_total_s_media": round(cpu_med), "cpu_total_s_teto_max_por_n": round(cpu_max),
       "parede_min_8_paralelos_media": round(cpu_med / EFIC / 60, 1), "parede_min_8_paralelos_teto": round(cpu_max / EFIC / 60, 1),
       "parede_min_8_paralelos_teto_x3": round(3 * cpu_max / EFIC / 60, 1),
       "eficiencia_de_paralelismo_usada": EFIC, "paralelismo": PAR,
       "pior_celula_estimada_s": round(300 * max(mx.values())),
       "limites": ["custo de celula COM ataque nao medido em 76932b9", "duracao 300 s vs 120 s de V-A (escala linear assumida)",
                   "n=13 nao medido", "build de V-A/V-C reaproveitado (conferir-build-vb.py); sem tempo de build"],
       "comparacao_com_estimativa_antiga": "custo-estimado.json dava 956 s de parede (fator pessimista 5,5x)"}
(VAL / "custo-vb-e7.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))
print(json.dumps(out, indent=1, ensure_ascii=False))

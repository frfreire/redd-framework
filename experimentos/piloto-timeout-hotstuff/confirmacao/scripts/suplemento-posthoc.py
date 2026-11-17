#!/usr/bin/env python3
"""SUPLEMENTO POS-HOC da confirmacao (rotulado; NAO altera o veredito de A3', que e o de analise-confirmacao.json).

(1) autocorrelacao com a media REMOVIDA POR CELULA (separa dependencia serial de deriva de nivel entre celulas/ao longo da celula);
    e a media de D por quinto da celula (deriva dentro da celula);
(2) altura do pico de cada n: Fmax-Fmin, Rmin, nos que finalizaram, vizinhas (h-1, h+1);
(3) empilhamento com o piloto: ROTATING, L=100, medicao (semente_base 12345) + verificacao m=3 (22345) + confirmacao (42345)
    = 40 sementes por n (N ~ 86 mil): UB por estatistica de ordem (e_sup(N) = 2 para N entre 72 245 e 87 669).
    A verificacao m=2 NAO entra (mesmas sementes de m=3; duplicaria amostras). Mesma versao de simulador (9f305dd em src/pom).
    Dentro do piloto o --hs-view-timeout diferia (60 s na medicao, T na verificacao); como nenhum timeout disparou, a dinamica
    simulada e a mesma (conferido: timeouts_disparados_total = 0 em todas as celulas empilhadas).
"""
import csv
import gzip
import importlib.util
import json
import math
import statistics as st
from pathlib import Path

CONF = Path(__file__).resolve().parent.parent
PIL = CONF.parent
spec = importlib.util.spec_from_file_location("ac", CONF / "scripts" / "analisar-confirmacao.py")
ac = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ac)
H0 = 50


def ler(d):
    with gzip.open(d / "obs" / "observador-por-altura.csv.gz", "rt") as f:
        rows = list(csv.DictReader(l for l in f if not l.startswith("#")))
    return {int(r["height"]): r for r in rows}


def serie(d):
    r = ler(d)
    return [float(r[h]["Rmax_s"]) for h in sorted(r) if h > H0]


def acorr_por_celula(series, lag):
    num = den = 0.0
    tot = []
    for s in series:
        mu = st.fmean(s)
        tot += [(x - mu) for x in s]
    var = sum(x * x for x in tot) / len(tot)
    for s in series:
        mu = st.fmean(s)
        for i in range(len(s) - lag):
            num += (s[i] - mu) * (s[i + lag] - mu)
        den += len(s) - lag
    return (num / den) / var


out = {"rotulo": "POS-HOC, nao altera o veredito de A3'", "por_n": {}}
for n in (4, 16, 64, 128):
    cdirs = sorted((CONF / "dados-brutos" / "confirmacao").glob(f"conf_rotating_L100_n{n}_*"))
    ser = [serie(d) for d in cdirs]
    quintos = []
    for s in ser:
        k = len(s) // 5
        quintos.append([st.fmean(s[i * k:(i + 1) * k]) for i in range(5)])
    media_quintos = [st.fmean(q[i] for q in quintos) for i in range(5)]
    medias_cel = [st.fmean(s) for s in ser]
    # pico
    mx, cd, h = max((max(s), d, [hh for hh in sorted(ler(d)) if hh > H0][s.index(max(s))]) for s, d in zip(ser, cdirs))
    r = ler(cd)
    viz = {}
    for hh in (h - 1, h, h + 1):
        x = r.get(hh)
        if x:
            viz[hh] = {"Rmax_s": float(x["Rmax_s"]), "Rmin_s": float(x["Rmin_s"]),
                       "Fmax_menos_Fmin_s": float(x["Fmax_s"]) - float(x["Fmin_s"]), "n_finalizaram": int(x["n_finalizaram"]),
                       "no_do_Rmax": int(x["Rmax_node"])}
    # empilhamento com o piloto (40 sementes)
    pil = sorted((PIL / "dados-brutos" / "medicao").glob(f"med_rotating_L100_n{n}_*")) + \
        sorted((PIL / "dados-brutos" / "verificacao").glob(f"ver_m3_rotating_L100_n{n}_*"))
    tmo = 0
    for d in pil:
        tmo += json.loads((d / "obs" / "observador-resumo.json").read_text())["timeouts_disparados_total"]
    todas = [x for s in ser for x in s] + [x for d in pil for x in serie(d)]
    s40 = sorted(todas)
    N = len(s40)
    ic = ac.ic_ordem(s40)
    T = ac.T_PILOTO[n]
    out["por_n"][n] = {
        "autocorr_media_removida_por_celula": {str(k): acorr_por_celula(ser, k) for k in range(1, 6)},
        "desvio_padrao_das_medias_por_celula": st.pstdev(medias_cel),
        "media_de_D_por_quinto_da_celula": media_quintos,
        "pico": {"celula": cd.name, "altura": h, "D": mx, "vizinhas": viz},
        "empilhado_40_sementes": {"celulas_piloto": len(pil), "celulas_confirmacao": len(cdirs), "N": N,
                                  "timeouts_piloto_nessas_celulas": tmo, "p99_99": ac.nr(s40, 0.9999)[0],
                                  "IC95_ordem": ic, "max": s40[-1], "T_piloto": T, "T_meio": T / 2,
                                  "UB_sobre_T": (ic["superior"] / T if ic["superior"] else None),
                                  "veredito_A3_com_40_sementes": ("PASSA" if ic["superior"] <= T / 2 else "FALHA")},
    }
    e = out["por_n"][n]
    print(n, "ac1(media por celula)=%.3f" % e["autocorr_media_removida_por_celula"]["1"],
          "quintos", [round(x, 4) for x in media_quintos], "sd medias cel %.4f" % e["desvio_padrao_das_medias_por_celula"])
    print("   pico", cd.name[-3:], "h", h, "D=%.4f" % mx, {k: (round(v["Fmax_menos_Fmin_s"], 3), round(v["Rmin_s"], 3), v["n_finalizaram"]) for k, v in viz.items()})
    em = e["empilhado_40_sementes"]
    print("   40 sem: N", N, "p99,99=%.4f" % em["p99_99"], "UB=%.4f" % em["IC95_ordem"]["superior"], "e_sup", ic["e_sup"], "T/2=%.4f" % (T / 2),
          em["veredito_A3_com_40_sementes"], "timeouts piloto", tmo)
(CONF / "suplemento-posthoc.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))

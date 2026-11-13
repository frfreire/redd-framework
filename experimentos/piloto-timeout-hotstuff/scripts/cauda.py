#!/usr/bin/env python3
"""Cauda da duracao de visao D (INDICATIVO, nao e medicao do piloto). Somente biblioteca padrao.

Substitui a versao anterior (ajuste de Hill nos 0,5% maiores + extrapolacao), que subestimava a cauda: o Hill capta o
corpo da distribuicao, nao a cauda assintotica. Aqui:
 (1) contagens EMPIRICAS de D acima de limiares, por n, empilhando medicao (semente_base 12345) e verificacao m=3
     (semente_base 22345), politica ROTATING, L=100 (STABLE usa as mesmas sementes e reproduz quase as mesmas caudas;
     NAO sao amostras independentes);
 (2) ordem de grandeza TEORICA do numero esperado de visoes com D > D_media + e, sob a HIPOTESE (derivada da leitura de
     LAN100MNetworkStats: latencia por pacote ~ Pareto(xm = 0,8 x 20 ms, alfa = 5), banda constante) de que o excesso de D
     sobre a media vem de UM pacote lento que esta no caminho critico de UMA replica: P(D > D_media + e) ~ n * k * (xm/e)^alfa
     por visao, k = 4 pacotes (um por fase) por replica. k e uma suposicao declarada, nao medida.
 (3) comparacao (1) x (2). RESULTADO: a hipotese SUPERESTIMA as contagens observadas em 5 a 20 vezes para excessos
     pequenos/moderados (ex.: n=4, excesso 0,10 s: observado 4, hipotese ~73). Logo ela NAO reproduz a cauda e foi
     DESCARTADA: nenhuma extrapolacao alem do observado (nem P(D>T)) e reportada ou deve ser inferida daqui.
Uso: cauda.py <dir_brutos_medicao> <dir_brutos_verificacao> --saida cauda.json
"""
import argparse
import csv
import glob
import gzip
import json
from pathlib import Path

XM = 0.8 * 0.02
ALFA = 5.0
K = 4


def lerD(d):
    with gzip.open(d / "obs" / "observador-por-altura.csv.gz", "rt") as f:
        return [float(x["Rmax_s"]) for x in csv.DictReader(f) if int(x["height"]) > 50]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("medicao")
    ap.add_argument("verificacao")
    ap.add_argument("--saida", required=True)
    a = ap.parse_args()
    T3 = {4: 0.918, 16: 1.09, 64: 2.065, 128: 3.396}
    out = {"hipotese": {"xm_s": XM, "alfa": ALFA, "k_pacotes_por_replica": K,
                        "fonte": "LAN100MNetworkStats: scale=(shape-1)/shape*0.02, shape=5"}, "por_n": {}}
    for n in (4, 16, 64, 128):
        D = []
        for base, pref in ((a.medicao, "med_rotating_L100"), (a.verificacao, "ver_m3_rotating_L100")):
            for d in sorted(glob.glob(f"{base}/{pref}_n{n}_*")):
                D += lerD(Path(d))
        N = len(D)
        media = sum(D) / N
        linhas = []
        for e in (0.05, 0.10, 0.15, 0.20, 0.30, 0.45, 0.60):
            lim = media + e
            obs = sum(1 for v in D if v > lim)
            teo = N * n * K * (XM / e) ** ALFA
            linhas.append({"excesso_e_s": e, "limiar_s": round(lim, 4), "observado": obs, "teorico_esperado": round(teo, 2)})
        out["por_n"][n] = {"N_visoes": N, "D_media": media, "D_max": max(D), "tabela": linhas,
                           "T_m3": T3[n], "excesso_T_menos_media_s": T3[n] - media}
        print(f"n={n:3d} N={N} media={media:.3f} max={max(D):.3f}")
        for l in linhas:
            print(f"   D > media+{l['excesso_e_s']:.2f} ({l['limiar_s']:.3f}s): observado {l['observado']:6d}  hipotese {l['teorico_esperado']:10.2f}")
    Path(a.saida).write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()

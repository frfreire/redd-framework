#!/usr/bin/env python3
"""Resumo por celula e por grupo das celulas COM ATAQUE (EQUIVOCATION). Somente biblioteca padrao.

Com falha, o criterio de zero timeouts NAO se aplica: so se registra o comportamento. Por celula: rc do MVPComparison
(falha alta quando 0 blocos), alturas finalizadas, visao final, timeouts disparados, razao timeouts/visoes e, quando
houve blocos, D (duracao de visao, max entre replicas) e latencias de bloco do CSV oficial. Quando ROTATING com f=1:
quantas visoes tinham lider bizantino e quanto durou a visao seguinte a um timeout.
Uso: analisar-ataque.py <dir_brutos> --manifesto m.json --saida resumo.json
"""
import argparse
import csv
import gzip
import json
import math
import statistics as st
from collections import defaultdict
from pathlib import Path


def lerz(p):
    with gzip.open(p, "rt", encoding="utf-8") as f:
        return list(csv.DictReader(l for l in f if not l.startswith("#")))


def q(xs, p):
    s = sorted(xs)
    return s[max(1, math.ceil(p * len(s) - 1e-9)) - 1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("brutos")
    ap.add_argument("--manifesto", required=True)
    ap.add_argument("--saida", required=True)
    a = ap.parse_args()
    man = json.loads(Path(a.manifesto).read_text())
    celulas, grupos = [], defaultdict(list)
    for cid in man["celulas"]:
        d = Path(a.brutos) / cid
        cfg = json.loads((d / "cfg.json").read_text())
        res = json.loads((d / "resultado.json").read_text())
        rs = json.loads((d / "obs" / "observador-resumo.json").read_text())
        n, dur, T = cfg["n"], cfg["duracao_s"], cfg["variante"]["view_timeout_s"]
        c = {"id": cid, "grupo": cfg["grupo"], "n": n, "f": cfg["byzantinos"], "estrato": cfg["estrato"],
             "politica": cfg["variante"]["leader_policy"], "semente": cfg["semente"], "duracao_s": dur,
             "view_timeout_s": T, "byzantine_ids": rs["byzantine_ids"], "rc_mvp": res["rc_mvp"],
             "rc_observador": res["rc_observador"], "altura_max": rs["altura_maxima_finalizada"],
             "visao_max": rs["visao_corrente_maxima"], "timeouts_disparados": rs["timeouts_disparados_total"],
             "propostas_equivocadas": rs["propostas_equivocadas"], "votos_equivocados": rs["votos_equivocados"],
             "csv_oficial_igual_ao_observador": res.get("md5_sem_hashcode_mvp") == res.get("md5_sem_hashcode_obs")
             if res["rc_mvp"] == 0 else None}
        c["timeouts_por_replica_por_visao"] = rs["timeouts_disparados_total"] / max(1, n * rs["visao_corrente_maxima"])
        c["vazao_alturas_por_s"] = rs["altura_maxima_finalizada"] / dur
        if rs["altura_maxima_finalizada"] > 0:
            alt = lerz(d / "obs" / "observador-por-altura.csv.gz")
            D = [float(r["Rmax_s"]) for r in alt]
            c["D"] = {"N": len(D), "media": st.fmean(D), "p50": q(D, .5), "p99": q(D, .99), "max": max(D),
                      "n_acima_de_T_2": sum(1 for x in D if x >= T / 2),
                      "n_acima_de_T": sum(1 for x in D if x >= T)}
            c["primeira_finalizacao_s"] = float(alt[0]["Fmin_s"])
        else:
            c["D"] = None
            c["primeira_finalizacao_s"] = None
        celulas.append(c)
        grupos[c["grupo"]].append(c)
    out = {"n_celulas": len(celulas), "celulas": celulas, "grupos": {}}
    for g, cs in sorted(grupos.items()):
        out["grupos"][g] = {
            "n_celulas": len(cs), "rc_mvp_diferente_de_zero": sum(1 for c in cs if c["rc_mvp"] != 0),
            "celulas_sem_nenhum_bloco": sum(1 for c in cs if c["altura_max"] == 0),
            "altura_max_media": st.fmean(c["altura_max"] for c in cs),
            "vazao_media_alturas_por_s": st.fmean(c["vazao_alturas_por_s"] for c in cs),
            "timeouts_disparados_total": sum(c["timeouts_disparados"] for c in cs),
            "timeouts_por_replica_por_visao_media": st.fmean(c["timeouts_por_replica_por_visao"] for c in cs),
            "D_max_entre_celulas": max((c["D"]["max"] for c in cs if c["D"]), default=None),
            "D_media_entre_celulas": st.fmean([c["D"]["media"] for c in cs if c["D"]]) if any(c["D"] for c in cs) else None}
        print(f"{g:42s} cel={len(cs):2d} sem_bloco={out['grupos'][g]['celulas_sem_nenhum_bloco']:2d} "
              f"alt_media={out['grupos'][g]['altura_max_media']:8.1f} vazao={out['grupos'][g]['vazao_media_alturas_por_s']:7.3f}/s "
              f"timeouts={out['grupos'][g]['timeouts_disparados_total']:7d} to/rep/visao={out['grupos'][g]['timeouts_por_replica_por_visao_media']:.3f} "
              f"Dmax={out['grupos'][g]['D_max_entre_celulas']}")
    Path(a.saida).write_text(json.dumps(out, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Analise do piloto de timeout do HotStuff. Somente biblioteca padrao.

Uso: analisar.py <dir_brutos> --manifestos <m1> [<m2> ...] --saida <arquivo.json> [--descartar-alturas 50]

Por celula: le obs/observador-por-altura.csv.gz (Rmax_s = duracao de visao D(h), maximo entre replicas),
obs/observador-resumo.json (contadores de timeout), o CSV bruto do MVPComparison (Tb e Dprimeiro) e resultado.json.
Por grupo (politica, L, n, ataque): empilha as amostras pos-transiente das sementes e calcula
media, p50, p99, p99,99 (nearest-rank ceil(p*N)), maximo, IC95 por bootstrap percentil (B=2000, semente 20261112)
e IC95 exato por estatistica de ordem (binomial). p99,99 so e "estimavel" se N >= 10^4.
Confere celulas esperadas x obtidas, equivalencia observador x MVPComparison (md5 do CSV sem BlockHashCode) e
soma dos timeouts disparados.
"""
import argparse
import csv
import gzip
import json
import math
import random
import statistics as st
import sys
from collections import defaultdict
from pathlib import Path

B_BOOT = 2000
SEMENTE_BOOT = 20261112
NIVEL = 0.95


def nearest_rank(xs_sorted, p):
    n = len(xs_sorted)
    r = max(1, math.ceil(p * n - 1e-9))
    return xs_sorted[r - 1], r


def binom_cdf_tabela(n, q, kmax):
    """P(Bin(n,q) <= k) para k=0..kmax, por recorrencia estavel em log."""
    out = []
    lp = n * math.log1p(-q)  # log P(0)
    acc = 0.0
    p = math.exp(lp)
    for k in range(0, kmax + 1):
        acc += p
        out.append(min(acc, 1.0))
        if k < n:
            p = p * (n - k) / (k + 1) * q / (1 - q)
    return out


def ic_ordem(xs_sorted, q, nivel=NIVEL):
    """IC exato para o quantil q por estatistica de ordem. Devolve (inferior, superior, rank_inf, rank_sup);
    superior=None se o maximo da amostra nao basta (quantil nao limitado por cima)."""
    n = len(xs_sorted)
    a = (1 - nivel) / 2
    kmax = min(n, int(n * (1 - q) * 6) + 60) if q > 0.9 else n
    if q <= 0.9:
        kmax = n
    # #observacoes <= x_q ~ Bin(n,q)  =>  P(X(l) <= x_q) = P(Bin >= l)
    # limite inferior: maior l com P(Bin(n,q) <= l-1) <= a  (usa-se a cauda esquerda; para q alto, l ~ n*q)
    # calculo direto sobre a cauda direita (poucos termos): P(Bin >= j) = soma_{i>=j} pmf(i), via complemento de contagem de excedentes
    # contagem de excedentes E = n - Bin ~ Bin(n, 1-q)
    r = 1 - q
    cdfE = binom_cdf_tabela(n, r, min(n, kmax))
    # X(l) <= x_q  <=>  E <= n - l ; queremos P(E <= n-l) >= 1 - a  => limite inferior: menor e=n-l com cdfE[e] >= 1-a
    e_inf = next((e for e, c in enumerate(cdfE) if c >= 1 - a), None)
    rank_inf = n - e_inf if e_inf is not None else None
    # x_q <= X(u) <=> E >= n-u+1  ; P(E >= n-u+1) >= 1-a  => P(E <= n-u) <= a  : maior e'=n-u com cdfE[e'] <= a
    cand = [e for e, c in enumerate(cdfE) if c <= a]
    if cand:
        e_sup = max(cand)
        rank_sup = n - e_sup
        sup = xs_sorted[min(rank_sup, n) - 1] if rank_sup >= 1 else None
        if e_sup < 0:
            sup = None
    else:
        sup, rank_sup = None, None  # nem o maximo limita
    inf = xs_sorted[rank_inf - 1] if rank_inf else None
    return inf, sup, rank_inf, rank_sup


def boot_quantil(xs, p, B=B_BOOT, seed=SEMENTE_BOOT):
    rng = random.Random(seed)
    n = len(xs)
    qs = []
    r = max(1, math.ceil(p * n - 1e-9))
    for _ in range(B):
        amostra = sorted(rng.choices(xs, k=n))
        qs.append(amostra[r - 1])
    qs.sort()
    lo = qs[int(0.025 * B)]
    hi = qs[min(B - 1, int(0.975 * B))]
    return lo, hi


def resumo(xs):
    s = sorted(xs)
    n = len(s)
    out = {"N": n, "media": st.fmean(s), "p50": nearest_rank(s, 0.50)[0], "p99": nearest_rank(s, 0.99)[0],
           "max": s[-1]}
    q4, rk = nearest_rank(s, 0.9999)
    out["p99_99_nearest_rank"] = q4
    out["p99_99_posto"] = rk
    out["p99_99_estimavel"] = n >= 10_000
    return out, s


def ler_csv_gz(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        linhas = [l for l in f if not l.startswith("#")]
    return list(csv.DictReader(linhas))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("brutos")
    ap.add_argument("--manifestos", nargs="+", required=True)
    ap.add_argument("--saida", required=True)
    ap.add_argument("--descartar-alturas", type=int, default=50)
    ap.add_argument("--sem-bootstrap", action="store_true")
    a = ap.parse_args()
    brutos = Path(a.brutos)
    esperadas = []
    for m in a.manifestos:
        esperadas += json.loads(Path(m).read_text())["celulas"]
    celulas, problemas = {}, []
    for cid in esperadas:
        d = brutos / cid
        try:
            cfg = json.loads((d / "cfg.json").read_text())
            res = json.loads((d / "resultado.json").read_text())
            rs = json.loads((d / "obs" / "observador-resumo.json").read_text())
        except Exception as e:  # celula faltante nunca e descartada em silencio
            problemas.append({"id": cid, "erro": f"arquivo ausente/ilegivel: {e}"})
            continue
        if res["rc_mvp"] != 0 or res["rc_observador"] != 0:
            problemas.append({"id": cid, "erro": f"rc {res['rc_mvp']},{res['rc_observador']}"})
            continue
        equiv = res.get("md5_sem_hashcode_mvp") == res.get("md5_sem_hashcode_obs") and res["blocos_mvp"] == res["blocos_obs"]
        alt = ler_csv_gz(d / "obs" / "observador-por-altura.csv.gz")
        D = {int(r["height"]): float(r["Rmax_s"]) for r in alt}
        F = {int(r["height"]): float(r["Fmin_s"]) for r in alt}
        off = ler_csv_gz(d / res["csv_mvp"])
        tb = {int(r["BlockHeight"]): float(r["BlockFinalizationTime"]) for r in off}
        tt = {int(r["BlockHeight"]): float(r["Time"]) for r in off}
        celulas[cid] = {"cfg": cfg, "res": res, "rs": rs, "equiv": equiv, "D": D, "F": F, "tb": tb, "tt": tt}
    grupos = defaultdict(list)
    for cid, c in celulas.items():
        v = c["cfg"]["variante"]
        grupos[(v["leader_policy"], v["L"], c["cfg"]["n"], v.get("attack") or "NONE",
                c["cfg"]["byzantinos"])].append(cid)
    h0 = a.descartar_alturas
    saida = {"descartar_alturas": h0, "bootstrap": {"B": B_BOOT, "semente": SEMENTE_BOOT, "nivel": NIVEL},
             "n_esperadas": len(esperadas), "n_obtidas": len(celulas), "problemas": problemas, "grupos": []}
    for chave in sorted(grupos):
        pol, L, n, atk, byz = chave
        ids = sorted(grupos[chave])
        D_all, tb_all, dp_all, trans_in, trans_out, t0s = [], [], [], [], [], []
        vis_por_cel, timeouts, stale, equiv_ok = [], 0, 0, True
        saltos = 0
        for cid in ids:
            c = celulas[cid]
            hs = sorted(c["D"])
            maxh = hs[-1]
            vis_por_cel.append(sum(1 for h in hs if h > h0))
            D_all += [c["D"][h] for h in hs if h > h0]
            trans_in += [c["D"][h] for h in hs if 2 <= h <= h0]
            trans_out += [c["D"][h] for h in hs if h > h0]
            tb_all += [c["tb"][h] for h in sorted(c["tb"]) if h > h0]
            hh = sorted(c["tt"])
            dp_all += [c["tt"][h] - c["tt"][h - 1] for h in hh if h > h0 and (h - 1) in c["tt"]]
            t0s.append(c["F"].get(h0, float("nan")))
            timeouts += c["rs"]["timeouts_disparados_total"]
            stale += c["rs"]["timeouts_obsoletos_STALE_total"]
            saltos += c["rs"]["finalizacoes_com_salto_de_altura"]
            equiv_ok &= c["equiv"]
        r, s = resumo(D_all)
        g = {"politica": pol, "L": L, "n": n, "ataque": atk, "byzantine_count": byz, "celulas": ids,
             "n_celulas": len(ids), "seeds": [celulas[i]["cfg"]["semente"] for i in ids],
             "view_timeout_passado_s": celulas[ids[0]]["cfg"]["variante"]["view_timeout_s"],
             "duracao_sim_s": celulas[ids[0]]["cfg"]["duracao_s"],
             "visoes_pos_transiente_por_celula": {"min": min(vis_por_cel), "max": max(vis_por_cel)},
             "D": r, "D_media_transiente_2a50": st.fmean(trans_in), "D_media_regime": st.fmean(trans_out),
             "t0_s_medio": st.fmean(t0s), "t0_s_max": max(t0s),
             "timeouts_disparados_total": timeouts, "timeouts_obsoletos_STALE_total": stale,
             "finalizacoes_com_salto": saltos, "csv_oficial_igual_ao_observador": equiv_ok}
        s_tb = sorted(tb_all)
        s_dp = sorted(dp_all)
        g["Tb"] = {"N": len(s_tb), "media": st.fmean(s_tb), "p50": nearest_rank(s_tb, .5)[0],
                   "p99": nearest_rank(s_tb, .99)[0], "p99_99_nearest_rank": nearest_rank(s_tb, .9999)[0], "max": s_tb[-1]}
        g["Dprimeiro"] = {"N": len(s_dp), "media": st.fmean(s_dp), "p50": nearest_rank(s_dp, .5)[0],
                          "p99": nearest_rank(s_dp, .99)[0], "p99_99_nearest_rank": nearest_rank(s_dp, .9999)[0],
                          "max": s_dp[-1]}
        inf, sup, ri, rs_ = ic_ordem(s, 0.9999)
        g["D"]["p99_99_IC95_ordem"] = {"inferior": inf, "superior": sup, "rank_inf": ri, "rank_sup": rs_}
        if not a.sem_bootstrap and r["N"] >= 2:
            lo, hi = boot_quantil(D_all, 0.9999)
            g["D"]["p99_99_IC95_bootstrap"] = {"inferior": lo, "superior": hi}
        saida["grupos"].append(g)
        print(f"{pol[:3]} L={L} n={n} {atk} f={byz}: cel={len(ids)} N={r['N']} D media={r['media']:.4f} "
              f"p99.99={r['p99_99_nearest_rank']:.4f} max={r['max']:.4f} timeouts={timeouts} equiv={equiv_ok}",
              flush=True)
    Path(a.saida).write_text(json.dumps(saida, indent=1, ensure_ascii=False))
    print(f"esperadas={len(esperadas)} obtidas={len(celulas)} problemas={len(problemas)}")
    sys.exit(0 if not problemas else 3)


if __name__ == "__main__":
    main()

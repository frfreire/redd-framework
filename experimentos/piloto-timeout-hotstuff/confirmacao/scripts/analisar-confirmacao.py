#!/usr/bin/env python3
"""Analise da CONFIRMACAO (criterio A3' de CRITERIOS-congelados.md). So biblioteca padrao.

Por n (grupo = ROTATING, L=100, n): empilha D(h)=Rmax_s das 20 sementes, h>50; p99,99 nearest-rank (posto ceil(0,9999 N));
IC95 bilateral por estatistica de ordem (implementacao propria, conferida contra ic_ordem do piloto);
verifica UB <= T/2 para T(a) = T do piloto (0,918/1,090/2,065/3,396) e T(b) = 3 x p99,99 da propria confirmacao;
timeouts espurios, conferencia visao_final - altura_final, equivalencia MD5, esperadas x obtidas, diagnostico de
independencia (autocorrelacao, pares adjacentes, concentracao do maximo). Auto-checagem da aritmetica de N (36887,
55715, 72246). Saida: analise-confirmacao.json.
"""
import csv
import gzip
import importlib.util
import json
import math
import statistics as st
import sys
from collections import Counter
from pathlib import Path

CONF = Path(__file__).resolve().parent.parent
PIL = CONF.parent
BRUTOS = CONF / "dados-brutos" / "confirmacao"
H0 = 50
Q = 0.9999
R = 1 - Q
ALFA2 = 0.025
T_PILOTO = {4: 0.918, 16: 1.09, 64: 2.065, 128: 3.396}
D_HAT_PILOTO = {4: 0.3058, 16: 0.3631, 64: 0.6881, 128: 1.1318}  # maior p99,99 entre as duas politicas (RELATORIO, 5.2)
N_ALVO = 36887
BOOT_B, BOOT_SEED = 2000, 20261112


def pmf_cdf(N, r, kmax):
    """P(Bin(N,r) <= k), k=0..kmax."""
    p = math.exp(N * math.log1p(-r))
    acc, out = 0.0, []
    for k in range(kmax + 1):
        acc += p
        out.append(acc)
        p = p * (N - k) / (k + 1) * r / (1 - r)
    return out


def e_sup(N, r=R):
    """maior e com P(Bin(N,r)<=e) <= 0,025; -1 se nem e=0 (maximo nao limita)."""
    cdf = pmf_cdf(N, r, 200)
    e = -1
    for k, c in enumerate(cdf):
        if c <= ALFA2:
            e = k
        else:
            break
    return e


def e_inf(N, r=R):
    """menor e' com P(Bin(N,r)<=e') >= 0,975."""
    cdf = pmf_cdf(N, r, 400)
    for k, c in enumerate(cdf):
        if c >= 1 - ALFA2:
            return k
    return None


def n_min_maximo():
    return math.ceil(math.log(ALFA2) / math.log1p(-R))


def ic_ordem(s):
    """s ordenado. Devolve dict com inferior/superior e postos (base 1)."""
    N = len(s)
    es, ei = e_sup(N), e_inf(N)
    rk_sup = N - es if es >= 0 else None
    rk_inf = N - ei if ei is not None else None
    return {"N": N, "e_sup": es, "e_inf": ei, "posto_sup": rk_sup, "posto_inf": rk_inf,
            "superior": s[rk_sup - 1] if rk_sup else None, "inferior": s[rk_inf - 1] if rk_inf else None}


def nr(s, p):
    r = max(1, math.ceil(p * len(s) - 1e-9))
    return s[r - 1], r


def carregar_piloto_ic():
    spec = importlib.util.spec_from_file_location("analisar_piloto", PIL / "scripts" / "analisar.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def ler_gz(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        return list(csv.DictReader(l for l in f if not l.startswith("#")))


def autocorr(series_por_celula, lag):
    """autocorrelacao amostral de lag k, media global removida (agregada sobre celulas)."""
    todos = [x for s in series_por_celula for x in s]
    mu = st.fmean(todos)
    var = sum((x - mu) ** 2 for x in todos) / len(todos)
    num = den = 0.0
    for s in series_por_celula:
        for i in range(len(s) - lag):
            num += (s[i] - mu) * (s[i + lag] - mu)
        den += len(s) - lag
    return (num / den) / var


def adjacentes(series_por_celula, limiar):
    exc = pares = pos = 0
    for s in series_por_celula:
        f = [x > limiar for x in s]
        exc += sum(f)
        pares += sum(1 for i in range(len(f) - 1) if f[i] and f[i + 1])
        pos += len(f) - 1
    Ntot = sum(len(s) for s in series_por_celula)
    p = exc / Ntot
    return {"limiar": limiar, "excedencias": exc, "pares_adjacentes_observados": pares,
            "pares_adjacentes_esperados_sob_independencia": pos * p * p}


def main():
    # auto-checagem da aritmetica de N (antes de olhar qualquer dado)
    chk = {"N_min_maximo_p99_99": n_min_maximo(), "e_sup(36887)": e_sup(36887), "e_sup(36886)": e_sup(36886),
           "e_sup(55714)": e_sup(55714), "e_sup(55715)": e_sup(55715), "e_sup(72244)": e_sup(72244),
           "e_sup(72245)": e_sup(72245)}
    assert chk["N_min_maximo_p99_99"] == 36887 and chk["e_sup(36886)"] == -1 and chk["e_sup(36887)"] == 0
    assert chk["e_sup(55714)"] == 0 and chk["e_sup(55715)"] == 1 and chk["e_sup(72244)"] == 1 and chk["e_sup(72245)"] == 2
    piloto = carregar_piloto_ic()

    man = json.loads((CONF / "celulas" / "confirmacao-L100" / "manifesto.json").read_text())
    esperadas = man["celulas"]
    celulas, problemas = {}, []
    for cid in esperadas:
        d = BRUTOS / cid
        try:
            cfg = json.loads((d / "cfg.json").read_text())
            res = json.loads((d / "resultado.json").read_text())
            rs = json.loads((d / "obs" / "observador-resumo.json").read_text())
            alt = ler_gz(d / "obs" / "observador-por-altura.csv.gz")
        except Exception as e:
            problemas.append({"id": cid, "erro": str(e)})
            continue
        if res["rc_mvp"] != 0 or res["rc_observador"] != 0:
            problemas.append({"id": cid, "erro": f"rc {res['rc_mvp']},{res['rc_observador']}"})
            continue
        D = {int(r["height"]): float(r["Rmax_s"]) for r in alt}
        equiv = res.get("md5_sem_hashcode_mvp") == res.get("md5_sem_hashcode_obs") and res["blocos_mvp"] == res["blocos_obs"]
        celulas[cid] = {"cfg": cfg, "res": res, "rs": rs, "D": D, "equiv": equiv}

    por_n = {}
    for cid, c in celulas.items():
        por_n.setdefault(c["cfg"]["n"], []).append(cid)

    saida = {"auto_checagem_aritmetica_N": chk, "N_alvo": N_ALVO, "descartar_alturas": H0, "quantil": Q,
             "esperadas": len(esperadas), "obtidas": len(celulas), "problemas": problemas, "por_n": {}}
    for n in sorted(por_n):
        ids = sorted(por_n[n])
        series, D_all = [], []
        timeouts = stale = saltos = 0
        histo = Counter()
        equiv_ok = True
        vis_por_cel = []
        sementes = []
        for cid in ids:
            c = celulas[cid]
            hs = sorted(c["D"])
            assert hs == list(range(1, hs[-1] + 1)), f"alturas nao contiguas em {cid}"
            s = [c["D"][h] for h in hs if h > H0]
            series.append(s)
            D_all += s
            vis_por_cel.append(len(s))
            sementes.append(c["cfg"]["semente"])
            timeouts += c["rs"]["timeouts_disparados_total"]
            stale += c["rs"]["timeouts_obsoletos_STALE_total"]
            saltos += c["rs"]["finalizacoes_com_salto_de_altura"]
            equiv_ok &= c["equiv"]
            for v in c["rs"]["visao_corrente_final_por_no"]:
                histo[v - c["rs"]["altura_maxima_finalizada"]] += 1
        s = sorted(D_all)
        N = len(s)
        p9999, posto = nr(s, Q)
        ic = ic_ordem(s)
        # conferencia contra a implementacao do piloto
        inf_p, sup_p, ri_p, rs_p = piloto.ic_ordem(s, Q)
        conf_piloto = {"superior_piloto": sup_p, "posto_sup_piloto": rs_p, "inferior_piloto": inf_p,
                       "posto_inf_piloto": ri_p, "igual": (sup_p == ic["superior"] and inf_p == ic["inferior"])}
        bl, bh = piloto.boot_quantil(D_all, Q, B=BOOT_B, seed=BOOT_SEED)
        T_a = T_PILOTO[n]
        T_b = 3 * p9999
        UB = ic["superior"]
        veredito = lambda T: ("INCONCLUSIVO (sem UB)" if UB is None else ("PASSA" if UB <= T / 2 else "FALHA"))
        excede_a = sum(1 for x in s if x > T_a / 2)
        excede_T = sum(1 for x in s if x >= T_a)
        # diagnostico de independencia
        p99 = nr(s, 0.99)[0]
        p999 = nr(s, 0.999)[0]
        ac = {str(k): autocorr(series, k) for k in range(1, 6)}
        adj99, adj999 = adjacentes(series, p99), adjacentes(series, p999)
        # concentracao do maximo e das excedencias de p99,9
        por_cel_999 = {cid: sum(1 for x in series[i] if x > p999) for i, cid in enumerate(ids)}
        cel_max = max(ids, key=lambda cid: max(series[ids.index(cid)]))
        tot999 = sum(por_cel_999.values())
        topo = sorted(por_cel_999.items(), key=lambda kv: -kv[1])[:3]
        top_alt = []
        for i, cid in enumerate(ids):
            mx = max(series[i])
            h = [h for h in sorted(celulas[cid]["D"]) if h > H0][series[i].index(mx)]
            top_alt.append((mx, cid, h))
        top_alt.sort(reverse=True)
        saida["por_n"][n] = {
            "n": n, "celulas": len(ids), "sementes": [min(sementes), max(sementes)], "N": N,
            "N_ge_alvo": N >= N_ALVO, "visoes_por_celula": {"min": min(vis_por_cel), "max": max(vis_por_cel)},
            "D_media": st.fmean(s), "D_p50": nr(s, .5)[0], "D_p99": p99, "D_p99_9": p999,
            "D_p99_99_nearest_rank": p9999, "posto_p99_99": posto, "D_max": s[-1],
            "IC95_ordem_p99_99": ic, "conferencia_ic_vs_piloto": conf_piloto,
            "IC95_bootstrap_p99_99_contraste": [bl, bh],
            "T_a_piloto": T_a, "D_hat_piloto_max_2_politicas": D_HAT_PILOTO[n],
            "A3_linha_a": {"T": T_a, "T_meio": T_a / 2, "UB": UB, "razao_UB_sobre_T": (UB / T_a if UB else None),
                           "veredito": veredito(T_a), "visoes_acima_de_T_meio": excede_a,
                           "visoes_ge_T": excede_T, "D_max_sobre_T": s[-1] / T_a},
            "A3_linha_b": {"D_hat_confirmacao": p9999, "T": T_b, "T_meio": T_b / 2, "UB": UB,
                           "razao_UB_sobre_T": (UB / T_b if UB else None), "veredito": veredito(T_b),
                           "m_minimo_2UB_sobre_Dhat": (2 * UB / p9999 if UB else None)},
            "comparacao_com_piloto": {"p99_99_confirmacao_sobre_D_hat_piloto": p9999 / D_HAT_PILOTO[n],
                                      "UB_sobre_D_hat_piloto": (UB / D_HAT_PILOTO[n] if UB else None),
                                      "m_minimo_2UB_sobre_Dhat_piloto": (2 * UB / D_HAT_PILOTO[n] if UB else None)},
            "timeouts_disparados_total": timeouts, "timeouts_obsoletos_STALE_total": stale,
            "finalizacoes_com_salto": saltos,
            "visao_final_menos_altura_final_histograma": dict(sorted(histo.items())),
            "visao_final_menos_altura_final_em_0_1": set(histo) <= {0, 1},
            "csv_oficial_igual_ao_observador": equiv_ok,
            "independencia": {"autocorrelacao_lag": ac, "pares_adjacentes_p99": adj99, "pares_adjacentes_p99_9": adj999,
                              "p99_9_excedencias_total": tot999, "p99_9_celulas_com_mais_excedencias": topo,
                              "celula_do_maximo": cel_max, "tres_maiores_D": top_alt[:3],
                              "celulas_com_alguma_excedencia_p99_9": sum(1 for v in por_cel_999.values() if v > 0)},
        }
        r = saida["por_n"][n]
        print(f"n={n:3d} cel={len(ids)} N={N} p99,99={p9999:.4f} UB={UB} max={s[-1]:.4f} T(a)={T_a} (a)={r['A3_linha_a']['veredito']} "
              f"T(b)={T_b:.3f} (b)={r['A3_linha_b']['veredito']} timeouts={timeouts} vis-alt={dict(sorted(histo.items()))} "
              f"equiv={equiv_ok} ic_igual_piloto={conf_piloto['igual']}")
    (CONF / "analise-confirmacao.json").write_text(json.dumps(saida, indent=1, ensure_ascii=False))
    print(f"esperadas={len(esperadas)} obtidas={len(celulas)} problemas={len(problemas)}")
    sys.exit(0 if not problemas else 3)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Analise v2 da CONFIRMACAO: celula/semente como unidade independente (CRITERIOS-congelados-v2.md). So biblioteca padrao.
Escrito DEPOIS do congelamento (SHA em CRITERIOS-congelados-v2.sha256). Nao simula nada: so le dados brutos existentes.

Primario  : estatistica de ordem sobre os K maximos por celula (quantil u_n = 0,9999^mbar de M_c).
Secundario: N efetivo por autocorrelacao (DEFF = 1 + 2*soma rho_k, truncado) aplicado ao IC por ordem de visoes.
Contrastes: bootstrap por celula, Clopper-Pearson, theta por runs, IC v1 (visoes empilhadas).
Conjuntos: CONF (80 celulas, resultado primario), R (replicacao: med_rotating_L100 + ver_m3_rotating_L100, 20 por n),
           POSHOC = CONF + R (K=40 por n), rotulado.
Saida: analise-confirmacao-v2.json
"""
import csv
import gzip
import hashlib
import importlib.util
import json
import math
import random
import statistics as st
import sys
from pathlib import Path

CONF = Path(__file__).resolve().parent.parent
PIL = CONF.parent
Q = 0.9999
ALFA2 = 0.025
H0 = 50
KMAX_LAG = 200
T_PILOTO = {4: 0.918, 16: 1.09, 64: 2.065, 128: 3.396}
N_ALVO = 36887
BOOT_B, BOOT_SEED = 2000, 20261113

spec = importlib.util.spec_from_file_location("v1", CONF / "scripts" / "analisar-confirmacao.py")
v1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v1)


def ler_gz(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        return list(csv.DictReader(l for l in f if not l.startswith("#")))


def carregar_celula(d):
    cfg = json.loads((d / "cfg.json").read_text())
    res = json.loads((d / "resultado.json").read_text())
    rs = json.loads((d / "obs" / "observador-resumo.json").read_text())
    alt = ler_gz(d / "obs" / "observador-por-altura.csv.gz")
    D = {int(r["height"]): float(r["Rmax_s"]) for r in alt}
    hs = sorted(D)
    assert hs == list(range(1, hs[-1] + 1)), f"alturas nao contiguas em {d.name}"
    serie = [D[h] for h in hs if h > H0]
    return {"id": d.name, "n": cfg["n"], "semente": cfg["semente"], "rc": (res["rc_mvp"], res["rc_observador"]),
            "timeouts": rs["timeouts_disparados_total"], "serie": serie}


# ---------- binomial ----------
def binom_cdf_list(K, u):
    """P(Bin(K,u) <= k), k=0..K (exato, K pequeno)."""
    out, acc = [], 0.0
    for k in range(K + 1):
        acc += math.comb(K, k) * u ** k * (1 - u) ** (K - k)
        out.append(acc)
    return out


def ic_ordem_celulas(M, u):
    """M: maximos por celula. IC95 bilateral por estatistica de ordem para o quantil u de M."""
    K = len(M)
    s = sorted(M)
    cdf = binom_cdf_list(K, u)  # cdf[k] = P(Bin<=k)
    j = None
    for jj in range(1, K + 1):  # menor j com P(Bin<=j-1) >= 0,975
        if cdf[jj - 1] >= 1 - ALFA2:
            j = jj
            break
    i = None
    for ii in range(1, K + 1):  # maior i com P(Bin<=i-1) <= 0,025
        if cdf[ii - 1] <= ALFA2:
            i = ii
    return {"K": K, "u": u, "j_sup": j, "i_inf": i, "superior": s[j - 1] if j else None,
            "inferior": s[i - 1] if i else None,
            "P_Bin_K_u_le_K_menos_1": cdf[K - 1], "P_Bin_K_u_le_K_menos_2": cdf[K - 2] if K >= 2 else None,
            "K_min": math.ceil(math.log(ALFA2) / math.log(u))}


def clopper_pearson(x, K, alfa=0.05):
    def cdf(k, p):  # P(Bin(K,p) <= k)
        return sum(math.comb(K, i) * p ** i * (1 - p) ** (K - i) for i in range(k + 1))
    lo = 0.0 if x == 0 else None
    hi = 1.0 if x == K else None
    if lo is None:  # P(Bin>=x | p)=alfa/2
        a, b = 0.0, 1.0
        for _ in range(100):
            m = (a + b) / 2
            if 1 - cdf(x - 1, m) < alfa / 2:
                a = m
            else:
                b = m
        lo = (a + b) / 2
    if hi is None:  # P(Bin<=x | p)=alfa/2
        a, b = 0.0, 1.0
        for _ in range(100):
            m = (a + b) / 2
            if cdf(x, m) > alfa / 2:
                a = m
            else:
                b = m
        hi = (a + b) / 2
    return [lo, hi]


# ---------- secundario ----------
def rho_pooled(series, lags):
    todos = [x for s in series for x in s]
    N = len(todos)
    mu = st.fmean(todos)
    var = sum((x - mu) ** 2 for x in todos) / N
    xs = [[x - mu for x in s] for s in series]
    out = []
    for k in lags:
        num = den = 0.0
        for s in xs:
            num += sum(a * b for a, b in zip(s, s[k:]))
            den += len(s) - k
        out.append((num / den) / var)
    return out, N


def neff(series):
    rhos, N = rho_pooled(series, range(1, KMAX_LAG + 1))
    lim = 2 / math.sqrt(N)
    ktr, trunc_teto = None, False
    for k, r in enumerate(rhos, start=1):
        if r < lim:
            ktr = k
            break
    if ktr is None:
        ktr, trunc_teto = KMAX_LAG + 1, True
    S = sum(rhos[:ktr - 1])
    deff = max(1.0, 1 + 2 * S)
    return {"N": N, "limiar_2_sobre_raiz_N": lim, "K_trunc_primeiro_lag_nao_signif": ktr, "truncado_no_teto": trunc_teto,
            "soma_rho": S, "DEFF": deff, "N_eff": N / deff, "rho_lag1_a_5": rhos[:5]}


def theta_runs(series, q=0.99):
    todos = sorted(x for s in series for x in s)
    lim = v1.nr(todos, q)[0]
    exc = runs = 0
    for s in series:
        prev = False
        for x in s:
            e = x > lim
            if e:
                exc += 1
                if not prev:
                    runs += 1
            prev = e
    return {"limiar_p99": lim, "excedencias": exc, "corridas": runs, "theta_hat": (runs / exc if exc else None)}


def veredito(UB, T):
    return "INCONCLUSIVO (sem UB)" if UB is None else ("PASSA" if UB <= T / 2 else "FALHA")


def boot_celulas(series, B, seed):
    rng = random.Random(seed)
    K = len(series)
    tops = [sorted(s, reverse=True)[:30] for s in series]
    tam = [len(s) for s in series]
    out = []
    for _ in range(B):
        idx = [rng.randrange(K) for _ in range(K)]
        N = sum(tam[i] for i in idx)
        rank = max(1, math.ceil(Q * N - 1e-9))
        kt = N - rank + 1  # posicao a partir do maior (1 = maximo)
        assert kt <= 30
        merged = sorted((x for i in idx for x in tops[i]), reverse=True)
        out.append(merged[kt - 1])
    out.sort()
    return out[int(0.025 * B)], out[int(0.975 * B) - 1]


def analisar_grupo(n, cels):
    T = T_PILOTO[n]
    series = [c["serie"] for c in cels]
    K = len(series)
    mbar = st.fmean(len(s) for s in series)
    M = [max(s) for s in series]
    u = Q ** mbar
    prim = ic_ordem_celulas(M, u)
    UBp = prim["superior"]
    empilhado = sorted(x for s in series for x in s)
    N = len(empilhado)
    ic1 = v1.ic_ordem(empilhado)
    UB1 = ic1["superior"]
    nf = neff(series)
    Neff = nf["N_eff"]
    es = v1.e_sup(int(math.floor(Neff)))
    if es < 0:
        UBs, rot_s = None, "N_eff < 36887"
    else:
        rk = N - es
        UBs, rot_s = empilhado[rk - 1], f"e_sup(N_eff)={es}, UB = posto {rk} de {N}"
    th = theta_runs(series)
    Neff_cauda = N * th["theta_hat"] if th["theta_hat"] else None
    UBc = None
    if Neff_cauda is not None and v1.e_sup(int(math.floor(Neff_cauda))) >= 0:
        UBc = empilhado[N - v1.e_sup(int(math.floor(Neff_cauda))) - 1]
    acima = sum(1 for m in M if m > T / 2)
    celulas_max = sorted(((m, c["id"]) for m, c in zip(M, cels)), reverse=True)[:3]
    bl, bh = boot_celulas(series, BOOT_B, BOOT_SEED)
    deff = nf["DEFF"]
    r = {
        "n": n, "K": K, "sementes": [min(c["semente"] for c in cels), max(c["semente"] for c in cels)],
        "m_c_min_max_medio": [min(len(s) for s in series), max(len(s) for s in series), mbar], "N": N,
        "T": T, "T_meio": T / 2,
        "maximos_por_celula": {"max": max(M), "mediana": st.median(M), "min": min(M),
                               "celulas_com_Mc_maior_que_T_meio": acima, "tres_maiores": celulas_max,
                               "CP95_P_Mc_maior_T_meio": clopper_pearson(acima, K)},
        "primario": {**prim, "veredito": veredito(UBp, T), "UB_sobre_T": (UBp / T if UBp else None),
                     "T_exigido_2UB": (2 * UBp if UBp else None),
                     "bootstrap_celulas_contraste_p99_99": [bl, bh]},
        "secundario": {"N_eff_info": nf, "N_eff_menos_N_alvo_pct": 100 * (Neff - N_ALVO) / N_ALVO,
                       "borderline_5pct": abs(Neff - N_ALVO) / N_ALVO < 0.05,
                       "UB": UBs, "regra": rot_s, "veredito": veredito(UBs, T),
                       "T_exigido_2UB": (2 * UBs if UBs else None),
                       "N_necessario_visoes": math.ceil(N_ALVO * deff),
                       "celulas_equivalentes_necessarias": math.ceil(N_ALVO * deff / mbar)},
        "S2_theta_runs_informativo": {**th, "N_eff_cauda": Neff_cauda, "UB": UBc, "veredito": veredito(UBc, T)},
        "v1_visoes_empilhadas_confronto": {"e_sup": ic1["e_sup"], "UB": UB1, "veredito": veredito(UB1, T),
                                           "UB_igual_ao_maximo_dos_maximos_por_celula": UB1 == max(M)},
    }
    p, s_ = r["primario"]["veredito"], r["secundario"]["veredito"]
    if p == "PASSA":
        r["rotulo_robustez"] = "PASSA robusto" if s_ == "PASSA" else "PASSA condicionado a dependencia (secundario: " + s_ + ")"
    else:
        r["rotulo_robustez"] = p
    return r


def conjunto(nome, selecionar, esperadas_por_n):
    out = {"conjunto": nome, "por_n": {}, "problemas": []}
    for n in (4, 16, 64, 128):
        cels = []
        for d in selecionar(n):
            try:
                c = carregar_celula(d)
            except Exception as e:
                out["problemas"].append({"id": d.name, "erro": str(e)})
                continue
            if c["rc"] != (0, 0):
                out["problemas"].append({"id": c["id"], "erro": f"rc={c['rc']}"})
                continue
            if c["timeouts"] != 0:
                out["problemas"].append({"id": c["id"], "erro": f"timeouts={c['timeouts']} (excluida)"})
                continue
            cels.append(c)
        out.setdefault("esperadas_obtidas", {})[n] = [esperadas_por_n, len(cels)]
        out["por_n"][n] = analisar_grupo(n, cels)
        out.setdefault("_cels", {})[n] = cels
    return out


def main():
    sha_crit = hashlib.sha256((CONF / "CRITERIOS-congelados-v2.md").read_bytes()).hexdigest()
    gravado = (CONF / "CRITERIOS-congelados-v2.sha256").read_text().split()[0]
    assert sha_crit == gravado, "CRITERIOS-congelados-v2.md ALTERADO depois do congelamento"
    man = json.loads((CONF / "celulas" / "confirmacao-L100" / "manifesto.json").read_text())
    ids = man["celulas"]
    conf = conjunto("CONFIRMACAO (resultado primario)",
                    lambda n: [CONF / "dados-brutos" / "confirmacao" / i
                               for i in ids if f"_n{n}_" in i], 20)

    def sel_R(n):
        return sorted((PIL / "dados-brutos" / "medicao").glob(f"med_rotating_L100_n{n}_*")) + \
            sorted((PIL / "dados-brutos" / "verificacao").glob(f"ver_m3_rotating_L100_n{n}_*"))
    rep = conjunto("REPLICACAO R (med_rotating_L100 + ver_m3_rotating_L100; nao misturada)", sel_R, 20)
    # pos-hoc: uniao K=40
    pos = {"conjunto": "POS-HOC: CONFIRMACAO + R (K=40 por n), rotulado, nao decide", "por_n": {}}
    for n in (4, 16, 64, 128):
        pos["por_n"][n] = analisar_grupo(n, conf["_cels"][n] + rep["_cels"][n])
    colisoes = {n: sorted({c["semente"] for c in conf["_cels"][n]} & {c["semente"] for c in rep["_cels"][n]})
                for n in (4, 16, 64, 128)}
    for x in (conf, rep):
        x.pop("_cels")
    saida = {"criterios_sha256": sha_crit, "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
             "parametros": {"Q": Q, "alfa_por_cauda": ALFA2, "H0": H0, "KMAX_LAG": KMAX_LAG, "T": T_PILOTO,
                            "N_ALVO": N_ALVO, "boot_B": BOOT_B, "boot_seed": BOOT_SEED},
             "colisoes_de_semente_conf_x_R": colisoes,
             "confirmacao": conf, "replicacao_R": rep, "posthoc_uniao": pos}
    (CONF / "analise-confirmacao-v2.json").write_text(json.dumps(saida, indent=1, ensure_ascii=False))
    for nome, x in (("CONF", conf), ("R", rep), ("POSHOC", pos)):
        print(f"== {nome}  esperadas/obtidas: {x.get('esperadas_obtidas')}  problemas: {len(x.get('problemas', []))}")
        for n, r in x["por_n"].items():
            p, s = r["primario"], r["secundario"]
            print(f" n={n:3d} K={r['K']} N={r['N']} Mmax={r['maximos_por_celula']['max']:.4f} T/2={r['T_meio']:.4f} | "
                  f"PRIM j={p['j_sup']} UB={p['superior']} {p['veredito']} Treq={p['T_exigido_2UB']} | "
                  f"SEC DEFF={s['N_eff_info']['DEFF']:.3f} Neff={s['N_eff_info']['N_eff']:.0f} UB={s['UB']} {s['veredito']} | {r['rotulo_robustez']}")
    sys.exit(0 if not (conf["problemas"] or rep["problemas"]) else 3)


if __name__ == "__main__":
    main()

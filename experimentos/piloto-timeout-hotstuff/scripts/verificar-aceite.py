#!/usr/bin/env python3
"""Verifica o criterio de aceite (CRITERIOS-congelados.md) sobre as analises ja geradas. Somente biblioteca padrao.

A1: zero timeouts disparados em todas as celulas honestas da verificacao (e da medicao).
A2: minimo de visoes: >= 2000 por celula (L=100), >= 1000 (L=10, 500); N >= 10^4 por (politica, L, n).
A3: D_max <= T/2, com D_max da medicao E da verificacao (e do conjunto empilhado).
Tambem: sensibilidade a L usando o T de L=100 (m adotado) e a variacao pelo overhead de mensagem.
Saida: aceite.json e texto no stdout.
"""
import json
from pathlib import Path

B = Path(__file__).resolve().parent.parent


def J(n):
    return json.loads((B / n).read_text())


def gmax(d, n, campo="max"):
    return max(g["D"][campo] for g in d["grupos"] if g["n"] == n)


def main():
    prop = J("propostas-timeout.json")
    m = str(prop["m_adotado"])
    T = {p["n"]: p["propostas"][m]["T_s"] for p in prop["propostas"] if p["L"] == 100}
    T2 = {p["n"]: p["propostas"]["2"]["T_s"] for p in prop["propostas"] if p["L"] == 100}
    prop_Dh = {p["n"]: p["D_hat_p99_99_max_entre_politicas"] for p in prop["propostas"] if p["L"] == 100}
    med = J("analise-medicao-L100.json")
    ver = J(f"analise-verificacao-m{m}-L100.json")
    ver2 = J("analise-verificacao-m2-L100.json")
    out = {"m_adotado": m, "T_s_por_n": T, "por_n": {}, "A1": {}, "A2": {}, "sensibilidade_L_com_T_L100": {}}
    ok_all = True
    for n in (4, 16, 64, 128):
        mm, vv = gmax(med, n), gmax(ver, n)
        pool = max(mm, vv)
        e = {"T_s": T[n], "D_max_medicao": mm, "D_max_verificacao": vv, "D_max_empilhado": pool,
             "razao_Dmax_sobre_T": {"medicao": mm / T[n], "verificacao": vv / T[n], "empilhado": pool / T[n]},
             "A3_passa": pool <= T[n] / 2,
             "m_minimo_para_A3_empilhado": 2 * pool / prop_Dh[n],
             "max_sobre_p9999_empilhado": pool / prop_Dh[n],
             "m2": {"T_s": T2[n], "razao_Dmax_empilhado_sobre_T": pool / T2[n],
                    "A3_passa_empilhado": pool <= T2[n] / 2, "A3_passa_so_medicao": mm <= T2[n] / 2}}
        out["por_n"][n] = e
        ok_all &= e["A3_passa"]
    for nome, d in (("medicao_L100", med), ("verificacao_m3_L100", ver), ("verificacao_m2_L100", ver2)):
        to = sum(g["timeouts_disparados_total"] for g in d["grupos"])
        cel = sum(g["n_celulas"] for g in d["grupos"])
        out["A1"][nome] = {"celulas": cel, "timeouts_disparados_total": to,
                           "visoes_somadas": sum(g["D"]["N"] for g in d["grupos"])}
        if nome != "verificacao_m2_L100":
            ok_all &= to == 0
    out["A1"]["nota"] = "verificacao_m2_L100 usa T_m2 (menor) nas MESMAS sementes novas: informa se m=2 teria disparado"
    for nome, d, minimo in (("medicao_L100", med, 2000), ("verificacao_m3_L100", ver, 2000)):
        vmin = min(g["visoes_pos_transiente_por_celula"]["min"] for g in d["grupos"])
        nmin = min(g["D"]["N"] for g in d["grupos"])
        out["A2"][nome] = {"visoes_pos_transiente_min_por_celula": vmin, "N_min_por_grupo": nmin,
                           "passa": vmin >= minimo and nmin >= 10_000}
        ok_all &= out["A2"][nome]["passa"]
    out["A2"]["sensibilidades_medicao"] = {}
    for L in (10, 500):
        dm = J(f"analise-medicao-L{L}.json")
        vmin = min(g["visoes_pos_transiente_por_celula"]["min"] for g in dm["grupos"])
        nmin = min(g["D"]["N"] for g in dm["grupos"])
        out["A2"]["sensibilidades_medicao"][str(L)] = {"visoes_pos_transiente_min_por_celula": vmin, "N_min_por_grupo": nmin,
                                                       "passa": vmin >= 1000 and nmin >= 10_000,
                                                       "timeouts_na_medicao": sum(g["timeouts_disparados_total"] for g in dm["grupos"])}
        ok_all &= out["A2"]["sensibilidades_medicao"][str(L)]["passa"]
    for L in (10, 500):
        d = J(f"analise-sens-T100m3-L{L}.json")
        out["sensibilidade_L_com_T_L100"][str(L)] = {
            str(n): {"T_s": T[n], "D_max": gmax(d, n), "razao": gmax(d, n) / T[n],
                     "timeouts": sum(g["timeouts_disparados_total"] for g in d["grupos"] if g["n"] == n)}
            for n in (4, 16, 64, 128)}
    out["ACEITE_GERAL"] = ok_all
    (B / "aceite.json").write_text(json.dumps(out, indent=1))
    print(f"m adotado = {m}")
    for n, e in out["por_n"].items():
        print(f"n={n:3d} T={e['T_s']:.3f}  Dmax med={e['D_max_medicao']:.3f} ver={e['D_max_verificacao']:.3f} "
              f"emp={e['D_max_empilhado']:.3f}  Dmax/T emp={e['razao_Dmax_sobre_T']['empilhado']:.3f} "
              f"A3={'ok' if e['A3_passa'] else 'FALHA'} m_min(A3)={e['m_minimo_para_A3_empilhado']:.2f} | m=2: T={e['m2']['T_s']:.3f} razao_emp={e['m2']['razao_Dmax_empilhado_sobre_T']:.3f} "
              f"A3(med)={'ok' if e['m2']['A3_passa_so_medicao'] else 'FALHA'} A3(emp)={'ok' if e['m2']['A3_passa_empilhado'] else 'FALHA'}")
    print("A1", json.dumps(out["A1"]))
    print("A2", json.dumps(out["A2"]))
    for L, v in out["sensibilidade_L_com_T_L100"].items():
        print("sens L", L, {n: (round(x["razao"], 3), x["timeouts"]) for n, x in v.items()})
    print("ACEITE_GERAL (m adotado):", ok_all)


if __name__ == "__main__":
    main()

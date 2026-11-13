#!/usr/bin/env python3
"""Gera tabelas.md (markdown) a partir dos JSON de analise, sem transcricao manual. Somente biblioteca padrao."""
import json
from pathlib import Path

B = Path(__file__).resolve().parent.parent


def J(n):
    return json.loads((B / n).read_text())


def f(x, d=4):
    return "—" if x is None else f"{x:.{d}f}"


def tabela_medicao(nome, L):
    d = J(nome)
    ln = ["| política | n | células | visões pós-t0 (N) | D média | D p50 | D p99 | D p99,99 | D máx | IC95 p99,99 ordem (inf; sup) | IC95 p99,99 bootstrap | timeouts disparados | t0 (s, alt. 50) | Tb média | Tb p99 | Tb máx | Dprim. média | Dprim. p99,99 | Dprim. máx |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for g in sorted(d["grupos"], key=lambda g: (g["politica"], g["n"])):
        D = g["D"]
        io = D["p99_99_IC95_ordem"]
        ib = D.get("p99_99_IC95_bootstrap", {})
        ln.append(f"| {g['politica']} | {g['n']} | {g['n_celulas']} | {D['N']} | {f(D['media'])} | {f(D['p50'])} | {f(D['p99'])} | "
                  f"{f(D['p99_99_nearest_rank'])} | {f(D['max'])} | ({f(io['inferior'])}; {'∞' if io['superior'] is None else f(io['superior'])}) | "
                  f"({f(ib.get('inferior'))}; {f(ib.get('superior'))}) | {g['timeouts_disparados_total']} | {f(g['t0_s_medio'],1)} | "
                  f"{f(g['Tb']['media'])} | {f(g['Tb']['p99'])} | {f(g['Tb']['max'])} | {f(g['Dprimeiro']['media'])} | "
                  f"{f(g['Dprimeiro']['p99_99_nearest_rank'])} | {f(g['Dprimeiro']['max'])} |")
    return "\n".join(ln)


def tabela_prop():
    p = J("propostas-timeout.json")
    ln = ["| L | n | D̂ p99,99 (máx. das 2 políticas) | D_max (medição) | T (m=2) | D_max/T (m=2) | A3 (m=2) | T (m=3) | D_max/T (m=3) | A3 (m=3, só medição) |",
          "|---|---|---|---|---|---|---|---|---|---|"]
    for x in p["propostas"]:
        a, b = x["propostas"]["2"], x["propostas"]["3"]
        ln.append(f"| {x['L']} | {x['n']} | {f(x['D_hat_p99_99_max_entre_politicas'])} | {f(x['D_max'])} | {a['T_s']:.3f} | "
                  f"{a['folga_Dmax_sobre_T']:.3f} | {'ok' if a['A3_passa'] else 'FALHA'} | {b['T_s']:.3f} | "
                  f"{b['folga_Dmax_sobre_T']:.3f} | {'ok' if b['A3_passa'] else 'FALHA'} |")
    return "\n".join(ln)


def tabela_aceite():
    a = J("aceite.json")
    ln = ["| n | T (m=3) | D_max medição | D_max verificação | D_max empilhado | D_max/T empilhado | A3 (m=3) | D_max/p99,99 empilhado | m mínimo p/ A3 | T (m=2) | D_max/T (m=2) empilhado |",
          "|---|---|---|---|---|---|---|---|---|---|---|"]
    for n, e in a["por_n"].items():
        ln.append(f"| {n} | {e['T_s']:.3f} | {e['D_max_medicao']:.4f} | {e['D_max_verificacao']:.4f} | {e['D_max_empilhado']:.4f} | "
                  f"{e['razao_Dmax_sobre_T']['empilhado']:.3f} | {'ok' if e['A3_passa'] else '**FALHA**'} | {e['max_sobre_p9999_empilhado']:.3f} | {e['m_minimo_para_A3_empilhado']:.2f} | {e['m2']['T_s']:.3f} | "
                  f"{e['m2']['razao_Dmax_empilhado_sobre_T']:.3f} |")
    return "\n".join(ln)


def tabela_sens():
    a = J("aceite.json")["sensibilidade_L_com_T_L100"]
    ln = ["| L | n | T usado (L=100, m=3) | D_max | D_max/T | timeouts |", "|---|---|---|---|---|---|"]
    for L, v in a.items():
        for n, x in v.items():
            ln.append(f"| {L} | {n} | {x['T_s']:.3f} | {x['D_max']:.4f} | {x['razao']:.3f} | {x['timeouts']} |")
    return "\n".join(ln)


def tabela_ataque():
    d = J("analise-ataque.json")
    ln = ["| grupo (política_estrato_n_f) | células | sem nenhum bloco | altura final média | alturas/s | timeouts disparados (soma) | timeouts por réplica por visão | D máx | T usado (s) |",
          "|---|---|---|---|---|---|---|---|---|"]
    T = {c["grupo"]: c["view_timeout_s"] for c in d["celulas"]}
    for g, v in d["grupos"].items():
        ln.append(f"| {g} | {v['n_celulas']} | {v['celulas_sem_nenhum_bloco']} | {v['altura_max_media']:.1f} | {v['vazao_media_alturas_por_s']:.3f} | "
                  f"{v['timeouts_disparados_total']} | {v['timeouts_por_replica_por_visao_media']:.3f} | {f(v['D_max_entre_celulas'],3)} | {T[g]} |")
    return "\n".join(ln)


def tabela_cauda():
    d = J("analise-cauda.json")["por_n"]
    ln = ["| n | N visões (ROTATING, 20 sementes) | D média | D máx | D>média+0,10 s (obs.) | D>média+0,20 s (obs.) | D>média+0,30 s (obs.) |", "|---|---|---|---|---|---|---|"]
    for n, v in d.items():
        t = {x["excesso_e_s"]: x["observado"] for x in v["tabela"]}
        ln.append(f"| {n} | {v['N_visoes']} | {v['D_media']:.4f} | {v['D_max']:.4f} | {t[0.1]} | {t[0.2]} | {t[0.3]} |")
    return "\n".join(ln)


def tabela_pooled():
    d = J("suplemento-pooled.json")
    ln = ["| política | n | N (20 sementes) | p99,99 | IC95 ordem (inf; sup) | IC95 bootstrap | máx | máx / p99,99 | m mínimo p/ A3 (2·máx/p99,99) |", "|---|---|---|---|---|---|---|---|---|"]
    for k, g in d["grupos"].items():
        pol, n = k.split("_n")
        io, ib = g["IC95_ordem"], g["IC95_bootstrap"]
        ln.append(f"| {pol.upper()} | {n} | {g['N']} | {g['p99_99']:.4f} | ({io[0]:.4f}; {'∞' if io[1] is None else format(io[1], '.4f')}) | "
                  f"({ib[0]:.4f}; {ib[1]:.4f}) | {g['max']:.4f} | {g['max_sobre_p99_99']:.2f} | {g['m_minimo_A3_com_este_p99_99']:.2f} |")
    return "\n".join(ln)


def main():
    partes = {"Medição L=100 (caso central)": tabela_medicao("analise-medicao-L100.json", 100),
              "Medição L=10": tabela_medicao("analise-medicao-L10.json", 10),
              "Medição L=500": tabela_medicao("analise-medicao-L500.json", 500),
              "Propostas de timeout": tabela_prop(), "Aceite (L=100, m=3 e m=2)": tabela_aceite(),
              "Sensibilidade a L com o T de L=100": tabela_sens(), "Ataque (EQUIVOCATION)": tabela_ataque(),
              "Cauda de D (ROTATING, L=100)": tabela_cauda(),
              "Suplemento empilhado": tabela_pooled()}
    out = []
    for k, v in partes.items():
        out.append(f"### {k}\n\n{v}\n")
    (B / "tabelas.md").write_text("\n".join(out), encoding="utf-8")
    tpl = B / "RELATORIO.template.md"
    if tpl.exists():
        txt = tpl.read_text(encoding="utf-8")
        for k, v in partes.items():
            txt = txt.replace("{{T:" + k + "}}", v)
        if "{{T:" in txt:
            raise SystemExit("marcador de tabela sem substituicao em RELATORIO.template.md")
        (B / "RELATORIO.md").write_text(txt, encoding="utf-8")
    print("tabelas.md gerado:", sum(len(v.splitlines()) for v in partes.values()), "linhas")


if __name__ == "__main__":
    main()

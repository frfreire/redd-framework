#!/usr/bin/env python3
"""E7 (e): relista, SEM SIMULAR, as celulas de V-B que abortam pela regra do ataque (HotStuff.requireEquivocationSplit), agora contra o
build PINADO em /tmp/opencode/val-hs (commit 76932b9; harness ObservadorVal) e nao contra o build de fumaca a65bf83 da v1/1b.
Nao altera listar-vb-bloqueadas.py nem vb-celulas-bloqueadas.json (ambos hash-conferidos); grava vb-celulas-bloqueadas-e7.json e compara
com o JSON da Emenda 1b. "Sem simular": o unico uso do simulador e o sorteio de ids (`--listar-byz-faixa`, ByzantineConfig.withExactCount;
nao constroi cenario). A regra e reaplicada por aritmetica (funcao `viola` do script original, importada) sobre a regra LIDA em
HotStuff.java 76932b9 (vizinhos = outros ids em ordem crescente; half=max(1,size/2); >=1 honesto em cada metade).
LIMITE: previsao por leitura de codigo; so a execucao confirma. Cobre SO esta regra (nao cobre `nenhum bloco finalizado`).
Uso: listar-vb-e7.py        Saida 0 = previsao identica a da Emenda 1b.
"""
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
BUILD = Path("/tmp/opencode/val-hs")
spec = importlib.util.spec_from_file_location("lvb", VAL / "scripts" / "listar-vb-bloqueadas.py")
lvb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lvb)


def sortear(n, f):
    cp = f"{BUILD}/harness-classes:{BUILD}/target/classes:" + (BUILD / "cp.txt").read_text().strip()
    r = subprocess.run(["java", "-cp", cp, "ObservadorVal", f"--listar-byz-faixa=1:{lvb.SEMENTES}",
                        f"--run-seed-base={lvb.BASE + n * 1000}", f"--validators={n}", f"--byzantine-count={f}"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"java falhou n={n} f={f}: {r.stderr[-500:]}")
    out = {}
    for linha in r.stdout.strip().splitlines():
        s, ids = linha.split(" ", 1)
        out[int(s)] = sorted(json.loads(ids))
    return out


def exaustivo():
    """Independe do sorteio: enumera TODOS os subconjuntos bizantinos de tamanho f e aplica a regra (aritmetica). Mostra para quais (n,f)
    existe ALGUM conjunto que aborta e a fracao deles; assim a previsao vale para qualquer semente, nao so para as 10 sorteadas."""
    from itertools import combinations
    from math import comb
    out = {}
    for n in lvb.NS:
        for f in range(1, (n - 1) // 3 + 2):
            ab = sum(1 for byz in combinations(range(n), f) if lvb.viola(n, set(byz)))
            out[f"n{n}_f{f}"] = {"conjuntos_possiveis": comb(n, f), "conjuntos_que_abortam": ab,
                                 "fracao": round(ab / comb(n, f), 4)}
    return out


def main():
    EXA = exaustivo()
    ant = json.loads((VAL / "vb-celulas-bloqueadas.json").read_text())
    ant_por_id = {c["id"]: c for c in ant["celulas"]}
    cel, resumo, difs = [], {}, []
    for n in lvb.NS:
        fmax = (n - 1) // 3
        for f in range(1, fmax + 2):
            sorteio = sortear(n, f)
            abort = []
            for s in range(1, lvb.SEMENTES + 1):
                byz = sorteio[s]
                assert len(byz) == f, (n, f, s, byz)
                ruins = lvb.viola(n, byz)
                cid = f"vb_individual_n{n}_f{f:02d}_s{s:02d}"
                c = {"id": cid, "n": n, "f": f, "replicacao": s, "semente": lvb.BASE + n * 1000 + s,
                     "fora_da_faixa": f > fmax, "bizantinos": byz,
                     "aborta_pela_regra_do_ataque_(previsao)": bool(ruins), "nos_que_violam": ruins}
                a = ant_por_id[cid]
                if (a["bizantinos"], a["aborta_pela_regra_do_ataque_(previsao)"], a["semente"]) != (byz, bool(ruins), c["semente"]):
                    difs.append(cid)
                cel.append(c)
                if ruins:
                    abort.append(s)
            resumo[f"n{n}_f{f}"] = {"fora_da_faixa": f > fmax, "abortam": len(abort), "de": lvb.SEMENTES,
                                    "sementes_que_abortam": abort}
    n_ab = [c for c in cel if c["aborta_pela_regra_do_ataque_(previsao)"]]
    doc = {"aviso": "PREVISAO por leitura de codigo + sorteio do simulador pinado 76932b9; NADA foi simulado.",
           "build": str(BUILD), "harness": "ObservadorVal --listar-byz-faixa (so sorteia e imprime)",
           "celulas_f_ge_1": len(cel), "f0_nao_listado": "f=0: 40 celulas sem bizantino; a regra nao e acionada",
           "abortam_previsto": [{"id": c["id"], "semente": c["semente"], "n": c["n"], "f": c["f"], "bizantinos": c["bizantinos"]}
                                for c in n_ab],
           "resumo_por_n_f": resumo,
           "exaustivo_todos_os_conjuntos_bizantinos_(independe_da_semente)": EXA,
           "diferencas_em_relacao_a_emenda_1b_(bizantinos/previsao/semente)": difs,
           "identico_a_emenda_1b": not difs and len(cel) == ant["totais"]["celulas_f_ge_1"], "celulas": cel}
    (VAL / "vb-celulas-bloqueadas-e7.json").write_text(json.dumps(doc, indent=1, ensure_ascii=False))
    for k, v in resumo.items():
        print(f"{k:8s} fora={str(v['fora_da_faixa']):5s} abortam {v['abortam']:2d}/{v['de']}  sementes={v['sementes_que_abortam']}")
    print("exaustivo (conjuntos que abortam):", {k: v for k, v in EXA.items() if v["conjuntos_que_abortam"]} or "nenhum", "| de", len(EXA), "pontos")
    print("abortam_previsto:", [(c["id"], c["semente"]) for c in n_ab])
    print("diferencas vs Emenda 1b:", difs or "nenhuma", "| identico:", doc["identico_a_emenda_1b"])
    sys.exit(0 if doc["identico_a_emenda_1b"] else 1)


if __name__ == "__main__":
    main()

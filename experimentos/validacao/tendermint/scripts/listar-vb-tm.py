#!/usr/bin/env python3
"""V-B do Tendermint: relista, SEM SIMULAR, as celulas que abortam pela regra do ataque (Tendermint.requireEquivocationSplit; HEAD bc07355).

REGRA LIDA em Tendermint.java (linhas 382-414): vizinhos = TODOS os outros ids em ordem crescente (TendermintP2P: malha completa SEM o proprio no;
size = n-1); half = max(1, size/2); exige size >= 2 e >= 1 destinatario HONESTO (fora do conjunto bizantino) em CADA metade
([0,half) e [half,size)); senao IllegalStateException "EQUIVOCATION em <FASE> (no <id>) exige ao menos ... Ataque nao aplicado: simulacao
abortada em vez de degradar". FASE em {PROPOSAL, PREVOTE, PRECOMMIT}. A checagem roda a CADA envio equivocado de um no bizantino
(proposta quando e o proponente; prevote/precommit nao-nil), antes de qualquer envio.
PREVISAO: a celula ABORTA se ALGUM no do conjunto bizantino viola a regra E esse no chega a emitir uma mensagem nao-nil. Em round 0 com
proposta recebida todo no bizantino emite prevote nao-nil logo na altura 0, entao a previsao e "algum no viola" (LIMITE: leitura de codigo;
so a execucao confirma; um no bizantino que nunca emitisse voto nao-nil nao dispararia a checagem).
Mesma aritmetica do HotStuff (half = max(1, (n-1)//2)), mas a regra do Tendermint e uma COPIA no codigo, nao a mesma funcao.
DEPENDENCIA DO PINO: o SORTEIO usa ByzantineConfig.withExactCount do build usado (HEAD bc07355 no dry-run). A tarefa BYZ-UNICA (gerador unico de
conjuntos, em src/ nao commitado) PODE mudar o sorteio: a lista de celulas que abortam so vale para o commit pinado e DEVE ser regerada
la (este script e o mesmo). A enumeracao EXAUSTIVA nao depende de semente nem do gerador.
Uso: listar-vb-tm.py [--build /tmp/opencode/tm-dry]     grava vb-celulas-abortam-previsto.json
"""
import json
import subprocess
import sys
from itertools import combinations
from math import comb
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
BUILD = Path(sys.argv[sys.argv.index("--build") + 1]) if "--build" in sys.argv else Path("/tmp/opencode/tm-dry")
cfg = json.loads((VAL / "config" / "fatores-vb.json").read_text())
NS, BASE, SEMENTES = cfg["n"], cfg["semente_base"], cfg["sementes"]


def viola_no(n, byz, i):
    """no bizantino i viola a regra? (vizinhos = outros ids em ordem crescente)"""
    viz = [j for j in range(n) if j != i]
    size = len(viz)
    if size < 2:
        return "menos de 2 vizinhos"
    half = max(1, size // 2)
    h1 = sum(1 for j in viz[:half] if j not in byz)
    h2 = sum(1 for j in viz[half:] if j not in byz)
    return None if h1 > 0 and h2 > 0 else f"honestos 1a metade={h1}, 2a={h2}"


def viola(n, byz):
    return {i: m for i in sorted(byz) for m in [viola_no(n, byz, i)] if m}


def sortear(n, f):
    cp = f"{BUILD}/harness-classes:{BUILD}/target/classes:" + (BUILD / "cp.txt").read_text().strip()
    r = subprocess.run(["java", "-cp", cp, "ObservadorTm", f"--listar-byz-faixa=1:{SEMENTES}", f"--run-seed-base={BASE + n * 1000}",
                        f"--validators={n}", f"--byzantine-count={f}"], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"java falhou n={n} f={f}: {r.stderr[-500:]}")
    out = {}
    for linha in r.stdout.strip().splitlines():
        s, ids = linha.split(" ", 1)
        out[int(s)] = sorted(json.loads(ids))
    return out


def main():
    exa = {}
    for n in NS:
        for f in range(1, (n - 1) // 3 + 2):
            ab = sum(1 for byz in combinations(range(n), f) if viola(n, set(byz)))
            exa[f"n{n}_f{f}"] = {"conjuntos_possiveis": comb(n, f), "conjuntos_que_abortam": ab, "fracao": round(ab / comb(n, f), 4)}
    cel, resumo = [], {}
    for n in NS:
        fm = (n - 1) // 3
        for f in range(1, fm + 2):
            sorteio = sortear(n, f)
            abort = []
            for s in range(1, SEMENTES + 1):
                byz = sorteio[s]
                assert len(byz) == f, (n, f, s, byz)
                v = viola(n, set(byz))
                c = {"id": f"tvb_n{n}_f{f:02d}_s{s:02d}", "n": n, "f": f, "replicacao": s, "semente": BASE + n * 1000 + s,
                     "fora_da_faixa": f > fm, "bizantinos": byz, "aborta_previsto": bool(v), "nos_que_violam": v}
                cel.append(c)
                if v:
                    abort.append(s)
            k = SEMENTES - len(abort)
            resumo[f"n{n}_f{f}"] = {"fora_da_faixa": f > fm, "abortam": len(abort), "de": SEMENTES, "sementes_que_abortam": abort,
                                    "k_exec_previsto": k, "min_exec": cfg["min_exec"], "decidivel_pelo_MIN_EXEC": k >= cfg["min_exec"]}
    ab = [c for c in cel if c["aborta_previsto"]]
    doc = {"aviso": "PREVISAO por leitura de codigo + sorteio do build de DRY-RUN (HEAD bc07355); NADA foi simulado. Regerar no commit pinado.",
           "build": str(BUILD), "harness": "ObservadorTm --listar-byz-faixa (so sorteia e imprime)",
           "f0": "f=0: 40 celulas sem bizantino (controle honesto); a regra nao e acionada",
           "abortam_previsto": [{k: c[k] for k in ("id", "semente", "n", "f", "bizantinos", "nos_que_violam")} for c in ab],
           "resumo_por_n_f": resumo, "exaustivo_todos_os_conjuntos_bizantinos_(independe_da_semente_e_do_gerador)": exa, "celulas": cel}
    (VAL / "vb-celulas-abortam-previsto.json").write_text(json.dumps(doc, indent=1, ensure_ascii=False))
    for k, v in resumo.items():
        print(f"{k:8s} fora={str(v['fora_da_faixa']):5s} abortam {v['abortam']:2d}/{v['de']}  k_exec={v['k_exec_previsto']:2d} "
              f"decidivel(MIN_EXEC={v['min_exec']})={v['decidivel_pelo_MIN_EXEC']}  sementes={v['sementes_que_abortam']}")
    print("exaustivo (conjuntos que abortam):", {k: v for k, v in exa.items() if v["conjuntos_que_abortam"]} or "nenhum", "| pontos:", len(exa))
    print("abortam_previsto:", [(c["id"], c["semente"], c["bizantinos"]) for c in ab])


if __name__ == "__main__":
    main()

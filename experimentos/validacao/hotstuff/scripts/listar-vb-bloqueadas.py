#!/usr/bin/env python3
"""V-B BLOQUEADO (nota da Emenda 1b, item 6). Lista, SEM SIMULAR, as celulas de V-B em que a regra do ataque EQUIVOCATION
(HotStuff.requireEquivocationSplit) aborta a simulacao, para n em {4,7,10,13}, f em 1..f_max+1, 10 sementes.

Como: (1) o sorteio dos ids bizantinos vem do proprio simulador (ByzantineConfig.withExactCount, via o modo
`--listar-byz-faixa` do ObservadorHotStuff, que so sorteia e imprime; nao constroi cenario nem simula); (2) a regra e
reproduzida aqui por aritmetica sobre a regra LIDA no codigo (HotStuff.java, requireEquivocationSplit; HotStuffP2P):
  vizinhos de um no i = todos os outros ids em ordem crescente (size = n-1); half = max(1, size/2);
  indices [0,half) = 1a metade; [half,size) = 2a metade; exige >=1 vizinho HONESTO em cada metade, senao IllegalStateException.
  A regra e avaliada por cada no bizantino quando ele equivoca (lider bizantino no PREPARE; replica bizantina em cada voto);
  em ROTATING todo no lidera, entao basta UM bizantino falhar a regra para a celula abortar.
LIMITE DECLARADO: e uma PREVISAO por leitura de codigo; nenhuma celula foi simulada. O aborto real so e confirmado rodando.
Uso: listar-vb-bloqueadas.py   (grava vb-celulas-bloqueadas.json; usa o build de fumaca em /tmp/opencode/val-hs-fumaca)
"""
import json
import subprocess
import sys
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
BUILD = Path("/tmp/opencode/val-hs-fumaca")
BASE = 420345
NS = [4, 7, 10, 13]
SEMENTES = 10


def sortear(n, f):
    cp = f"{BUILD}/harness-classes:{BUILD}/target/classes:" + (BUILD / "cp.txt").read_text().strip()
    r = subprocess.run(["java", "-cp", cp, "ObservadorHotStuff", f"--listar-byz-faixa=1:{SEMENTES}",
                        f"--run-seed-base={BASE + n * 1000}", f"--validators={n}", f"--byzantine-count={f}"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"java falhou n={n} f={f}: {r.stderr[-500:]}")
    out = {}
    for linha in r.stdout.strip().splitlines():
        s, ids = linha.split(" ", 1)
        out[int(s)] = sorted(json.loads(ids))
    return out


def viola(n, byz):
    """Devolve a lista de nos bizantinos que violam a regra (vazia = a celula nao aborta por esta regra)."""
    ruins = []
    for i in byz:
        viz = [x for x in range(n) if x != i]
        half = max(1, len(viz) // 2)
        h1 = sum(1 for k, x in enumerate(viz) if x not in byz and k < half)
        h2 = sum(1 for k, x in enumerate(viz) if x not in byz and k >= half)
        if len(viz) < 2 or h1 == 0 or h2 == 0:
            ruins.append({"no": i, "honestos_1a_metade": h1, "honestos_2a_metade": h2, "vizinhos": len(viz)})
    return ruins


def main():
    cel, resumo = [], {}
    for n in NS:
        fmax = (n - 1) // 3
        for f in range(1, fmax + 2):
            sorteio = sortear(n, f)
            abort = []
            for s in range(1, SEMENTES + 1):
                byz = sorteio[s]
                assert len(byz) == f, (n, f, s, byz)
                ruins = viola(n, byz)
                cel.append({"id": f"vb_individual_n{n}_f{f:02d}_s{s:02d}", "n": n, "f": f, "replicacao": s,
                            "semente": BASE + n * 1000 + s, "fora_da_faixa": f > fmax, "bizantinos": byz,
                            "aborta_pela_regra_do_ataque_(previsao)": bool(ruins), "nos_que_violam": ruins})
                if ruins:
                    abort.append(s)
            resumo[f"n{n}_f{f}"] = {"fora_da_faixa": f > fmax, "abortam": len(abort), "de": SEMENTES,
                                    "sementes_que_abortam": abort}
    doc = {"aviso": "PREVISAO por leitura de codigo + sorteio do simulador; NADA foi simulado. V-B segue BLOQUEADO (pergunta aberta ao humano).",
           "regra": "HotStuff.requireEquivocationSplit: >=2 vizinhos e >=1 destinatario honesto em cada metade (half=max(1,(n-1)/2), vizinhos=outros ids em ordem)",
           "f0_nao_listado": "f=0 (40 das 180 celulas) nao tem bizantino e nao aciona a regra; listadas so as 140 celulas com f>=1",
           "limite_da_previsao": ("cobre SO a regra requireEquivocationSplit. Outros modos de falha (nenhum bloco finalizado => rc!=0; "
                                  "visoes que nunca decidem) so aparecem simulando e NAO estao nesta lista"),
           "resumo_por_n_f": resumo, "celulas": cel}
    n_ab = sum(1 for c in cel if c["aborta_pela_regra_do_ataque_(previsao)"])
    dentro = sum(1 for c in cel if not c["fora_da_faixa"] and c["aborta_pela_regra_do_ataque_(previsao)"])
    doc["totais"] = {"celulas_f_ge_1": len(cel), "abortam_previsto": n_ab, "abortam_dentro_da_faixa": dentro,
                     "abortam_fora_da_faixa": n_ab - dentro}
    (VAL / "vb-celulas-bloqueadas.json").write_text(json.dumps(doc, indent=1, ensure_ascii=False))
    for k, v in resumo.items():
        print(f"{k:8s} fora_da_faixa={str(v['fora_da_faixa']):5s} abortam {v['abortam']:2d}/{v['de']}  sementes={v['sementes_que_abortam']}")
    print(doc["totais"])


if __name__ == "__main__":
    main()

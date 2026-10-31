#!/usr/bin/env python3
"""Gera resumo.txt (tabela caso x semente) a partir de saida-final/*.txt. Uso: python3 resumo.py (neste dir).
A CLASSE e medida, nao rotulada: 'termina' se a baseline (base-a e base-b) terminou com rc=0; 'trava' se
base-a/base-b deram TIMEOUT/erro. Veredito: I=IDENTICO D=DIVERGENTE ?=SEM_DADOS; ordem das colunas:
base-a x base-b | atual-a x atual-b | base-a x atual-a."""
import re
from pathlib import Path

d = Path(__file__).resolve().parent
V = {"IDENTICO": "I", "DIVERGENTE": "D", "SEM_DADOS": "?"}
linhas = [f"{'classe':7} {'familia':9} {'caso':16} {'sem':3} {'exit':4} {'base x base':11} {'atual x atual':13} {'base x atual':12} rc,segundos(base-a,base-b,atual-a,atual-b)"]
cont = {"termina": [0, 0], "trava": [0, 0]}  # [casos, casos com base x atual == I]
for f in sorted((d / "saida-final").glob("*.txt")):
    t = f.read_text()
    fam = f.name.split("-")[1]
    m = re.search(r"== (\w+) n=(\d+) duracao=(\d+)s semente=(\d+)", t)
    ex = re.search(r"exit=(\d+)", t).group(1)
    rc = re.search(r"execucoes \(rc, segundos\): (.*)", t).group(1)
    base_ok = "base-a=0," in rc and "base-b=0," in rc
    classe = "termina" if base_ok else "trava"
    if "INCONCLUSIVO" in t:
        ve = ["-", "-", "-"]
    else:
        ve = [V[x] for x in re.findall(r": (IDENTICO|DIVERGENTE|SEM_DADOS)", t)]
    cont[classe][0] += 1
    cont[classe][1] += ve[2:3] == ["I"]
    linhas.append(f"{classe:7} {fam:9} {m.group(1)+':'+m.group(2)+':'+m.group(3):16} {m.group(4):3} {ex:4} "
                  f"{ve[0]:11} {ve[1]:13} {ve[2]:12} {rc.replace(' ', '')}")
linhas.append("")
linhas.append(f"# termina na baseline: {cont['termina'][0]} execucoes, base x atual IDENTICO em {cont['termina'][1]}")
linhas.append(f"# trava na baseline  : {cont['trava'][0]} execucoes (base TIMEOUT; so a saida nova existe)")
(d / "resumo.txt").write_text("\n".join(linhas) + "\n")
print("\n".join(linhas))

#!/usr/bin/env python3
"""Compara DUAS execucoes da MESMA celula (mesma semente, mesmo build, -XX:hashCode=2): saidas de dado tem de ser
IDENTICAS byte a byte (CSV por bloco descomprimido, mvp-results*.csv, *-nos.csv, *-corrida.csv, observador-por-altura).
Ignora logs, comando-*.txt, resultado.json (tempos de parede) e as linhas '#' com carimbo. Saida 0 = identico.
Uso: comparar-determinismo.py <dir_celula_A> <dir_celula_B>
"""
import glob
import gzip
import hashlib
import sys
from pathlib import Path


def conteudo(p):
    op = gzip.open if str(p).endswith(".gz") else open
    with op(p, "rt", encoding="utf-8") as f:
        return "".join(l for l in f if not l.startswith("# JVM") )


def main():
    a, b = Path(sys.argv[1]), Path(sys.argv[2])
    padroes = ["hotstuff-*v-t0.csv.gz", "mvp-results.csv", "mvp-results-cb.csv", "*-nos.csv", "*-corrida.csv",
               "obs/observador-por-altura.csv.gz", "obs/observador-trocas-por-timeout.csv.gz",
               "obs/pacotes-por-altura.csv.gz"]   # EMENDA 1b: pacotes por altura (ObservadorVal)
    ok, n = True, 0
    for pad in padroes:
        fa = sorted(glob.glob(str(a / pad)))
        fb = sorted(glob.glob(str(b / pad)))
        if [Path(x).name for x in fa] != [Path(x).name for x in fb]:
            print("ARQUIVOS DIFEREM", pad, fa, fb)
            ok = False
            continue
        for x, y in zip(fa, fb):
            n += 1
            ha, hb = hashlib.sha256(conteudo(x).encode()).hexdigest(), hashlib.sha256(conteudo(y).encode()).hexdigest()
            if ha != hb:
                print("DIVERGE", Path(x).name)
                ok = False
    print(f"{n} arquivo(s) comparado(s):", "IDENTICO" if ok and n else "NAO IDENTICO")
    sys.exit(0 if ok and n else 1)


if __name__ == "__main__":
    main()

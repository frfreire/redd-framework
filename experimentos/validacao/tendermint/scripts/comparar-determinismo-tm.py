#!/usr/bin/env python3
"""Compara DUAS execucoes da MESMA celula do V-ABC do Tendermint (mesma semente, mesmo build, -XX:hashCode=2): saidas de dado tem de ser IDENTICAS
byte a byte (CSV por bloco descomprimido, mvp-results*.csv, observador-por-altura, pacotes-por-altura, por-no-por-altura). Ignora logs, comando-*.txt,
resultado.json (tempos de parede) e linhas '# JVM'. Em celulas vb (so observador) os arquivos mvp-* nao existem nas duas pastas: e aceito, desde que os
nomes batam. Saida 0 = identico. Adaptado de validacao/hotstuff/scripts/comparar-determinismo.py (nomes tendermint-*; sem -nos.csv/-corrida.csv:
o Tendermint do V-ABC nao usa --load-record).
Uso: comparar-determinismo-tm.py <dir_celula_A> <dir_celula_B>
"""
import glob
import gzip
import hashlib
import sys
from pathlib import Path


def conteudo(p):
    op = gzip.open if str(p).endswith(".gz") else open
    with op(p, "rt", encoding="utf-8") as f:
        return "".join(l for l in f if not l.startswith("# JVM"))


def main():
    a, b = Path(sys.argv[1]), Path(sys.argv[2])
    padroes = ["tendermint-*v-t0.csv.gz", "mvp-results.csv", "mvp-results-cb.csv", "obs/observador-por-altura.csv.gz",
               "obs/pacotes-por-altura.csv.gz", "obs/por-no-por-altura.csv.gz"]
    ok, n = True, 0
    for pad in padroes:
        fa, fb = sorted(glob.glob(str(a / pad))), sorted(glob.glob(str(b / pad)))
        if [Path(x).name for x in fa] != [Path(x).name for x in fb]:
            print("ARQUIVOS DIFEREM", pad, fa, fb)
            ok = False
            continue
        for x, y in zip(fa, fb):
            n += 1
            if hashlib.sha256(conteudo(x).encode()).hexdigest() != hashlib.sha256(conteudo(y).encode()).hexdigest():
                print("DIVERGE", Path(x).name)
                ok = False
    print(f"{n} arquivo(s) comparado(s):", "IDENTICO" if ok and n else "NAO IDENTICO")
    sys.exit(0 if ok and n else 1)


if __name__ == "__main__":
    main()

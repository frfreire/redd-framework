#!/usr/bin/env python3
"""Arquiva (<200 KB) a evidencia da tarefa LOGGER-csv-flushBuffer-corrida.

Copia para <destino>/amostras-baseline/ os CSV divergentes encontrados pelo diagnostico de carga na BASELINE
(diag_determinismo_carga.py --manter-saidas nao e necessario: as pastas divergentes ficam) mais a referencia e000,
e grava AMOSTRAS.txt com sha256, linhas e contagem de linhas com numero de colunas diferente do cabecalho.

Uso: arquivar_logger_flush.py --origem /tmp/opencode/nr-logger/diag-p48-base2400 --destino <dir> --exec e000 e350 e576
Autoteste: --selftest
"""
import argparse
import csv
import hashlib
import os
import shutil
import sys
import tempfile

LIMITE = 200 * 1024


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


def linhas_colunas_erradas(p):
    """(linhas de dados, linhas com colunas != cabecalho); ignora linhas '#'."""
    with open(p, newline="", encoding="utf-8", errors="replace") as f:
        linhas = [l for l in f.read().splitlines() if l and not l.startswith("#")]
    if not linhas:
        return 0, 0
    n = len(next(csv.reader([linhas[0]])))
    erradas = sum(1 for l in linhas[1:] if len(next(csv.reader([l]))) != n)
    return len(linhas) - 1, erradas


def arquivar(origem, destino, execs, nome="tendermint-4v-t0.csv"):
    saida = []
    total = 0
    os.makedirs(os.path.join(destino, "amostras-baseline"), exist_ok=True)
    for e in execs:
        src = os.path.join(origem, e, nome)
        if not os.path.isfile(src):
            saida.append(f"{e}: ausente em {src}")
            continue
        dst = os.path.join(destino, "amostras-baseline", f"{e}-{nome}")
        shutil.copyfile(src, dst)
        total += os.path.getsize(dst)
        dados, erradas = linhas_colunas_erradas(dst)
        saida.append(f"{e}: {os.path.getsize(dst)} B sha256={sha256(dst)} linhas_de_dados={dados} colunas_erradas={erradas}")
    if total > LIMITE:
        sys.exit(f"amostras somam {total} B > {LIMITE} B")
    with open(os.path.join(destino, "amostras-baseline", "AMOSTRAS.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(saida) + "\n")
    return saida


def selftest():
    with tempfile.TemporaryDirectory() as t:
        os.makedirs(os.path.join(t, "o", "e1"))
        with open(os.path.join(t, "o", "e1", "tendermint-4v-t0.csv"), "w") as f:
            f.write("# c\na,b\n1,2\n3\n4,5\n")
        r = arquivar(os.path.join(t, "o"), os.path.join(t, "d"), ["e1", "e2"])
        assert "linhas_de_dados=3 colunas_erradas=1" in r[0], r
        assert "ausente" in r[1], r
    print("SELFTEST OK")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--origem")
    ap.add_argument("--destino")
    ap.add_argument("--exec", nargs="*", default=[])
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        selftest()
        return
    if not a.origem or not a.destino:
        ap.error("--origem e --destino sao obrigatorios")
    for l in arquivar(a.origem, a.destino, a.exec):
        print(l)


if __name__ == "__main__":
    main()

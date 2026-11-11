#!/usr/bin/env python3
"""Arquiva a evidencia do B1 (HS-CB-fase2): amostras divergentes do diag_determinismo_carga.py + tabela com sha256.

Para cada item `ROTULO=PASTA:eNNN,eMMM` copia o CSV por bloco de e000 (referencia) e das execucoes citadas para
<destino>/<ROTULO>/<eNNN>/ (so *.csv que NAO sejam mvp-results*; limite 200 KB cada) e escreve <destino>/AMOSTRAS.txt com
bytes, linhas, sha256 e se o CSV tem linha com numero de colunas diferente do cabecalho (escrita entrelacada).
Uso: arquivar_b1.py --destino src/test/resources/nao-regressao/hs-cb-fase2/b1 \
        TM-p24=/tmp/opencode/b1/TM-p24:e066 TM-p48=/tmp/opencode/b1/TM-p48:e004,e117,e242
Autoteste: --selftest
"""
import argparse
import hashlib
import sys
from pathlib import Path

LIMITE = 200 * 1024


def colunas_ruins(texto: str) -> int:
    ls = [l for l in texto.splitlines() if l.strip() and not l.startswith("#")]
    if not ls:
        return 0
    k = ls[0].count(",")
    return sum(1 for l in ls[1:] if l.count(",") != k)


def selftest():
    assert colunas_ruins("# c\na,b\n1,2\n3,4\n") == 0
    assert colunas_ruins("a,b\n1,2\n3,4,5\n6\n") == 2
    print("SELFTEST OK")
    return 0


def main():
    if "--selftest" in sys.argv[1:]:
        return selftest()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--destino", required=True)
    ap.add_argument("itens", nargs="+")
    a = ap.parse_args()
    dest = Path(a.destino)
    linhas = ["# rotulo/execucao/arquivo | bytes | linhas | sha256 | linhas_com_n_colunas_diferente_do_cabecalho | copiado"]
    for item in a.itens:
        rot, _, resto = item.partition("=")
        pasta, _, execs = resto.rpartition(":")
        for e in ["e000"] + [x for x in execs.split(",") if x and x != "e000"]:
            d = Path(pasta) / e
            for p in sorted(d.glob("*.csv")):
                if p.name.startswith("mvp-results"):
                    continue
                b = p.read_bytes()
                ok = len(b) <= LIMITE
                if ok:
                    alvo = dest / rot / e / p.name
                    alvo.parent.mkdir(parents=True, exist_ok=True)
                    alvo.write_bytes(b)
                linhas.append(" | ".join([f"{rot}/{e}/{p.name}", str(len(b)), str(b.count(b"\n")),
                                          hashlib.sha256(b).hexdigest(), str(colunas_ruins(b.decode("utf-8", "replace"))),
                                          "sim" if ok else "NAO (> 200 KB)"]))
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "AMOSTRAS.txt").write_text("\n".join(linhas) + "\n", encoding="utf-8")
    print(f"OK {len(linhas) - 1} CSV em {dest}/AMOSTRAS.txt")
    return 0


if __name__ == "__main__":
    sys.exit(main())

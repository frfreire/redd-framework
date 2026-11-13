#!/usr/bin/env python3
"""Grava / confere o SHA-256 de PREREGISTRO.md (confirmacao-k40/PREREGISTRO.sha256) e, com --artefatos, confere tambem os
SHA-256 dos arquivos listados na tabela de artefatos DENTRO do PREREGISTRO.md (linhas `| `caminho` | `sha256` |`,
caminhos relativos a raiz do repositorio).

Uso:
  hash-preregistro.py gravar                 grava o hash (recusa sobrescrever: o registro e unico)
  hash-preregistro.py conferir               confere PREREGISTRO.md contra PREREGISTRO.sha256
  hash-preregistro.py conferir --artefatos   idem + cada artefato da tabela (scripts, config, celulas-esperadas, blobs)
  hash-preregistro.py listar <caminho>...    imprime linhas da tabela de artefatos (auxiliar; nao grava)
Saida 0 = tudo inalterado; 1 = divergencia.
Obs.: o hash fica em git quando o orquestrador commita o pre-registro ANTES da etapa 2 (proveniencia imutavel).
"""
import hashlib
import re
import sys
from pathlib import Path

K40 = Path(__file__).resolve().parent.parent
RAIZ = K40.parents[2]
ARQ = K40 / "PREREGISTRO.md"
SHA = K40 / "PREREGISTRO.sha256"


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def main():
    cmd = sys.argv[1:2]
    if cmd == ["gravar"]:
        if SHA.exists():
            sys.exit("ja existe hash gravado; nao sobrescrevo (o pre-registro e unico)")
        h = sha(ARQ)
        SHA.write_text(f"{h}  PREREGISTRO.md\n")
        print("gravado", h)
    elif cmd == ["conferir"]:
        gravado = SHA.read_text().split()[0]
        atual = sha(ARQ)
        ok = gravado == atual
        print("PREREGISTRO.md: OK, inalterado" if ok else f"PREREGISTRO.md ALTERADO: gravado={gravado} atual={atual}")
        if "--artefatos" in sys.argv:
            tab = re.findall(r"\|\s*`([^`]+)`\s*\|\s*`([0-9a-f]{64})`\s*\|", ARQ.read_text(encoding="utf-8"))
            if not tab:
                sys.exit("tabela de artefatos nao encontrada no PREREGISTRO.md")
            for caminho, esperado in tab:
                p = RAIZ / caminho
                got = sha(p) if p.exists() else "AUSENTE"
                bom = got == esperado
                ok = ok and bom
                print(("OK       " if bom else "DIVERGE  ") + caminho)
        sys.exit(0 if ok else 1)
    elif cmd == ["listar"]:  # auxiliar: imprime `| `caminho` | `sha256` |` para os caminhos dados (sem gravar nada)
        for c in sys.argv[2:]:
            print(f"| `{c}` | `{sha(RAIZ / c)}` |")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()

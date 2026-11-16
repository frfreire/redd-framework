#!/usr/bin/env python3
"""Grava / confere o SHA-256 de PREREGISTRO.md (validacao/hotstuff/PREREGISTRO.sha256) e, com --artefatos, confere tambem os
SHA-256 dos arquivos listados na tabela de artefatos DENTRO do PREREGISTRO.md (linhas `| `caminho` | `sha256` |`,
caminhos relativos a raiz do repositorio).

Uso:
  hash-preregistro.py gravar                 grava o hash (recusa sobrescrever: o registro e unico)
  hash-preregistro.py gravar-emenda          EMENDA 1b (16/11/2026): troca o hash vigente SO se o gravado ainda e o da v1
                                             (arquivada em PREREGISTRO.v1-original-2026-11-16.md, hash em PREREGISTRO.v1-original.sha256)
  hash-preregistro.py conferir [--artefatos] confere (0 = inalterado, 1 = divergencia)
  hash-preregistro.py listar <caminho>...    imprime linhas da tabela de artefatos (auxiliar; nao grava)
"""
import hashlib
import re
import sys
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
RAIZ = VAL.parents[2]
ARQ = VAL / "PREREGISTRO.md"
V1_SHA = VAL / "PREREGISTRO.v1-original.sha256"
# EMENDA 1b: a tabela de artefatos VIGENTE e a que vem depois deste marcador (a da v1 fica no texto como historico e NAO e conferida)
MARCA_VIGENTE = "### Artefatos vigentes (SHA-256) apos a Emenda 1b"
SHA = VAL / "PREREGISTRO.sha256"


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
    elif cmd == ["gravar-emenda"]:
        v1 = V1_SHA.read_text().split()[0]
        atual_gravado = SHA.read_text().split()[0]
        if atual_gravado != v1:
            sys.exit(f"hash vigente ({atual_gravado}) nao e o da v1 ({v1}): a emenda 1b ja foi gravada; nao sobrescrevo")
        h = sha(ARQ)
        if h == v1:
            sys.exit("PREREGISTRO.md ainda e a v1; escreva a secao de emendas antes")
        SHA.write_text(f"{h}  PREREGISTRO.md\n")
        print("gravado (emenda 1b)", h)
    elif cmd == ["conferir"]:
        gravado = SHA.read_text().split()[0]
        atual = sha(ARQ)
        ok = gravado == atual
        print("PREREGISTRO.md: OK, inalterado" if ok else f"PREREGISTRO.md ALTERADO: gravado={gravado} atual={atual}")
        if "--artefatos" in sys.argv:
            texto = ARQ.read_text(encoding="utf-8")
            if MARCA_VIGENTE in texto:
                texto = texto.split(MARCA_VIGENTE)[-1]
            tab = re.findall(r"\|\s*`([^`]+)`\s*\|\s*`([0-9a-f]{64})`\s*\|", texto)
            if not tab:
                sys.exit("tabela de artefatos nao encontrada no PREREGISTRO.md")
            for caminho, esperado in tab:
                p = RAIZ / caminho
                got = sha(p) if p.exists() else "AUSENTE"
                bom = got == esperado
                ok = ok and bom
                print(("OK       " if bom else "DIVERGE  ") + caminho)
        sys.exit(0 if ok else 1)
    elif cmd == ["listar"]:
        for c in sys.argv[2:]:
            print(f"| `{c}` | `{sha(RAIZ / c)}` |")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()

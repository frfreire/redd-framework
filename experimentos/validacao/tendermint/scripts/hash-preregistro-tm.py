#!/usr/bin/env python3
"""Grava / confere o SHA-256 de PREREGISTRO.md do V-ABC-tendermint (validacao/tendermint/PREREGISTRO.sha256) e, com --artefatos, os SHA-256 dos
arquivos listados na tabela de artefatos DENTRO do PREREGISTRO.md (linhas `| `caminho` | `sha256` |`, caminhos relativos a raiz do repositorio).

RASCUNHO (etapa 1): o hash do pre-registro SO VALE DEPOIS DO PINO. Por isso `gravar` RECUSA enquanto o PREREGISTRO.md contiver a marca
"COMMIT_PINADO = a definir" e enquanto preparar-tm.py tiver COMMIT_PINADO = None. Nao ha `gravar-emenda` aqui: a primeira gravacao e a unica.
Uso:
  hash-preregistro-tm.py estado                     diz se o pino ja existe e se o hash pode ser gravado (0 = pode)
  hash-preregistro-tm.py gravar                     grava o hash (recusa sobrescrever e recusa sem pino)
  hash-preregistro-tm.py conferir [--artefatos]     0 = inalterado; com --artefatos confere a tabela
  hash-preregistro-tm.py listar <caminho>...        imprime linhas da tabela de artefatos (auxiliar; nao grava)
"""
import hashlib
import re
import sys
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
RAIZ = VAL.parents[2]
ARQ = VAL / "PREREGISTRO.md"
SHA = VAL / "PREREGISTRO.sha256"
MARCA_PENDENTE = "COMMIT_PINADO = a definir"
MARCA_TABELA = "### Artefatos (SHA-256)"


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def pino_pendente():
    motivos = []
    if MARCA_PENDENTE in ARQ.read_text(encoding="utf-8"):
        motivos.append(f"PREREGISTRO.md ainda contem '{MARCA_PENDENTE}'")
    prep = (VAL / "scripts" / "preparar-tm.py").read_text(encoding="utf-8")
    if re.search(r"^COMMIT_PINADO\s*=\s*None", prep, re.M):
        motivos.append("preparar-tm.py: COMMIT_PINADO = None")
    return motivos


def main():
    cmd = sys.argv[1:2]
    if cmd == ["estado"]:
        m = pino_pendente()
        print("PINO PENDENTE: " + "; ".join(m) if m else "pino definido")
        sys.exit(1 if m else 0)
    elif cmd == ["gravar"]:
        m = pino_pendente()
        if m:
            sys.exit("RECUSADO (o hash so vale depois do pino): " + "; ".join(m))
        if SHA.exists():
            sys.exit("ja existe hash gravado; nao sobrescrevo (o pre-registro e unico; mudanca = emenda datada em arquivo novo)")
        h = sha(ARQ)
        SHA.write_text(f"{h}  PREREGISTRO.md\n")
        print("gravado", h)
    elif cmd == ["conferir"]:
        if not SHA.exists():
            sys.exit("sem PREREGISTRO.sha256 (rascunho: o hash so e gravado depois do pino)")
        gravado, atual = SHA.read_text().split()[0], sha(ARQ)
        ok = gravado == atual
        print("PREREGISTRO.md: OK, inalterado" if ok else f"PREREGISTRO.md ALTERADO: gravado={gravado} atual={atual}")
        if "--artefatos" in sys.argv:
            texto = ARQ.read_text(encoding="utf-8")
            if MARCA_TABELA in texto:
                texto = texto.split(MARCA_TABELA)[-1]
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

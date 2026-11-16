#!/usr/bin/env python3
"""EMENDA E7 (16/11/2026, V-B liberado). Preserva o historico e grava o hash vigente SEM tocar em hash-preregistro.py
(cujo SHA-256 esta na tabela de artefatos da Emenda 1b).

  hash-emenda-e7.py arquivar-v2   copia byte a byte o PREREGISTRO.md vigente ANTES da E7 (v2 = v1 + Emenda 1b, SHA-256
                                  bf1d85f7...) para PREREGISTRO.v2-emenda-1b-2026-11-16.md + PREREGISTRO.v2-emenda-1b.sha256.
                                  Recusa se o PREREGISTRO.md atual nao for exatamente a v2, ou se o arquivo ja existir.
  hash-emenda-e7.py gravar        confere que o PREREGISTRO.md novo e a v2 ARQUIVADA + texto acrescentado (so anexa: nenhum byte
                                  anterior muda) e troca o hash em PREREGISTRO.sha256 (so se o gravado ainda for o da v2).
  hash-emenda-e7.py conferir      confere o arquivo da v2 e que o PREREGISTRO.md atual ainda comeca por ele.
Nao simula nada.
"""
import hashlib
import sys
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
V2_SHA = "bf1d85f78a1f70e5f74b368e836a80b32cd3e065e3c0c834d37d0cfc44ed4ec2"
ARQ = VAL / "PREREGISTRO.md"
V2 = VAL / "PREREGISTRO.v2-emenda-1b-2026-11-16.md"
V2_HASH = VAL / "PREREGISTRO.v2-emenda-1b.sha256"
SHA = VAL / "PREREGISTRO.sha256"


def sha(b):
    return hashlib.sha256(b).hexdigest()


def main():
    cmd = sys.argv[1:2]
    if cmd == ["arquivar-v2"]:
        if V2.exists():
            sys.exit(f"ja existe {V2.name}; nao sobrescrevo")
        dados = ARQ.read_bytes()
        if sha(dados) != V2_SHA:
            sys.exit(f"PREREGISTRO.md atual ({sha(dados)}) nao e a v2 ({V2_SHA}); nada arquivado")
        V2.write_bytes(dados)
        V2_HASH.write_text(f"{V2_SHA}  {V2.name}\n")
        print("arquivado", V2.name, V2_SHA)
    elif cmd == ["gravar"]:
        v2 = V2.read_bytes()
        if sha(v2) != V2_SHA:
            sys.exit("o arquivo da v2 foi alterado")
        novo = ARQ.read_bytes()
        if novo == v2:
            sys.exit("PREREGISTRO.md ainda e a v2; escreva a secao E7 antes")
        if not novo.startswith(v2):
            sys.exit("PREREGISTRO.md novo NAO comeca pela v2 arquivada: a E7 deve so ANEXAR; nenhum byte anterior pode mudar")
        gravado = SHA.read_text().split()[0]
        if gravado != V2_SHA:
            sys.exit(f"hash vigente ({gravado}) nao e o da v2: a E7 ja foi gravada; nao sobrescrevo")
        SHA.write_text(f"{sha(novo)}  PREREGISTRO.md\n")
        print("gravado (E7)", sha(novo), "| bytes acrescentados:", len(novo) - len(v2))
    elif cmd == ["conferir"]:
        v2 = V2.read_bytes()
        ok = sha(v2) == V2_SHA and ARQ.read_bytes().startswith(v2)
        print("v2 arquivada integra e PREREGISTRO.md so anexa a ela" if ok else "DIVERGE")
        sys.exit(0 if ok else 1)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()

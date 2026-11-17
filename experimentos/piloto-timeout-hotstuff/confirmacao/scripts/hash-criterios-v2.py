#!/usr/bin/env python3
"""Grava (ou confere) o SHA-256 de CRITERIOS-congelados-v2.md em CRITERIOS-congelados-v2.sha256.
Uso: hash-criterios-v2.py gravar | conferir     (o congelamento e unico: nao sobrescreve)
"""
import hashlib
import sys
from pathlib import Path

CONF = Path(__file__).resolve().parent.parent
arq = CONF / "CRITERIOS-congelados-v2.md"
sha = CONF / "CRITERIOS-congelados-v2.sha256"
h = hashlib.sha256(arq.read_bytes()).hexdigest()
if sys.argv[1:] == ["gravar"]:
    if sha.exists():
        sys.exit("ja existe hash gravado; nao sobrescrevo (o congelamento e unico)")
    sha.write_text(f"{h}  CRITERIOS-congelados-v2.md\n")
    print("gravado", h)
elif sys.argv[1:] == ["conferir"]:
    antigo = sha.read_text().split()[0]
    print("OK, inalterado" if antigo == h else f"ALTERADO: gravado={antigo} atual={h}")
    sys.exit(0 if antigo == h else 1)
else:
    sys.exit(__doc__)

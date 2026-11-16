#!/usr/bin/env python3
"""Arquiva (copia byte a byte) o PREREGISTRO.md ORIGINAL de 16/11/2026 antes da Emenda 1b, para o historico nao se perder
(experimentos/validacao/ ainda nao esta no git). Confere que o hash do original e o gravado em PREREGISTRO.sha256 (v1).
Recusa sobrescrever. Uso: arquivar-original.py
"""
import hashlib
import sys
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
ORIG_SHA = "17a30899d3fbfc67e4bf64a85e129b7c2976a3370a24f2ccc89daa3bfde85617"
dest = VAL / "PREREGISTRO.v1-original-2026-11-16.md"
dados = (VAL / "PREREGISTRO.md").read_bytes()
h = hashlib.sha256(dados).hexdigest()
if dest.exists():
    sys.exit(f"ja existe {dest.name}; nao sobrescrevo (sha256 atual dele: {hashlib.sha256(dest.read_bytes()).hexdigest()})")
if h != ORIG_SHA:
    sys.exit(f"PREREGISTRO.md atual ({h}) nao e o original v1 ({ORIG_SHA}); nada arquivado")
dest.write_bytes(dados)
sha_v1 = VAL / "PREREGISTRO.v1-original.sha256"
sha_v1.write_text(f"{h}  PREREGISTRO.v1-original-2026-11-16.md\n")
print("arquivado", dest.name, h, "| hash v1 em", sha_v1.name)

#!/usr/bin/env python3
"""AUXILIAR de leitura (V-C): baixa um PDF e extrai o texto dos fluxos FlateDecode (operadores Tj/TJ), sem biblioteca externa.
So leitura de fonte publicada; nao simula. Limite: fontes com codificacao propria (CID/Type0) saem ilegiveis; o script reporta a fracao de
caracteres legiveis para o orquestrador/critic saberem se a leitura vale. Uso: aux-ler-pdf.py <url> [saida.txt]"""
import re
import sys
import urllib.request
import zlib
from pathlib import Path

url = sys.argv[1]
dados = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=60).read()
textos = []
for m in re.finditer(rb"stream\r?\n(.*?)\r?\nendstream", dados, re.S):
    try:
        bruto = zlib.decompress(m.group(1))
    except Exception:
        continue
    if b"Tj" not in bruto and b"TJ" not in bruto:
        continue
    partes = []
    for t in re.finditer(rb"\[(.*?)\]\s*TJ|\((.*?)(?<!\\)\)\s*Tj", bruto, re.S):
        if t.group(1) is not None:
            for s in re.finditer(rb"\((.*?)(?<!\\)\)|(-?\d+\.?\d*)", t.group(1), re.S):
                if s.group(1) is not None:
                    partes.append(s.group(1).decode("latin-1"))
                elif float(s.group(2)) < -200:
                    partes.append(" ")
        else:
            partes.append(t.group(2).decode("latin-1") + " ")
    textos.append("".join(partes))
txt = "\n".join(textos)
legivel = sum(c.isalpha() or c == " " for c in txt) / max(1, len(txt))
print(f"bytes={len(dados)} fluxos_com_texto={len(textos)} caracteres={len(txt)} fracao_legivel={legivel:.2f}", file=sys.stderr)
if len(sys.argv) > 2:
    Path(sys.argv[2]).write_text(txt, encoding="utf-8")
else:
    print(txt[:3000])

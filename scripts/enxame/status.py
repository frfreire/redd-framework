#!/usr/bin/env python3
"""Resumo do estado do enxame: loops, gates e arquivos de dominio."""
import subprocess
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
print("== Loops", flush=True)
subprocess.run([sys.executable, str(RAIZ / "scripts/enxame/registrar-rodada.py"), "--status"], check=False)
print("\n== Gates", flush=True)
for lid in ("loop1-chaincode", "loop2-jabs-codigo"):
    r = subprocess.run([sys.executable, str(RAIZ / "scripts/enxame/registrar-rodada.py"), "--verificar-gate", lid],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    linhas = (r.stdout.strip() or r.stderr.strip()).splitlines()
    if not linhas:
        print(f"   {lid}: (sem saida; codigo {r.returncode})")
    for i, linha in enumerate(linhas):
        print(f"   {lid}: {linha}" if i == 0 else f"      {linha.strip()}")
print("\n== Alvos")
for nome in ("especificacoes/dominio-redd.md", "chaincode", "rede-fabric", "api-gateway", "src/main/java/jabs", "experimentos"):
    print(f"   {nome:34s} {'presente' if (RAIZ / nome).exists() else 'ausente'}")

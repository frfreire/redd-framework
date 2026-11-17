#!/usr/bin/env python3
"""Sensibilidade a mutacao dos testes de countFromFraction (BYZ-UNICA, rodada 4, criterio 3).

Da raiz do repositorio:  python3 scripts/jabs/mutar-countfromfraction.py [--destino /tmp/opencode/byz-mut-r4]

Trabalha SEMPRE numa COPIA (pom.xml + src) em /tmp/opencode; nunca escreve em src/ do repositorio.
1. baseline: copia do codigo atual; ByzantineContagemUnicaTest DEVE passar.
2. mutante M1: corpo ANTIGO de countFromFraction ((long) universe * num; (produto + den - 1) / den) na copia;
   os testes countFromFraction_* DEVEM falhar.
Saida: 0 = baseline passou E mutante detectado; 1 = baseline falhou ou mutante sobreviveu; 2 = ancora ausente.
"""
import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

ALVO = Path("src/main/java/jabs/config/ByzantineConfig.java")
ANTIGO = "        long produto = (long) universe * num;\n        return (int) ((produto + den - 1) / den);\n"
RE_NOVO = re.compile(r"        // BigInteger: universe.*?intValueExact\(\);\n", re.S)


def copiar(raiz: Path, destino: Path) -> None:
    if destino.exists():
        shutil.rmtree(destino)
    destino.mkdir(parents=True)
    shutil.copy2(raiz / "pom.xml", destino / "pom.xml")
    shutil.copytree(raiz / "src", destino / "src")


def testar(destino: Path, mvn: list) -> tuple:
    p = subprocess.run(mvn + ["test", "-Dtest=ByzantineContagemUnicaTest"], cwd=destino, capture_output=True, text=True)
    saida = p.stdout + p.stderr
    falhas = sorted(set(re.findall(r"ByzantineContagemUnicaTest\.(\w+)", saida)))
    return p.returncode, saida, falhas


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--destino", default="/tmp/opencode/byz-mut-r4")
    ap.add_argument("--mvn", default="mvn -o -q")
    a = ap.parse_args()
    raiz = Path.cwd()
    destino = Path(a.destino)
    if not str(destino.resolve()).startswith("/tmp/opencode/"):
        print("ERRO: destino precisa estar em /tmp/opencode/")
        return 2
    mvn = a.mvn.split()

    copiar(raiz, destino)
    rc, _, _ = testar(destino, mvn)
    print(f"baseline (codigo novo, copia): {'PASSOU' if rc == 0 else 'FALHOU'}")
    if rc != 0:
        return 1

    alvo = destino / ALVO
    texto = alvo.read_text()
    if not RE_NOVO.search(texto):
        print("ERRO: ancora do corpo novo nao encontrada")
        return 2
    alvo.write_text(RE_NOVO.sub(lambda _m: ANTIGO, texto, count=1))
    rc, saida, falhas = testar(destino, mvn)
    print(f"M1 corpo antigo (copia): {'SOBREVIVEU (ruim)' if rc == 0 else 'DETECTADO'}; testes que falharam: {falhas}")
    if rc == 0:
        return 1
    if "countFromFraction_nao_transborda_long_caso_do_critic" not in falhas:
        print("ERRO: o teste do caso do critic nao esta entre os que falharam")
        return 1
    print(f"repo intacto: {raiz / ALVO} nao foi tocado")
    return 0


if __name__ == "__main__":
    sys.exit(main())

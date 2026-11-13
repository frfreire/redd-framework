#!/usr/bin/env python3
"""Prepara a copia de build do simulador a partir do HEAD (sem rede-fabric, sem chaves).

Copia so src/, pom.xml e scripts/ para /tmp/opencode/piloto-hs, compila com Maven (sem testes),
monta o classpath com `mvn dependency:build-classpath` e grava a versao do simulador
(git rev-parse HEAD + estado da arvore) em versao-simulador.json ao lado deste script.

Uso: python3 experimentos/piloto-timeout-hotstuff/scripts/preparar.py
"""
import json
import shutil
import subprocess
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[3]
DEST = Path("/tmp/opencode/piloto-hs")
SAIDA = Path(__file__).resolve().parent.parent / "versao-simulador.json"


def sh(cmd, cwd):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    return r.returncode, r.stdout, r.stderr


def main():
    _, head, _ = sh(["git", "rev-parse", "HEAD"], RAIZ)
    head = head.strip()
    _, porcelain, _ = sh(["git", "status", "--porcelain", "--", "src", "pom.xml", "scripts"], RAIZ)
    limpa = porcelain.strip() == ""
    if not limpa:
        print("AVISO: arvore com alteracoes em src/pom.xml/scripts:\n" + porcelain)
    if DEST.exists():
        shutil.rmtree(DEST)
    DEST.mkdir(parents=True)
    for nome in ("src", "pom.xml", "scripts"):
        origem = RAIZ / nome
        if origem.is_dir():
            shutil.copytree(origem, DEST / nome)
        else:
            shutil.copy2(origem, DEST / nome)
    rc, out, err = sh(["mvn", "-q", "-DskipTests", "compile"], DEST)
    if rc != 0:
        print(out[-3000:], err[-3000:])
        sys.exit(f"ERRO: mvn compile rc={rc}")
    cp_file = DEST / "cp.txt"
    rc, out, err = sh(["mvn", "-q", "dependency:build-classpath", f"-Dmdep.outputFile={cp_file}"], DEST)
    if rc != 0:
        print(out[-3000:], err[-3000:])
        sys.exit(f"ERRO: mvn dependency:build-classpath rc={rc}")
    # observador externo (fora de src/): so a API publica ja compilada, ver harness/ObservadorHotStuff.java
    hdir = DEST / "harness-classes"
    hdir.mkdir(exist_ok=True)
    cp = f"{DEST}/target/classes:" + cp_file.read_text().strip()
    fonte = RAIZ / "experimentos" / "piloto-timeout-hotstuff" / "harness" / "ObservadorHotStuff.java"
    rc, out, err = sh(["javac", "-nowarn", "-cp", cp, "-d", str(hdir), str(fonte)], DEST)
    if rc != 0:
        print(out[-3000:], err[-3000:])
        sys.exit(f"ERRO: javac do observador rc={rc}")
    _, jv, jv2 = sh(["java", "-version"], RAIZ)
    _, mv, _ = sh(["mvn", "-v"], RAIZ)
    info = {
        "git_head": head,
        "arvore_limpa_em_src_pom_scripts": limpa,
        "status_porcelain": porcelain.strip(),
        "copia_de_build": str(DEST),
        "classes": str(DEST / "target" / "classes"),
        "classpath_arquivo": str(cp_file),
        "observador_classes": str(hdir),
        "java_version": (jv + jv2).strip().splitlines()[0:3],
        "mvn_v": mv.strip().splitlines()[0:2],
    }
    SAIDA.write_text(json.dumps(info, indent=1, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(info, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()

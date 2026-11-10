#!/usr/bin/env python3
"""Congela o binario compilado da arvore ATUAL como baseline de nao regressao.

Copia target/classes e target/cp.txt para <destino>/target/ (a forma que nao_regressao.py espera em --base)
e grava <destino>/BASELINE.txt com o commit (HEAD) e o estado da arvore. RECUSA congelar se a arvore tem
alteracao (git status --porcelain nao vazio) ou se o HEAD nao e o commit esperado (--commit): assim o binario
congelado e, comprovadamente, o do commit citado. Use ANTES de editar o codigo:

  mvn -q -o clean compile && mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
  congelar-baseline.py --jabs . --commit 45eb3e7 --destino /tmp/opencode/base-45eb3e7

So le o git (rev-parse, status); nao escreve nada no repositorio. Saida: 0 ok; 2 recusado/erro.
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path


def git(jabs: Path, *args):
    r = subprocess.run(["git", *args], capture_output=True, text=True, cwd=str(jabs))
    if r.returncode != 0:
        sys.exit(f"ERRO: git {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout.strip()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jabs", required=True)
    ap.add_argument("--commit", required=True, help="prefixo do commit que o HEAD deve ter")
    ap.add_argument("--destino", required=True)
    a = ap.parse_args()
    jabs, destino = Path(a.jabs).resolve(), Path(a.destino).resolve()
    head = git(jabs, "rev-parse", "HEAD")
    if not head.startswith(a.commit):
        print(f"RECUSADO: HEAD={head} nao comeca com {a.commit}")
        return 2
    sujo = git(jabs, "status", "--porcelain", "--untracked-files=no")
    if sujo:
        print(f"RECUSADO: arvore com alteracoes rastreadas (nao e o commit puro):\n{sujo}")
        return 2
    classes, cp = jabs / "target" / "classes", jabs / "target" / "cp.txt"
    if not classes.is_dir() or not cp.is_file():
        print(f"RECUSADO: {classes} ou {cp} ausente (mvn -q -o clean compile + dependency:build-classpath)")
        return 2
    if destino.exists():
        print(f"RECUSADO: {destino} ja existe (nao sobrescrevo baseline)")
        return 2
    (destino / "target").mkdir(parents=True)
    shutil.copytree(classes, destino / "target" / "classes")
    shutil.copy2(cp, destino / "target" / "cp.txt")
    (destino / "BASELINE.txt").write_text(f"commit={head}\narvore_limpa=sim\nfonte={jabs}\n", encoding="utf-8")
    n = sum(1 for _ in (destino / "target" / "classes").rglob("*.class"))
    print(f"OK baseline {head[:7]} congelada em {destino} ({n} classes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

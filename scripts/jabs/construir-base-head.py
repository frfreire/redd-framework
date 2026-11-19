#!/usr/bin/env python3
r"""Constroi o build de um commit (padrao: HEAD) a partir do GIT, nunca da arvore de trabalho.

Passos: `git rev-parse` -> `git archive <commit>` (so o conteudo versionado) extraido em --destino -> `mvn compile` ->
`mvn dependency:build-classpath`. Grava <destino>/COMMIT com o hash completo (lido por comparar-chave-tx.py, que
recusa uma base cujo hash difira do HEAD). Nao altera a arvore de trabalho nem o indice.

Uso: construir-base-head.py [--commit HEAD] [--destino <TMP>/opencode/loggerchave-head] [--repo .]   (<TMP> = tempfile.gettempdir())
Recusa destino ja existente e nao vazio (nao sobrescreve). Saida: 0 ok, 2 erro.
"""
import argparse
import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path


def mvn_exe():
    """Executavel do Maven: mvn.cmd no Windows (subprocess sem shell nao resolve `mvn`), mvn fora dele."""
    if os.name == "nt":
        return shutil.which("mvn.cmd") or "mvn.cmd"
    return "mvn"


def run(cmd, cwd=None, out=None):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.stderr.write("falhou: %s\n%s\n%s\n" % (" ".join(cmd), r.stdout[-3000:], r.stderr[-3000:]))
        sys.exit(2)
    return r.stdout


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--commit", default="HEAD")
    ap.add_argument("--destino", default=str(Path(tempfile.gettempdir()) / "opencode" / "loggerchave-head"))
    ap.add_argument("--repo", default=".")
    a = ap.parse_args()
    repo = str(Path(a.repo).resolve())
    commit = run(["git", "rev-parse", "--verify", a.commit + "^{commit}"], cwd=repo).strip()
    dest = Path(a.destino)
    if dest.exists() and any(dest.iterdir()):
        sys.stderr.write("destino ja existe e nao esta vazio: %s (nao sobrescrevo)\n" % dest)
        return 2
    dest.mkdir(parents=True, exist_ok=True)
    tar = subprocess.run(["git", "archive", "--format=tar", commit, "src", "pom.xml"], cwd=repo, capture_output=True)
    if tar.returncode != 0:
        sys.stderr.write(tar.stderr.decode(errors="replace"))
        return 2
    with tarfile.open(fileobj=io.BytesIO(tar.stdout)) as t:
        t.extractall(dest)
    (dest / "COMMIT").write_text(commit + "\n", encoding="utf-8")
    run([mvn_exe(), "-q", "-o", "clean", "compile"], cwd=str(dest))
    run([mvn_exe(), "-q", "-o", "dependency:build-classpath", "-Dmdep.outputFile=target/cp.txt"], cwd=str(dest))
    print("commit", commit)
    print("fontes", dest / "src")
    print("classes", dest / "target" / "classes")
    print("cp", dest / "target" / "cp.txt")
    return 0


if __name__ == "__main__":
    sys.exit(main())

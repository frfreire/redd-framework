#!/usr/bin/env python3
"""Prepara o build do simulador para a rodada K=40 A PARTIR DO GIT (git archive), nunca da arvore de trabalho.

A arvore de trabalho pode ter mudancas nao commitadas de outro agente em src/ (Tx.java, logger, TxSequencer): elas NAO
entram. Contrato pre-registrado (PREREGISTRO.md, secao 5): a arvore src/ do commit tem de ser a de 9f305dd e o pom.xml
tambem; o observador (harness) tem de ser o blob pre-registrado. Qualquer divergencia ABORTA (nao ha fallback silencioso).

Passos: git rev-parse -> confere tree/blob -> git archive <commit> -- src pom.xml -> extrai em /tmp/opencode/k40-hs ->
mvn -q -DskipTests compile -> mvn dependency:build-classpath (cp.txt) -> javac do observador (blob do commit) ->
grava versao-simulador-k40.json.   Recusa destino ja existente e nao vazio.
Uso: preparar-k40.py [--commit HEAD] [--destino /tmp/opencode/k40-hs] [--registro <arquivo.json>]
(--registro: onde gravar o registro de versao; padrao confirmacao-k40/versao-simulador-k40.json. Um teste de build em outro
destino deve usar --registro em /tmp para nao criar dado na pasta do pre-registro.)
"""
import argparse
import io
import json
import subprocess
import sys
import tarfile
from pathlib import Path

K40 = Path(__file__).resolve().parent.parent
RAIZ = K40.parents[2]
TREE_SRC = "38820851e622b76a9f60e1a67520c60b48e2e6a5"   # git rev-parse 9f305dd:src
BLOB_POM = "32f934e332097d6bfed46830ff03132c36763670"   # git rev-parse 9f305dd:pom.xml
BLOB_OBS = "4024e15b53b8939458c22ae9d04f107b422e3cd0"   # harness/ObservadorHotStuff.java
OBS_REL = "experimentos/piloto-timeout-hotstuff/harness/ObservadorHotStuff.java"


def run(cmd, cwd=None, binario=False):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=not binario)
    if r.returncode != 0:
        err = r.stderr if not binario else r.stderr.decode(errors="replace")
        sys.exit(f"ERRO: {' '.join(cmd)}\n{err[-3000:]}")
    return r.stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--commit", default="HEAD")
    ap.add_argument("--destino", default="/tmp/opencode/k40-hs")
    ap.add_argument("--registro", default=str(K40 / "versao-simulador-k40.json"))
    a = ap.parse_args()
    dest = Path(a.destino)
    if dest.exists() and any(dest.iterdir()):
        sys.exit(f"ERRO: {dest} ja existe e nao esta vazio (nao sobrescrevo)")
    commit = run(["git", "rev-parse", a.commit], RAIZ).strip()
    tree = run(["git", "rev-parse", f"{commit}:src"], RAIZ).strip()
    pom = run(["git", "rev-parse", f"{commit}:pom.xml"], RAIZ).strip()
    obs = run(["git", "rev-parse", f"{commit}:{OBS_REL}"], RAIZ).strip()
    for nome, got, esp in (("src/", tree, TREE_SRC), ("pom.xml", pom, BLOB_POM), ("ObservadorHotStuff.java", obs, BLOB_OBS)):
        if got != esp:
            sys.exit(f"ERRO: {nome} de {commit[:7]} = {got} != pre-registrado {esp}. Rodada NAO autorizada com outro codigo.")
    dest.mkdir(parents=True, exist_ok=True)
    tar = run(["git", "archive", commit, "--", "src", "pom.xml"], RAIZ, binario=True)
    tarfile.open(fileobj=io.BytesIO(tar)).extractall(dest, filter="data")
    run(["mvn", "-q", "-DskipTests", "compile"], dest)
    cp_file = dest / "cp.txt"
    run(["mvn", "-q", "dependency:build-classpath", f"-Dmdep.outputFile={cp_file}"], dest)
    hdir = dest / "harness-classes"
    hdir.mkdir(exist_ok=True)
    fonte = dest / "ObservadorHotStuff.java"
    fonte.write_bytes(run(["git", "show", f"{commit}:{OBS_REL}"], RAIZ, binario=True))
    cp = f"{dest}/target/classes:" + cp_file.read_text().strip()
    run(["javac", "-nowarn", "-cp", cp, "-d", str(hdir), str(fonte)], dest)
    porcelain = run(["git", "status", "--porcelain", "--", "src", "pom.xml"], RAIZ).strip()
    jv = subprocess.run(["java", "-version"], capture_output=True, text=True)
    mv = run(["mvn", "-v"], RAIZ)
    info = {"commit_construido": commit, "tree_src": tree, "blob_pom": pom, "blob_observador": obs,
            "origem": "git archive (arvore de trabalho NAO usada)",
            "mudancas_nao_commitadas_em_src_pom_na_arvore_de_trabalho_(excluidas_do_build)": porcelain.splitlines(),
            "copia_de_build": str(dest), "classes": str(dest / "target" / "classes"), "classpath_arquivo": str(cp_file),
            "observador_classes": str(hdir), "java_version": (jv.stdout + jv.stderr).strip().splitlines()[:3],
            "mvn_v": mv.strip().splitlines()[:2]}
    Path(a.registro).write_text(json.dumps(info, indent=1, ensure_ascii=False))
    print(json.dumps(info, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()

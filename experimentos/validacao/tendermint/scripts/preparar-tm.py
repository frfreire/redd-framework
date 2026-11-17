#!/usr/bin/env python3
"""Prepara o build do simulador para o V-ABC do Tendermint A PARTIR DO GIT (git archive de um COMMIT FIXO), nunca da arvore de trabalho.

RASCUNHO (etapa 1): COMMIT_PINADO = a definir (apos BYZ-UNICA, D2 e PARIDADE estarem commitadas). Enquanto for None, o modo normal RECUSA
rodar. Existe SO o modo `--dry-run-head`, que constroi o HEAD em /tmp/opencode/tm-dry para (i) conferir que ObservadorTm.java COMPILA contra a
API publica, (ii) sortear ids bizantinos (`--listar-byz-faixa`, nao constroi cenario) e (iii) ler codigo. NADA do que sair do dry-run vale como
dado: o dry-run NUNCA e usado para simular celula de portao.

Modo normal (so apos o pino): git rev-parse -> confere tree de src/ e blob de pom.xml pre-registrados -> git archive -> extrai em
/tmp/opencode/val-tm -> mvn compile -> build-classpath (cp.txt) -> javac de harness/ObservadorTm.java (SHA-256 conferido) -> grava
versao-simulador.json. Recusa destino existente e nao vazio.
Uso: preparar-tm.py --dry-run-head [--destino /tmp/opencode/tm-dry]
     preparar-tm.py                 [--destino /tmp/opencode/val-tm] [--registro versao-simulador.json]   (so apos o pino)
"""
import argparse
import hashlib
import io
import json
import subprocess
import sys
import tarfile
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
RAIZ = VAL.parents[2]
# ---- A PREENCHER NO PINO (nao antes): ----
COMMIT_PINADO = None      # a definir (apos BYZ-UNICA, D2 e PARIDADE commitadas); hash completo de 40 hex
TREE_SRC = None           # git rev-parse <COMMIT_PINADO>:src
BLOB_POM = None           # git rev-parse <COMMIT_PINADO>:pom.xml
SHA_OBS_TM = None         # sha256 de harness/ObservadorTm.java NO PINO (o observador pode precisar de ajuste por causa da PARIDADE)
OBS_TM = VAL / "harness" / "ObservadorTm.java"


def run(cmd, cwd=None, binario=False):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=not binario)
    if r.returncode != 0:
        err = r.stderr if not binario else r.stderr.decode(errors="replace")
        sys.exit(f"ERRO: {' '.join(cmd)}\n{err[-3000:]}")
    return r.stdout


def construir(commit, dest, dry):
    dest = Path(dest)
    if dest.exists() and any(dest.iterdir()):
        sys.exit(f"ERRO: {dest} ja existe e nao esta vazio (nao sobrescrevo)")
    dest.mkdir(parents=True, exist_ok=True)
    tree = run(["git", "rev-parse", f"{commit}:src"], RAIZ).strip()
    pom = run(["git", "rev-parse", f"{commit}:pom.xml"], RAIZ).strip()
    if not dry:
        for nome, got, esp in (("src/", tree, TREE_SRC), ("pom.xml", pom, BLOB_POM)):
            if got != esp:
                sys.exit(f"ERRO: {nome} de {commit[:7]} = {got} != pre-registrado {esp}. Rodada NAO autorizada com outro codigo.")
        if hashlib.sha256(OBS_TM.read_bytes()).hexdigest() != SHA_OBS_TM:
            sys.exit(f"ERRO: ObservadorTm.java diverge do pre-registrado ({SHA_OBS_TM})")
    tar = run(["git", "archive", commit, "--", "src", "pom.xml"], RAIZ, binario=True)
    tarfile.open(fileobj=io.BytesIO(tar)).extractall(dest, filter="data")
    run(["mvn", "-q", "-DskipTests", "compile"], dest)
    cp_file = dest / "cp.txt"
    run(["mvn", "-q", "dependency:build-classpath", f"-Dmdep.outputFile={cp_file}"], dest)
    hdir = dest / "harness-classes"
    hdir.mkdir(exist_ok=True)
    fonte = dest / "ObservadorTm.java"
    fonte.write_bytes(OBS_TM.read_bytes())
    cp = f"{dest}/target/classes:" + cp_file.read_text().strip()
    run(["javac", "-nowarn", "-cp", cp, "-d", str(hdir), str(fonte)], dest)
    jv = subprocess.run(["java", "-version"], capture_output=True, text=True)
    mv = run(["mvn", "-v"], RAIZ)
    info = {"modo": "DRY-RUN (nao e dado; nao e o commit pinado)" if dry else "PINADO",
            "commit_construido": run(["git", "rev-parse", commit], RAIZ).strip(),
            "head_no_momento": run(["git", "rev-parse", "HEAD"], RAIZ).strip(), "tree_src": tree, "blob_pom": pom,
            "sha256_ObservadorTm_compilado": hashlib.sha256(OBS_TM.read_bytes()).hexdigest(),
            "origem": "git archive (arvore de trabalho NAO usada)",
            "mudancas_nao_commitadas_em_src_pom_na_arvore_de_trabalho_(excluidas_do_build)":
                run(["git", "status", "--porcelain", "--", "src", "pom.xml"], RAIZ).strip().splitlines(),
            "copia_de_build": str(dest), "classes": str(dest / "target" / "classes"), "classpath_arquivo": str(cp_file),
            "observador_classes": str(hdir), "java_version": (jv.stdout + jv.stderr).strip().splitlines()[:3],
            "mvn_v": mv.strip().splitlines()[:2]}
    return info


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run-head", action="store_true")
    ap.add_argument("--destino", default=None)
    ap.add_argument("--registro", default=str(VAL / "versao-simulador.json"))
    a = ap.parse_args()
    if a.dry_run_head:
        dest = a.destino or "/tmp/opencode/tm-dry"
        info = construir("HEAD", dest, True)
        (Path(dest) / "DRYRUN.json").write_text(json.dumps(info, indent=1, ensure_ascii=False))
        print(json.dumps(info, indent=1, ensure_ascii=False))
        return
    if COMMIT_PINADO is None:
        sys.exit("RECUSADO: COMMIT_PINADO = a definir (apos BYZ-UNICA, D2 e PARIDADE estarem commitadas). Use --dry-run-head so para dry-run.")
    info = construir(COMMIT_PINADO, a.destino or "/tmp/opencode/val-tm", False)
    Path(a.registro).write_text(json.dumps(info, indent=1, ensure_ascii=False))
    print(json.dumps(info, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()

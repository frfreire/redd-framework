#!/usr/bin/env python3
"""Prepara o build do simulador para o V-ABC do PBFT puro A PARTIR DO GIT (git archive de um COMMIT FIXO), nunca da arvore de trabalho.

RASCUNHO: COMMIT_PINADO = o MESMO do V-ABC-tendermint (2980d269). Se COMMIT_PINADO voltar a ser None, o modo normal RECUSA
rodar. Existe SO o modo `--dry-run-head`, que constroi o HEAD em /tmp/opencode/pbft-dry para (i) conferir que ObservadorPbft.java COMPILA contra a
API publica, (ii) sortear ids bizantinos (`--listar-byz-faixa`, nao constroi cenario) e (iii) ler codigo. NADA do que sair do dry-run vale como
dado: o dry-run NUNCA e usado para simular celula de portao.

Modo normal (so apos o pino): git rev-parse -> confere tree de src/ e blob de pom.xml pre-registrados -> git archive -> extrai em
/tmp/opencode/val-pbft -> mvn compile -> build-classpath (cp.txt) -> javac de harness/ObservadorPbft.java (SHA-256 conferido) -> grava
versao-simulador.json. Recusa destino existente e nao vazio.
Uso: preparar-pbft.py --dry-run-head [--destino /tmp/opencode/pbft-dry]
     preparar-pbft.py                 [--destino /tmp/opencode/val-pbft] [--registro versao-simulador.json]   (so apos o pino)
"""
import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
MVN = shutil.which("mvn.cmd") or shutil.which("mvn") or "mvn"  # Windows: mvn.cmd (subprocess sem shell nao resolve "mvn")
RAIZ = VAL.parents[2]
# ---- A PREENCHER NO PINO (nao antes): ----
COMMIT_PINADO = "2980d2695436ff4efea0138275aaf04a796ff6ff"  # pino de 22/11/2026: main apos os PRs #7, #10 e #11 (e o merge #1 do dependabot em chaincode/, fora de src/)
TREE_SRC = "326bf654091f16afa49323d2e88956fa4c12bf15"  # git rev-parse 2980d269:src
BLOB_POM = "32f934e332097d6bfed46830ff03132c36763670"  # git rev-parse 2980d269:pom.xml
SHA_OBS_PBFT = "d0638ff3dcdf908e7a9750be6993754f0800e76d6cc967757633b5fc8ee7737c"  # sha256 de ObservadorPbft.java com fim de linha LF (= o blob do git)
OBS_PBFT = VAL / "harness" / "ObservadorPbft.java"


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
        if hashlib.sha256(OBS_PBFT.read_bytes().replace(b"\r\n", b"\n")).hexdigest() != SHA_OBS_PBFT:  # LF: independe de core.autocrlf
            sys.exit(f"ERRO: ObservadorPbft.java diverge do pre-registrado ({SHA_OBS_PBFT})")
    tar = run(["git", "archive", commit, "--", "src", "pom.xml"], RAIZ, binario=True)
    tarfile.open(fileobj=io.BytesIO(tar)).extractall(dest, filter="data")
    run([MVN, "-q", "-DskipTests", "compile"], dest)
    cp_file = dest / "cp.txt"
    run([MVN, "-q", "dependency:build-classpath", f"-Dmdep.outputFile={cp_file}"], dest)
    hdir = dest / "harness-classes"
    hdir.mkdir(exist_ok=True)
    fonte = dest / "ObservadorPbft.java"
    fonte.write_bytes(OBS_PBFT.read_bytes())
    cp = f"{dest}/target/classes{os.pathsep}" + cp_file.read_text().strip()
    run(["javac", "-nowarn", "-cp", cp, "-d", str(hdir), str(fonte)], dest)
    jv = subprocess.run(["java", "-version"], capture_output=True, text=True)
    mv = run([MVN, "-v"], RAIZ)
    info = {"modo": "DRY-RUN (nao e dado; nao e o commit pinado)" if dry else "PINADO",
            "commit_construido": run(["git", "rev-parse", commit], RAIZ).strip(),
            "head_no_momento": run(["git", "rev-parse", "HEAD"], RAIZ).strip(), "tree_src": tree, "blob_pom": pom,
            "sha256_ObservadorPbft_compilado": hashlib.sha256(OBS_PBFT.read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
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
        dest = a.destino or "/tmp/opencode/pbft-dry"
        info = construir("HEAD", dest, True)
        (Path(dest) / "DRYRUN.json").write_text(json.dumps(info, indent=1, ensure_ascii=False))
        print(json.dumps(info, indent=1, ensure_ascii=False))
        return
    if COMMIT_PINADO is None:
        sys.exit("RECUSADO: COMMIT_PINADO nao definido. Use --dry-run-head so para dry-run.")
    info = construir(COMMIT_PINADO, a.destino or "/tmp/opencode/val-pbft", False)
    Path(a.registro).write_text(json.dumps(info, indent=1, ensure_ascii=False))
    print(json.dumps(info, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()

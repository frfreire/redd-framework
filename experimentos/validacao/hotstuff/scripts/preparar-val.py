#!/usr/bin/env python3
"""Prepara o build do simulador para V-A/V-C A PARTIR DO GIT (git archive de um COMMIT FIXO), nunca da arvore de trabalho.
EMENDA 1b (16/11/2026, antes dos dados): commit fixo passou de a65bf83 para 76932b9 (HEAD; inclui --allow-beyond-bft-threshold; dentro da
faixa a saida e byte a byte a de a65bf83). O observador compilado e harness/ObservadorVal.java (SHA-256 conferido), nao o do piloto.

O commit e FIXO no pre-registro (nao e HEAD): uma tarefa paralela do jabs-builder (BYZ-fora-da-faixa-hotstuff) vai mudar
src/ e pode ser commitada antes da etapa 2; V-A/V-C nao dependem dela. Aborta se tree de src/, blob do pom.xml ou blob do
observador divergirem do pre-registrado (nao ha fallback silencioso). Recusa destino ja existente e nao vazio.
Passos: git rev-parse -> confere tree/blob -> git archive -> extrai em /tmp/opencode/val-hs -> mvn compile ->
build-classpath (cp.txt) -> javac do observador (blob do commit) -> grava versao-simulador.json.
Uso: preparar-val.py [--commit 76932b92...] [--destino /tmp/opencode/val-hs] [--registro arq.json]
"""
import argparse
import io
import json
import subprocess
import sys
import tarfile
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
RAIZ = VAL.parents[2]
COMMIT_FIXO = "76932b925719a2b83d6165b5a84d30f5501896ee"
TREE_SRC = "a1b464ee983b78a2a6b6de7166a6e6460839b8af"   # git rev-parse 76932b9:src
BLOB_POM = "32f934e332097d6bfed46830ff03132c36763670"
BLOB_OBS = "4024e15b53b8939458c22ae9d04f107b422e3cd0"   # ObservadorHotStuff.java do piloto no commit (so conferencia; NAO e o compilado)
OBS_REL = "experimentos/piloto-timeout-hotstuff/harness/ObservadorHotStuff.java"
OBS_VAL = VAL / "harness" / "ObservadorVal.java"     # EMENDA 1b: o compilado
SHA_OBS_VAL = "21aa3f9e7669765ba32ff91f4cadd8e7b28c364d8989a27d6a850c7e9d61e089"


def run(cmd, cwd=None, binario=False):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=not binario)
    if r.returncode != 0:
        err = r.stderr if not binario else r.stderr.decode(errors="replace")
        sys.exit(f"ERRO: {' '.join(cmd)}\n{err[-3000:]}")
    return r.stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--commit", default=COMMIT_FIXO)
    ap.add_argument("--destino", default="/tmp/opencode/val-hs")
    ap.add_argument("--registro", default=str(VAL / "versao-simulador.json"))
    a = ap.parse_args()
    if a.commit != COMMIT_FIXO:
        sys.exit(f"ERRO: commit {a.commit} != commit fixo pre-registrado {COMMIT_FIXO}")
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
    import hashlib
    dados_obs = OBS_VAL.read_bytes()
    if hashlib.sha256(dados_obs).hexdigest() != SHA_OBS_VAL:
        sys.exit(f"ERRO: ObservadorVal.java diverge do pre-registrado ({SHA_OBS_VAL})")
    fonte = dest / "ObservadorVal.java"
    fonte.write_bytes(dados_obs)
    cp = f"{dest}/target/classes:" + cp_file.read_text().strip()
    run(["javac", "-nowarn", "-cp", cp, "-d", str(hdir), str(fonte)], dest)
    porcelain = run(["git", "status", "--porcelain", "--", "src", "pom.xml"], RAIZ).strip()
    jv = subprocess.run(["java", "-version"], capture_output=True, text=True)
    mv = run(["mvn", "-v"], RAIZ)
    head = run(["git", "rev-parse", "HEAD"], RAIZ).strip()
    info = {"commit_construido": commit, "head_no_momento": head, "tree_src": tree, "blob_pom": pom,
            "blob_observador_piloto_conferido": obs, "sha256_observador_compilado_ObservadorVal": SHA_OBS_VAL, "origem": "git archive (arvore de trabalho NAO usada)",
            "mudancas_nao_commitadas_em_src_pom_na_arvore_de_trabalho_(excluidas_do_build)": porcelain.splitlines(),
            "copia_de_build": str(dest), "classes": str(dest / "target" / "classes"), "classpath_arquivo": str(cp_file),
            "observador_classes": str(hdir), "java_version": (jv.stdout + jv.stderr).strip().splitlines()[:3],
            "mvn_v": mv.strip().splitlines()[:2]}
    Path(a.registro).write_text(json.dumps(info, indent=1, ensure_ascii=False))
    print(json.dumps(info, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()

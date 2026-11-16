#!/usr/bin/env python3
"""E7 (V-B liberado): confere, SO LEITURA e sem simular, que o build em /tmp/opencode/val-hs (usado na etapa 2 de V-A/V-C) e
exatamente o commit 76932b925719a2b83d6165b5a84d30f5501896ee: (1) cada arquivo de src/ e o pom.xml da copia de build tem o mesmo blob git
(`git hash-object`) que o do commit; (2) nao ha arquivo a mais ou a menos; (3) versao-simulador.json registra o commit/tree/pom pinados;
(4) ObservadorVal.java compilado tem o SHA-256 pinado e as classes existem; (5) o HEAD de trabalho ainda tem a mesma tree de src/.
Motivo: preparar-val.py RECUSA destino nao vazio, entao V-B reaproveita o build; esta conferencia substitui o rebuild e prova que e o mesmo.
Saida: conferencia-build-e7.json. Codigo de saida 0 = ok.
"""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
RAIZ = VAL.parents[2]
COMMIT = "76932b925719a2b83d6165b5a84d30f5501896ee"
TREE_SRC = "a1b464ee983b78a2a6b6de7166a6e6460839b8af"
BLOB_POM = "32f934e332097d6bfed46830ff03132c36763670"
SHA_OBS = "21aa3f9e7669765ba32ff91f4cadd8e7b28c364d8989a27d6a850c7e9d61e089"
BUILD = Path("/tmp/opencode/val-hs")


def git(*a, binario=False):
    r = subprocess.run(["git", *a], cwd=RAIZ, capture_output=True)
    if r.returncode != 0:
        sys.exit(f"git {' '.join(a)} falhou: {r.stderr.decode(errors='replace')[-300:]}")
    return r.stdout if binario else r.stdout.decode().strip()


def hash_obj(p):
    return subprocess.run(["git", "hash-object", str(p)], capture_output=True, text=True, check=True).stdout.strip()


def main():
    problemas = []
    tree_commit = git("rev-parse", f"{COMMIT}:src")
    if tree_commit != TREE_SRC:
        problemas.append(f"tree de src/ do commit {tree_commit} != pinada {TREE_SRC}")
    if git("rev-parse", f"{COMMIT}:pom.xml") != BLOB_POM:
        problemas.append("blob do pom.xml do commit diverge do pinado")
    # arquivos de src/ do commit (blob -> caminho)
    no_commit = {}
    for linha in git("ls-tree", "-r", COMMIT, "--", "src").splitlines():
        meta, caminho = linha.split("\t", 1)
        no_commit[caminho] = meta.split()[2]
    no_build = {}
    for p in sorted((BUILD / "src").rglob("*")):
        if p.is_file():
            no_build[str(p.relative_to(BUILD))] = hash_obj(p)
    faltam = sorted(set(no_commit) - set(no_build))
    sobram = sorted(set(no_build) - set(no_commit))
    difere = sorted(c for c in set(no_commit) & set(no_build) if no_commit[c] != no_build[c])
    if faltam or sobram or difere:
        problemas.append({"faltam_no_build": faltam[:5], "sobram_no_build": sobram[:5], "difere": difere[:5]})
    if hash_obj(BUILD / "pom.xml") != BLOB_POM:
        problemas.append("pom.xml do build diverge")
    obs = BUILD / "ObservadorVal.java"
    if not obs.exists() or hashlib.sha256(obs.read_bytes()).hexdigest() != SHA_OBS:
        problemas.append("ObservadorVal.java do build diverge do SHA-256 pinado")
    for c in ("ObservadorVal.class", "ObservadorVal$Observador.class"):
        if not (BUILD / "harness-classes" / c).exists():
            problemas.append(f"falta harness-classes/{c}")
    for c in ("jabs/example/MVPComparison.class", "jabs/consensus/algorithm/HotStuff.class"):
        if not (BUILD / "target" / "classes" / c).exists():
            problemas.append(f"falta target/classes/{c}")
    reg = json.loads((VAL / "versao-simulador.json").read_text())
    if (reg.get("commit_construido"), reg.get("tree_src"), reg.get("blob_pom")) != (COMMIT, TREE_SRC, BLOB_POM):
        problemas.append("versao-simulador.json nao registra o commit/tree/pom pinados")
    head_tree = git("rev-parse", "HEAD:src")
    out = {"commit_pinado": COMMIT, "arquivos_src_no_commit": len(no_commit), "arquivos_src_no_build": len(no_build),
           "tree_src_do_commit": tree_commit, "tree_src_no_HEAD_de_trabalho": head_tree,
           "HEAD_de_trabalho_tem_a_mesma_tree_de_src": head_tree == TREE_SRC,
           "classes_compiladas_conferidas_por": "existencia (o .class nao tem blob de origem; a prova e a fonte identica + o build ter sido feito por preparar-val.py)",
           "problemas": problemas}
    (VAL / "conferencia-build-e7.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))
    print(json.dumps(out, indent=1, ensure_ascii=False))
    sys.exit(1 if problemas else 0)


if __name__ == "__main__":
    main()

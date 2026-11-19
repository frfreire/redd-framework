#!/usr/bin/env python3
r"""Evidencia da tarefa HASHCODE-hotstuff-tx-por-valor: tempo de parede de UMA celula HotStuff com e sem
-XX:+UnlockExperimentalVMOptions -XX:hashCode=2, e MD5 dos CSV (por bloco sem colunas *hash* e agregado).

Uso:
  medir-hashcode-hotstuff.py --classes <dir de classes> --cp-file target/cp.txt \
      [--n 4] [--duracao 30] [--batch 500] [--semente 16346] [--saida-base /tmp/opencode/hs-hash] \
      [--rotulo antes] [--amostrar-pilha]
  medir-hashcode-hotstuff.py --copiar-classes target/classes --para /tmp/opencode/hs-hash/antes

`--amostrar-pilha` (so na execucao COM a flag) tira N amostras de `jstack` do processo e imprime o frame de topo
da thread `main` mais frequente (perfil simples para confirmar onde o tempo vai).
Os arquivos de saida vao para <saida-base>/<rotulo>-<com|sem>-<timestamp>/ (nunca sobrescreve nem apaga).
A celula e a do piloto (RELATORIO secao 9.2): ROTATING, INDIVIDUAL, BITMAP, timeout 60, tx 512 B, 1 trial.
"""
import argparse
import collections
import csv
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

FLAG_HASH = ["-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2"]


def md5_csv_sem_hash(path):
    """MD5 do CSV sem comentarios (#) e sem colunas cujo nome contem 'hash' (maiusc/minusc)."""
    with open(path, newline="", encoding="utf-8") as f:
        linhas = [l for l in f.read().splitlines() if l and not l.startswith("#")]
    rd = list(csv.reader(io.StringIO("\n".join(linhas))))
    if not rd:
        return None, 0
    manter = [i for i, c in enumerate(rd[0]) if "hash" not in c.lower()]
    h = hashlib.md5()
    for row in rd:
        h.update((",".join(row[i] for i in manter if i < len(row)) + "\n").encode())
    return h.hexdigest(), len(rd) - 1


def rodar(args, com_flag, rotulo):
    with open(args.cp_file, encoding="utf-8") as fcp:
        cp = fcp.read().strip()
    saida = os.path.join(args.saida_base, "%s-%s-%d" % (rotulo, "com" if com_flag else "sem", int(time.time() * 1000)))
    os.makedirs(saida)
    base = ["--protocols=" + args.protocolo, "--validators=%d" % args.n, "--trials=1", "--duration=%d" % args.duracao,
            "--seed=%d" % args.semente, "--output=" + saida, "--csv-out=" + saida + "/mvp-results.csv"]
    if args.protocolo == "HOTSTUFF":
        base += ["--hs-view-timeout=60", "--hs-max-future-view-horizon=1000", "--hs-batch-size=%d" % args.batch,
                 "--hs-tx-size=512", "--hs-individual-signature-size=64", "--hs-aggregated-signature-size=96",
                 "--hs-aggregated-share-size=96", "--hs-message-overhead=10", "--hs-signature-mode=INDIVIDUAL",
                 "--hs-leader-policy=ROTATING", "--hs-voter-encoding=BITMAP"]
    cmd = ["java", "-Xmx2g"] + (FLAG_HASH if com_flag else []) + [
        "-cp", args.classes + os.pathsep + cp, "jabs.example.MVPComparison"] + base
    if args.extra:
        cmd += args.extra
    t0 = time.time()
    p = subprocess.Popen(cmd, stdout=open(saida + "/stdout.txt", "w", encoding="utf-8"), stderr=subprocess.STDOUT)
    topos = collections.Counter()
    primeiro_jabs = collections.Counter()
    if args.amostrar_pilha and com_flag:
        time.sleep(3)
        while p.poll() is None:
            r = subprocess.run(["jstack", str(p.pid)], capture_output=True, text=True)
            m = re.search(r'"main".*?\n((?:\s+.*\n)+)', r.stdout)
            if m:
                fr = [l.strip() for l in m.group(1).splitlines() if l.strip().startswith("at ")]
                # colapsa frames repetidos (TreeNode.find recursivo) e mostra os 6 primeiros distintos
                vistos = []
                for l in fr:
                    nome = re.sub(r"\(.*\)", "", l[3:])
                    if not vistos or vistos[-1] != nome:
                        vistos.append(nome)
                topos[" <- ".join(vistos[:6])] += 1
                app = [v for v in vistos if v.startswith("jabs.")]
                primeiro_jabs[" <- ".join(app[:2])] += 1
            time.sleep(1.5)
    rc = p.wait()
    dt = time.time() - t0
    res = {"rc": rc, "parede_s": dt, "saida": saida, "cmd": " ".join(cmd)}
    for nome in sorted(os.listdir(saida)):
        if nome.endswith(".csv") or nome == "mvp-results.csv":
            pass
    csvs = []
    for raiz, _, arqs in os.walk(saida):
        for a in arqs:
            if a.endswith(".csv"):
                csvs.append(os.path.join(raiz, a))
    res["md5"] = {os.path.relpath(c, saida): md5_csv_sem_hash(c) for c in sorted(csvs)}
    res["topos"] = topos
    res["primeiro_jabs"] = primeiro_jabs
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--classes")
    ap.add_argument("--cp-file", default="target/cp.txt")
    ap.add_argument("--protocolo", default="HOTSTUFF")
    ap.add_argument("--n", type=int, default=4)
    ap.add_argument("--duracao", type=int, default=30)
    ap.add_argument("--batch", type=int, default=500)
    ap.add_argument("--semente", type=int, default=16346)
    ap.add_argument("--saida-base", default=os.path.join(tempfile.gettempdir(), "opencode", "hs-hash"))
    ap.add_argument("--rotulo", default="x")
    ap.add_argument("--amostrar-pilha", action="store_true")
    ap.add_argument("--so", choices=["com", "sem"])
    ap.add_argument("--extra", action="append")
    ap.add_argument("--copiar-classes")
    ap.add_argument("--para")
    args = ap.parse_args()
    if args.copiar_classes:
        shutil.copytree(args.copiar_classes, args.para)
        print("copiado", args.copiar_classes, "->", args.para)
        return 0
    if not args.classes:
        ap.error("--classes obrigatorio")
    os.makedirs(args.saida_base, exist_ok=True)
    out = {}
    for com in ([False, True] if not args.so else [args.so == "com"]):
        r = rodar(args, com, args.rotulo)
        out[com] = r
        print("[%s] flag hashCode=2: %-3s parede=%.2f s rc=%d" % (args.rotulo, "SIM" if com else "NAO", r["parede_s"], r["rc"]))
        for k, v in r["md5"].items():
            print("   md5(sem col hash) %-60s linhas=%s %s" % (k, v[1], v[0]))
        tot = sum(r["primeiro_jabs"].values())
        for k, c in r["primeiro_jabs"].most_common(8):
            print("   amostras %d/%d: primeiros frames jabs.*: %s" % (c, tot, k))
    if len(out) == 2:
        a, b = out[False], out[True]
        print("razao com/sem = %.1fx" % (b["parede_s"] / a["parede_s"]))
        ia, ib = a["md5"], b["md5"]
        print("CSV identicos com vs sem flag: %s" % (ia == ib))
    return 0


if __name__ == "__main__":
    sys.exit(main())

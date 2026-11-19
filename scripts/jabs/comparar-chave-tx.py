#!/usr/bin/env python3
r"""Evidencia de LOGGER-chave-txid-sequencial: saida ANTES x DEPOIS da troca da chave de transacao do logger.

Roda o jabs.example.MVPComparison com dois diretorios de classes (base = HEAD sem a correcao; atual = com ela), COM e SEM
-XX:+UnlockExperimentalVMOptions -XX:hashCode=2, para cada caso PROTO:N:DURACAO[:flags-do-protocolo], e compara TODOS os CSV
que a corrida grava (por bloco/logger, agregado, Cb), ignorando linhas '#' e colunas cujo nome contem 'hash' (como
checar-determinismo.py), mais o numero de linhas "Double-spend detected" do stdout. Imprime tempo de parede por execucao.

Uso:
  construir-base-head.py                     # gera /tmp/opencode/loggerchave-head (git archive do HEAD + COMMIT)
  comparar-chave-tx.py --base /tmp/opencode/loggerchave-head/target/classes --commit $(git rev-parse HEAD) \
      --atual target/classes --cp-file target/cp.txt [--semente 16346] [--saida /tmp/opencode/chave-tx] [--paralelo 4] [--casos ...]
O --base precisa ser build do HEAD ATUAL: o script le o arquivo COMMIT gravado por construir-base-head.py (no diretorio das
classes ou acima), exige COMMIT == --commit == `git rev-parse HEAD` e sai com 2 se nao for. Imprime o commit da base e do atual
(HEAD + estado da arvore de trabalho em src/ e pom.xml).
Criterio (ADR-0016): COM a flag toda celula tem de ser IDENTICA (base x atual). SEM a flag so os protocolos de SEM_FLAG_LIVRE
(Ethereum: AMBIFY, MCO2, OFFSET) podem diferir; diferenca sem flag em outro protocolo = falha. A base e rodada duas vezes
(base, base2) como controle: mostra se o HEAD sem flag ja diverge entre execucoes.
Saida: 0 = criterio cumprido; 1 = violacao do criterio; 2 = erro de execucao ou base que nao e o HEAD. Nunca apaga nem sobrescreve.
Os valores --hs-*/--tm-* sao FIXTURE (a celula de referencia do piloto e a do HASHCODE-hotstuff-tx-por-valor), nao calibracao.
"""
import argparse
import csv
import hashlib
import io
import os
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# protocolos cuja saida SEM a flag pode mudar (ADR-0016): dependem da sequencia de identityHashCode da thread principal
SEM_FLAG_LIVRE = {"AMBIFY", "MCO2", "OFFSET"}
FLAG_HASH = ["-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2"]

HS_REF = ["--hs-view-timeout=60", "--hs-max-future-view-horizon=1000", "--hs-batch-size=500", "--hs-tx-size=512",
          "--hs-individual-signature-size=64", "--hs-aggregated-signature-size=96", "--hs-aggregated-share-size=96",
          "--hs-message-overhead=10", "--hs-signature-mode=INDIVIDUAL", "--hs-leader-policy=ROTATING",
          "--hs-voter-encoding=BITMAP"]
HS_PEQ = [f if not f.startswith("--hs-batch-size") else "--hs-batch-size=20" for f in HS_REF]
TM = ["--tm-propose-timeout=1.0", "--tm-propose-delta=0.5", "--tm-prevote-timeout=1.0", "--tm-prevote-delta=0.5",
      "--tm-precommit-timeout=1.0", "--tm-precommit-delta=0.5", "--tm-batch-size=20", "--tm-tx-size=512",
      "--tm-signature-size=64", "--tm-proposer-signature-size=80", "--tm-message-overhead=10"]

# nome, protocolo, n, duracao_s, flags
CASOS = [
    ("HS-ref", "HOTSTUFF", 4, 30, HS_REF),
    ("HS-n7", "HOTSTUFF", 7, 30, HS_PEQ),
    ("TM-n4", "TENDERMINT", 4, 30, TM),
    ("ARQ-n7", "ARQ", 7, 60, []),
    ("MCO2-n10", "MCO2", 10, 60, []),
    ("AMBIFY-n10", "AMBIFY", 10, 60, []),
    ("TREE-n10", "TREE", 10, 60, []),
    ("EARTH-n10", "EARTH", 10, 60, []),
    ("DAGSPER-n10", "DAGSPER", 10, 60, []),
    # dependentes da sequencia de identityHashCode (ADR-0012/0013): so valem como "identico" sob -XX:hashCode=2
    ("AMBIFY-n25", "AMBIFY", 25, 300, []),
    ("MCO2-n50", "MCO2", 50, 600, []),
    ("OFFSET-n40", "OFFSET", 40, 20000, []),
]


def git(*args):
    return subprocess.run(["git"] + list(args), capture_output=True, text=True).stdout.strip()


def commit_da_base(base):
    """Le o arquivo COMMIT (gravado por construir-base-head.py) no diretorio de classes ou em ate 3 niveis acima."""
    d = Path(base).resolve()
    for _ in range(4):
        f = d / "COMMIT"
        if f.is_file():
            return f.read_text(encoding="utf-8").strip()
        d = d.parent
    return None


def main_commit_check(a):
    head = git("rev-parse", "HEAD")
    esperado = git("rev-parse", "--verify", a.commit + "^{commit}") if a.commit else head
    base = commit_da_base(a.base)
    sujo = git("status", "--porcelain", "--", "src", "pom.xml")
    diff = subprocess.run(["git", "diff", "HEAD", "--", "src", "pom.xml"], capture_output=True).stdout
    untracked = git("ls-files", "--others", "--exclude-standard", "--", "src", "pom.xml")
    h = hashlib.sha256(diff + untracked.encode()).hexdigest()[:12]
    print("HEAD (git rev-parse HEAD)      :", head)
    print("--commit esperado              :", esperado)
    print("commit da BASE (arquivo COMMIT):", base)
    print("ATUAL                          : HEAD %s + arvore de trabalho (%s; sha256 do diff em src/pom.xml: %s)" % (
        head[:7], "src/pom.xml modificados" if sujo else "src/pom.xml sem modificacao", h))
    if base is None:
        print("ERRO: a base nao tem arquivo COMMIT (use scripts/jabs/construir-base-head.py)", file=sys.stderr)
        return False
    if not (base == esperado == head):
        print("ERRO: a base nao e o HEAD atual (base=%s, --commit=%s, HEAD=%s)" % (base[:7], esperado[:7], head[:7]),
              file=sys.stderr)
        return False
    return True


def md5_csv(path):
    """(md5, linhas) do CSV sem comentarios (#) e sem colunas com 'hash' no nome."""
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


def executar(job):
    rotulo_classes, classes, cp, caso, com_flag, semente, raiz = job
    nome, proto, n, dur, flags = caso
    saida = Path(raiz) / ("%s-%s-%s" % (rotulo_classes, nome, "com" if com_flag else "sem"))
    saida.mkdir(parents=True, exist_ok=False)
    cmd = ["java", "-Xmx2g"] + (FLAG_HASH if com_flag else []) + [
        "-cp", classes + os.pathsep + cp, "jabs.example.MVPComparison", "--protocols=" + proto,
        "--validators=%d" % n, "--trials=1", "--duration=%d" % dur, "--seed=%d" % semente, "--output=" + str(saida),
        "--csv-out=" + str(saida / "mvp-results.csv")] + flags
    t0 = time.time()
    with open(saida / "stdout.txt", "w", encoding="utf-8") as so:
        rc = subprocess.run(cmd, stdout=so, stderr=subprocess.STDOUT).returncode
    dt = time.time() - t0
    md5 = {}
    for p in sorted(saida.rglob("*.csv")):
        md5[str(p.relative_to(saida))] = md5_csv(p)
    ds = sum(1 for l in (saida / "stdout.txt").read_text(encoding="utf-8", errors="replace").splitlines() if "Double-spend detected" in l)
    return (rotulo_classes, nome, com_flag), {"rc": rc, "parede": dt, "md5": md5, "ds": ds}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="diretorio de classes SEM a correcao (copia do HEAD)")
    ap.add_argument("--commit", default="", help="hash esperado da base; padrao e o HEAD. A base tem de ser o HEAD")
    ap.add_argument("--atual", required=True, help="diretorio de classes COM a correcao")
    ap.add_argument("--cp-file", default="target/cp.txt")
    ap.add_argument("--semente", type=int, default=16346)
    ap.add_argument("--saida", default=str(Path(tempfile.gettempdir()) / "opencode" / "chave-tx"))
    ap.add_argument("--paralelo", type=int, default=3)
    ap.add_argument("--casos", default="", help="lista de nomes separada por virgula (padrao: todos)")
    a = ap.parse_args()
    if not main_commit_check(a):
        return 2
    with open(a.cp_file, encoding="utf-8") as fcp:
        cp = fcp.read().strip()
    raiz = Path(a.saida) / str(int(time.time()))
    raiz.mkdir(parents=True)
    casos = [c for c in CASOS if not a.casos or c[0] in a.casos.split(",")]
    jobs = [(rot, cls, cp, c, flag, a.semente, str(raiz))
            for c in casos for flag in (False, True) for rot, cls in (("base", a.base), ("atual", a.atual), ("base2", a.base))]
    res = {}
    with ThreadPoolExecutor(max_workers=a.paralelo) as ex:
        for k, v in ex.map(executar, jobs):
            res[k] = v
    ok = True
    erro = False
    print("saida em", raiz)
    print("%-12s %-5s | %-17s | %-17s | %s" % ("caso", "flag", "parede base (s)", "parede atual (s)", "CSV/DS base==atual | base==base2 | veredito"))
    for c in casos:
        livre = c[1] in SEM_FLAG_LIVRE
        for flag in (False, True):
            b, t = res[("base", c[0], flag)], res[("atual", c[0], flag)]
            b2 = res[("base2", c[0], flag)]
            execok = b["rc"] == 0 and t["rc"] == 0 and b2["rc"] == 0 and bool(t["md5"])
            erro = erro or not execok
            igual = b["md5"] == t["md5"] and b["ds"] == t["ds"] and execok
            base_estavel = b["md5"] == b2["md5"] and b["ds"] == b2["ds"]
            if flag:
                veredito = "OK (identico)" if igual else "FALHA: diferente COM a flag"
                aceito = igual
            elif igual:
                veredito = "OK (identico)"
                aceito = True
            elif livre:
                veredito = "diferente (permitido: Ethereum, ADR-0016)"
                aceito = True
            else:
                veredito = "FALHA: diferente SEM a flag fora de Ethereum"
                aceito = False
            ok = ok and aceito
            print("%-12s %-5s | %8.2f rc=%-3d    | %8.2f rc=%-3d    | %-9s | base==base2: %-5s | %s  (csv=%d, linhas=%s, double-spend=%d/%d)" % (
                c[0], "com" if flag else "sem", b["parede"], b["rc"], t["parede"], t["rc"],
                "IDENTICO" if igual else "DIFERENTE", base_estavel, veredito, len(t["md5"]),
                ",".join(str(v[1]) for v in t["md5"].values()), b["ds"], t["ds"]))
            if not igual:
                for k in sorted(set(b["md5"]) | set(t["md5"])):
                    if b["md5"].get(k) != t["md5"].get(k):
                        print("     diff", k, b["md5"].get(k), "!=", t["md5"].get(k))
            if not base_estavel:
                for k in sorted(set(b["md5"]) | set(b2["md5"])):
                    if b["md5"].get(k) != b2["md5"].get(k):
                        print("     base x base2 (HEAD contra si mesmo)", k, b["md5"].get(k), "!=", b2["md5"].get(k))
        s, c2 = res[("atual", c[0], False)], res[("atual", c[0], True)]
        print("%-12s atual sem-flag == atual com-flag: %s | base sem-flag == base com-flag: %s" % (
            c[0], s["md5"] == c2["md5"], res[("base", c[0], False)]["md5"] == res[("base", c[0], True)]["md5"]))
    print("RESULTADO:", "criterio cumprido" if ok and not erro else "VIOLACAO/ERRO")
    return 2 if erro else (0 if ok else 1)


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Diagnostico (nao e teste): quantos identityHashCode da thread principal o EnhancedBlockFinalizationLogger consome por
BlockFinalizationEvent, em duas versoes do logger (HEAD x arvore atual). Serve para explicar por que a saida de protocolos
cujo comportamento depende da SEQUENCIA de System.identityHashCode (Casper/Parlia/Nakamoto/MCO2/AMBIFY: ADR-0012/0013, N1)
muda SEM -XX:hashCode=2 quando o logger passa a consumir um numero diferente de hashes.

Uso (raiz do repo, depois de mvn test-compile e target/cp.txt):
  diag-sequencia-hash.py [--head-logger <arquivo .java>] [--protocolo AMBIFY --n 10 --dur 60 --semente 16346]
  (sem --head-logger usa `git show HEAD:src/main/java/jabs/log/EnhancedBlockFinalizationLogger.java`)
Copia src/main/java para <TMP>/opencode/chave-tx/diag-<variante>/src, injeta 4 sondas (antes/depois de extrair txs, depois do laco,
no return) que imprimem identityHashCode(new Object()) em stderr, compila com javac e roda MVPComparison. Converte cada valor em
POSICAO na sequencia de referencia (SondaHash ref) e imprime o delta entre sondas consecutivas. Nao toca em src/.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

RAIZ = Path.cwd()
TMP = Path(tempfile.gettempdir()) / "opencode" / "chave-tx"  # no Linux: /tmp/opencode/chave-tx
LOGGER_REL = "jabs/log/EnhancedBlockFinalizationLogger.java"


def sonda(k):
    return 'System.err.println("PROBE %s " + System.identityHashCode(new Object()));\n' % k


def injetar(texto):
    # P1/P1b: em volta da extracao
    m = re.search(r"^(\s*)(\S[^\n]*= extractTransactionsFromBlock\(block\);\n)", texto, re.M)
    assert m, "ancora da extracao nao encontrada"
    texto = texto[:m.start()] + m.group(1) + sonda("P1-antes-extrair") + m.group(0) + m.group(1) + 'System.err.println("SIZE h=" + block.getHeight() + " n=" + txIds.size() + " cls=" + block.getClass().getSimpleName());\n' + m.group(1) + sonda("P1b-depois-extrair") + texto[m.end():]
    ancora = "            // Clear obsolete data periodically to prevent memory leaks\n"
    assert texto.count(ancora) == 1, "ancora do clear nao encontrada"
    texto = texto.replace(ancora, "            " + sonda("P2-depois-laco") + ancora, 1)
    ret = "            return true;\n        } else if (event instanceof BlockProposalEvent) {"
    assert texto.count(ret) == 1, "ancora do return nao encontrada"
    texto = texto.replace(ret, "            " + sonda("P3-fim") + ret, 1)
    return texto


extras = {}


def preparar(nome, logger_src):
    base = TMP / ("diag-" + nome)
    if base.exists():
        shutil.rmtree(base)
    (base / "src").mkdir(parents=True)
    shutil.copytree(RAIZ / "src/main/java", base / "src", dirs_exist_ok=True)
    alvo = base / "src" / LOGGER_REL
    alvo.write_text(injetar(logger_src.read_text(encoding="utf-8")), encoding="utf-8")
    for rel, conteudo in extras.items():
        if nome == "atual":
            (base / "src" / rel).write_text(conteudo, encoding="utf-8")
    (base / "classes").mkdir()
    cp = (RAIZ / "target/cp.txt").read_text(encoding="utf-8").strip()
    fontes = [str(p) for p in (base / "src").rglob("*.java")]
    r = subprocess.run(["javac", "-nowarn", "-d", str(base / "classes"), "-cp", cp] + fontes, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("javac falhou (%s): %s" % (nome, r.stderr[-1500:]))
    return base / "classes"


def rodar(classes, a):
    cp = (RAIZ / "target/cp.txt").read_text(encoding="utf-8").strip()
    saida = TMP / ("diag-run-" + classes.parent.name)
    saida.mkdir(exist_ok=True)
    cmd = ["java", "-cp", os.pathsep.join([str(classes), str(RAIZ / "target/test-classes"), cp]), "jabs.example.SondaHash", "run", "0",
           "--protocols=" + a.protocolo, "--validators=%d" % a.n, "--trials=1", "--duration=%d" % a.dur,
           "--seed=%d" % a.semente, "--output=" + str(saida), "--csv-out=" + str(saida / "m.csv")]
    r = subprocess.run(cmd, capture_output=True, text=True)
    tam = [l for l in r.stderr.splitlines() if l.startswith("SIZE")]
    return [l.split() for l in r.stderr.splitlines() if l.startswith("PROBE")], tam


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--head-logger")
    ap.add_argument("--classe-vazia", action="store_true",
                    help="controle: compara HEAD x HEAD + UMA classe vazia carregada no construtor do logger (sem nenhuma outra mudanca)")
    ap.add_argument("--protocolo", default="AMBIFY")
    ap.add_argument("--n", type=int, default=10)
    ap.add_argument("--dur", type=int, default=60)
    ap.add_argument("--semente", type=int, default=16346)
    a = ap.parse_args()
    TMP.mkdir(parents=True, exist_ok=True)
    ref = subprocess.run(["java", "-cp", "%s%s%s" % (RAIZ / "target/test-classes", os.pathsep, (RAIZ / "target/cp.txt").read_text(encoding="utf-8").strip()),
                          "jabs.example.SondaHash", "ref", "4000000"], capture_output=True, text=True).stdout.split()
    pos = {}
    for i, v in enumerate(ref):
        pos.setdefault(v, i + 1)
    if a.head_logger:
        head = Path(a.head_logger)
    else:
        head = TMP / "Logger.head.java"
        head.write_text(subprocess.run(["git", "show", "HEAD:src/main/java/" + LOGGER_REL], capture_output=True,
                                       text=True, encoding="utf-8", check=True).stdout, encoding="utf-8")
    if a.classe_vazia:
        vazia = TMP / "LoggerVazia.java"
        txt = head.read_text(encoding="utf-8")
        ancora = "        this.processedHeights = new HashSet<>();\n"
        assert ancora in txt
        vazia.write_text(txt.replace(ancora, ancora + "        new ClasseVaziaDiag();\n", 1), encoding="utf-8")
        extras["jabs/log/ClasseVaziaDiag.java"] = "package jabs.log;\nfinal class ClasseVaziaDiag { }\n"
        versoes = [("head", head), ("atual", vazia)]
    else:
        versoes = None
    versoes = versoes or [("head", head), ("atual", RAIZ / "src/main/java" / LOGGER_REL)]
    res = {}
    tamanhos = {}
    for nome, src in versoes:
        classes = preparar(nome, src)
        sondas, tam = rodar(classes, a)
        res[nome] = [(s[1], pos.get(s[2], -1)) for s in sondas]
        tamanhos[nome] = tam
    print("transacoes extraidas por bloco finalizado (HEAD x atual):")
    for i, (x, y) in enumerate(zip(tamanhos["head"], tamanhos["atual"])):
        print("  %-48s | %-48s %s" % (x, y, "" if x == y else "<-- DIFERE"))
    print("blocos: HEAD=%d atual=%d" % (len(tamanhos["head"]), len(tamanhos["atual"])))
    for nome in ("head", "atual"):
        s = res[nome]
        print("== %s: %d sondas" % (nome, len(s)))
        ant = None
        for i, (k, p) in enumerate(s[:40]):
            print("  %-22s pos=%-6d delta=%s" % (k, p, "" if ant is None else p - ant))
            ant = p
    # consumo POR TRECHO (entre sondas do mesmo evento), comparavel entre versoes mesmo que a posicao absoluta divirja
    def trechos(s):
        out = []
        for i in range(0, len(s) - 3, 4):
            q = [p for _, p in s[i:i + 4]]
            out.append((q[1] - q[0], q[2] - q[1], q[3] - q[2]))
        return out
    th, ta = trechos(res["head"]), trechos(res["atual"])
    print("consumo por evento (extrair-sonda, laco, resto) HEAD x atual (sonda propria soma 1 por trecho):")
    for i, (x, y) in enumerate(zip(th, ta)):
        print("  evento %2d  HEAD=%s  atual=%s %s" % (i, x, y, "" if x == y else "  <-- DIFERE"))
    return 0


if __name__ == "__main__":
    sys.exit(main())

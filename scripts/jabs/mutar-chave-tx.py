#!/usr/bin/env python3
"""Sensibilidade a mutacao dos testes de LOGGER-chave-txid-sequencial (TxSequencerTest + EnhancedLoggerChaveTxTest).

Da raiz do repositorio:  python3 scripts/jabs/mutar-chave-tx.py [--mvn "mvn -o -q"]

1. baseline: os dois testes DEVEM passar.
2. mutantes (cada um sozinho; os testes DEVEM falhar):
     C1 logger volta a chavear por Hash da transacao (a correcao desfeita: addTx poe a chave original como chave);
     C2 contador estatico global em TxSequencer (o id de uma simulacao dependeria das anteriores na mesma JVM).
3. restaura os fontes byte a byte (try/finally e SIGINT/SIGTERM) e confere o sha256. Nao usa git.
Saida: 0 = baseline passou E todos os mutantes detectados; 1 = algum mutante passou ou baseline falhou; 2 = ancora
nao encontrada.
"""
import argparse
import hashlib
import shlex
import signal
import subprocess
import sys
from pathlib import Path

LOGGER = Path("src/main/java/jabs/log/EnhancedBlockFinalizationLogger.java")
SEQ = Path("src/main/java/jabs/ledgerdata/TxSequencer.java")
TESTES = "TxSequencerTest,EnhancedLoggerChaveTxTest"

MUTACOES = [
    ("C1 chave por Hash da tx (correcao desfeita)", LOGGER,
     [("txIds.put(txSequencer.idOf((Tx<?>) tx), original);", "txIds.put(original, original);")]),
    ("C2 contador estatico global", SEQ,
     [("    private long next = 0L;", "    private static long next = 0L;")]),
]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def rodar(mvn):
    r = subprocess.run(shlex.split(mvn) + ["test", "-Dtest=" + TESTES], capture_output=True, text=True)
    return r.returncode == 0, r.stdout + r.stderr


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mvn", default="mvn -o -q")
    a = ap.parse_args()
    originais = {p: p.read_bytes() for p in (LOGGER, SEQ)}
    hashes = {p: sha(p) for p in originais}

    def restaurar(*_):
        for p, b in originais.items():
            p.write_bytes(b)

    def ao_sinal(signum, _frame):
        restaurar()
        sys.exit(130)

    signal.signal(signal.SIGINT, ao_sinal)
    signal.signal(signal.SIGTERM, ao_sinal)
    ok = True
    try:
        passou, _ = rodar(a.mvn)
        print("baseline:", "PASSOU" if passou else "FALHOU")
        if not passou:
            return 1
        for nome, alvo, trocas in MUTACOES:
            t = originais[alvo].decode("utf-8")
            for velho, novo in trocas:
                if t.count(velho) != 1:
                    print("ancora nao encontrada (ou ambigua) em %s: %r" % (nome, velho))
                    return 2
                t = t.replace(velho, novo)
            alvo.write_text(t, encoding="utf-8")
            passou, saida = rodar(a.mvn)
            falhas = sorted({l.strip() for l in saida.splitlines() if "FAIL" in l or "ERROR]   " in l})[:4]
            print("%-48s %s" % (nome, "NAO DETECTADO (passou)" if passou else "detectado (testes falharam)"))
            for f in falhas:
                print("      ", f[:150])
            ok = ok and not passou
            restaurar()
    finally:
        restaurar()
    for p, h in hashes.items():
        igual = sha(p) == h
        print("restaurado %s: %s" % (p, "ok" if igual else "DIVERGENTE"))
        ok = ok and igual
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

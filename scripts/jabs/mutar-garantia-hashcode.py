#!/usr/bin/env python3
"""Sensibilidade a mutacao de GarantiaHashCodeTest (M1a-registro-enlace-linkload, rodada 4, criterio 7).

Da raiz do repositorio:  python3 scripts/jabs/mutar-garantia-hashcode.py [--mvn "mvn -o -q"]

1. baseline: GarantiaHashCodeTest (execucao hash2 do pom) DEVE passar.
2. mutantes sobre MVPComparison.java (cada um sozinho; o teste DEVE falhar):
     G1 exigir() de volta para DEPOIS do banner (pre-varredura removida do topo do main, chamada logo depois do banner);
     G2 exigir() depois do banner e do parseArgs (aviso de flag desconhecida sai antes da garantia);
     G3 pre-varredura removida (so configureLoadRecord verifica, como na rodada 3).
3. restaura o fonte byte a byte (try/finally e SIGINT/SIGTERM) e confere o sha256. Copia em /tmp/mutar-garantia.bak.
Saida: 0 = baseline passou E todos os mutantes detectados; 1 = algum mutante passou ou baseline falhou; 2 = ancora
nao encontrada. Nao usa git.
"""
import argparse
import hashlib
import shlex
import signal
import subprocess
import sys
from pathlib import Path

ALVO = Path("src/main/java/jabs/example/MVPComparison.java")
BACKUP = Path("/tmp/mutar-garantia.bak")
CHAMADA = "        exigirGarantiaAntesDeQualquerSaida(args);\n"
FIM_BANNER = '        System.out.println("╚════════════════════════════════════════════════════════╝\\n");\n'
APOS_PARSE = "        java.util.Map<String, String> cli = parseArgs(args);\n"

MUTACOES = [
    ("G1 exigir() depois do banner", [(CHAMADA, ""), (FIM_BANNER, FIM_BANNER + CHAMADA)]),
    ("G2 exigir() depois do banner e do parseArgs", [(CHAMADA, ""), (APOS_PARSE, APOS_PARSE + CHAMADA)]),
    ("G3 pre-varredura removida", [(CHAMADA, "")]),
]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def rodar(mvn):
    cmd = shlex.split(mvn) + ["test-compile", "surefire:test@hash2", "-Dtest=GarantiaHashCodeTest"]
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r.returncode == 0, (r.stdout + r.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mvn", default="mvn -o -q")
    a = ap.parse_args()
    original = ALVO.read_bytes()
    h0 = sha(ALVO)
    BACKUP.write_bytes(original)

    def restaurar(*_):
        ALVO.write_bytes(original)

    def ao_sinal(signum, _frame):
        restaurar()
        sys.exit(130)

    signal.signal(signal.SIGINT, ao_sinal)
    signal.signal(signal.SIGTERM, ao_sinal)
    texto = original.decode("utf-8")
    ok = True
    try:
        print(f"sha256 original  {h0}")
        passou, _ = rodar(a.mvn)
        print(f"baseline: {'PASSOU' if passou else 'FALHOU'}")
        if not passou:
            return 1
        for nome, trocas in MUTACOES:
            t = texto
            for velho, novo in trocas:
                if t.count(velho) != 1:
                    print(f"ancora nao encontrada (ou ambigua) em {nome}: {velho!r}")
                    return 2
                t = t.replace(velho, novo)
            ALVO.write_text(t, encoding="utf-8")
            passou, saida = rodar(a.mvn)
            if passou:
                print(f"{nome}: NAO DETECTADA")
                ok = False
            else:
                falhas = sorted({l.strip()[:150] for l in saida.splitlines() if "GarantiaHashCodeTest." in l and "ERROR" in l})
                print(f"{nome}: DETECTADA ({len(falhas)} linhas de falha)")
                for f in falhas[:4]:
                    print("    " + f)
    finally:
        restaurar()
        h1 = sha(ALVO)
        print(f"sha256 restaurado {h1}  {'IGUAL' if h1 == h0 else 'DIFERENTE!!'}")
        ok = ok and h1 == h0
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

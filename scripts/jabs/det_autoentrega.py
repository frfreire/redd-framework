#!/usr/bin/env python3
r"""D2: roda scripts/jabs/checar-determinismo.py (mesma semente, duas execucoes, CSV comparados sem colunas 'hash' e sem
linhas '#') para cada protocolo AFETADO pela autoentrega uniforme e para os NAO afetados, na arvore atual.

Uso (da raiz, com target/classes e target/cp.txt construidos):
  det_autoentrega.py [--jabs .] [--semente 1]
Saida: 0 = todos identicos; 1 = algum divergente; 2 = erro de execucao.
"""
import argparse
import subprocess
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
HS = ["--hs-view-timeout=10.0", "--hs-max-future-view-horizon=1000", "--hs-batch-size=20", "--hs-tx-size=512",
      "--hs-individual-signature-size=64", "--hs-aggregated-signature-size=96", "--hs-aggregated-share-size=96",
      "--hs-message-overhead=10", "--hs-signature-mode=INDIVIDUAL", "--hs-leader-policy=ROTATING",
      "--hs-voter-encoding=BITMAP"]
TM = ["--tm-propose-timeout=1.0", "--tm-propose-delta=0.5", "--tm-prevote-timeout=1.0", "--tm-prevote-delta=0.5",
      "--tm-precommit-timeout=1.0", "--tm-precommit-delta=0.5", "--tm-batch-size=20", "--tm-tx-size=512",
      "--tm-signature-size=64", "--tm-proposer-signature-size=80", "--tm-message-overhead=10", "--tm-max-future-horizon=1000"]
SEM_N = ["--n-semantics=legacy"]  # Q32 A: obrigatoria onde n difere; a nao regressao fixa legacy
CASOS = [("ARQ", 7, 60, []), ("ARQ", 13, 60, []), ("MCO2", 20, 1200, SEM_N), ("TREE", 10, 3000, SEM_N),
         ("AMBIFY", 10, 600, SEM_N), ("AMBIFY", 20, 600, SEM_N), ("OFFSET", 40, 20000, []), ("EARTH", 40, 20000, []),
         ("DAGSPER", 10, 600, []), ("HOTSTUFF", 4, 30, HS), ("TENDERMINT", 4, 30, TM)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jabs", default=".")
    ap.add_argument("--semente", type=int, default=1)
    a = ap.parse_args()
    pior = 0
    for proto, n, dur, flags in CASOS:
        cmd = [sys.executable, str(AQUI / "checar-determinismo.py"), "--jabs", a.jabs, "--protocolo", proto,
               "--validadores", str(n), "--duracao", str(dur), "--semente", str(a.semente)]
        for f in flags:
            cmd.append("--extra-flag=" + f)
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
        ultima = (r.stdout.strip().splitlines() or ["(sem saida)"])[-1]
        print(f"[{proto} n={n} dur={dur} seed={a.semente}] rc={r.returncode} {ultima}")
        pior = max(pior, r.returncode)
        if r.returncode == 2:
            print(r.stderr[-600:])
    print("RESULTADO:", "DETERMINISTICO" if pior == 0 else "DIVERGENTE/ERRO")
    return pior


if __name__ == "__main__":
    sys.exit(main())

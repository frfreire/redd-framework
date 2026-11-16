#!/usr/bin/env python3
"""FORMULA ANALITICA do trafego por bloco do HotStuff basico do JABS (honesto, sem timeout), derivada do CODIGO LIDO
(tamanhos em HotStuffVote.computeSize, HotStuffQuorumCertificate.computeSize, HotStuffProposalMessage, HotStuffQcMessage;
fluxo em HotStuff.java) e NAO de dado simulado desta rodada. Nao simula nada.

Mensagens por bloco decidido (n nos, f=floor((n-1)/3), q=2f+1, H=32 B hash, O=overhead, P=lote*tx_size = carga do bloco):
  voto (replica -> lider)            V = H + O + sig_voto      (INDIVIDUAL: sig_ind ; AGGREGATED: share)
  QC                                 Q = H + O + ceil(n/8) + (INDIVIDUAL: q*sig_ind ; AGGREGATED: sig_agregada)
  PREPARE (lider -> n-1)             (H + O + Q) + P            [leva highQC]
  PRE_COMMIT, COMMIT, DECIDE         (H + O + Q) cada, para n-1 replicas
  votos: 3 fases                     3 * (n-1) * V
  NEW_VIEW com QC (replica -> prox lider) (n-1) * Q
  Verificacao: reproduz EXATAMENTE CbRedeConsenso_avg_B = 913248 do dado antigo K40 n=64 INDIVIDUAL (conferido na
  etapa 1; dado antigo, nao desta rodada).
Por no (STABLE, lider = no 0):  lider recebe 3(n-1)V + (n-1)Q ; replica recebe 4(H+O+Q) + P.
Uso: formula-analitica.py [--autoteste]   (autoteste: roda validar-complexidade.py sobre a formula, para calibrar o criterio)
"""
import csv
import math
import subprocess
import sys
import tempfile
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[4]
H, O, P = 32, 10, 100 * 512
SIG_IND, SIG_AGG, SHARE = 64, 96, 96


def f_max(n):
    return (n - 1) // 3


def tamanhos(n, modo):
    q = 2 * f_max(n) + 1
    v = H + O + (SIG_IND if modo == "INDIVIDUAL" else SHARE)
    sig_qc = q * SIG_IND if modo == "INDIVIDUAL" else SIG_AGG
    Q = H + O + math.ceil(n / 8) + sig_qc
    return v, Q


def rede_consenso(n, modo):
    v, Q = tamanhos(n, modo)
    return 3 * (n - 1) * v + 4 * (n - 1) * (H + O + Q) + (n - 1) * Q


def rede_total(n, modo):
    return rede_consenso(n, modo) + (n - 1) * P


def rede_msgs(n, modo=None):
    """EMENDA 1b (16/11/2026, antes dos dados): pacotes entregues pela rede por bloco decidido = AUTENTICADORES no sentido do
    artigo (um QC conta como UM autenticador): PREPARE+PRE_COMMIT+COMMIT+DECIDE = 4(n-1); votos das 3 fases = 3(n-1);
    NEW_VIEW com QC = (n-1). Total 8(n-1), O(n), igual nos dois modos (so o tamanho dos pacotes muda)."""
    return 8 * (n - 1)


def rede_assinaturas(n, modo):
    """EMENDA 1b: assinaturas INDIVIDUAIS carregadas por bloco decidido. INDIVIDUAL: 3(n-1) (votos) + 5(n-1)*q (cada QC leva q=2f+1
    assinaturas; 5 mensagens com QC por replica) => O(n^2). AGGREGATED: cada QC conta 1 => 8(n-1), O(n). Explica por que os
    BYTES de consenso sao O(n^2) no INDIVIDUAL embora os autenticadores sejam O(n)."""
    q = 2 * f_max(n) + 1
    return 3 * (n - 1) + 5 * (n - 1) * (q if modo == "INDIVIDUAL" else 1)


def no_lider(n, modo):
    v, Q = tamanhos(n, modo)
    return 3 * (n - 1) * v + (n - 1) * Q


def no_replica(n, modo):
    v, Q = tamanhos(n, modo)
    return 4 * (H + O + Q) + P


def tabela(ns=(4, 7, 10, 16, 31)):
    for modo in ("INDIVIDUAL", "AGGREGATED"):
        for n in ns:
            print(f"{modo:10s} n={n:3d} rede_total={rede_total(n, modo):10d} rede_consenso={rede_consenso(n, modo):9d} "
                  f"lider={no_lider(n, modo):8d} replica={no_replica(n, modo):7d}")


def autoteste():
    ns = [4, 7, 10, 16, 31]
    val = RAIZ / "scripts" / "jabs" / "validar-complexidade.py"
    casos = []
    for modo in ("INDIVIDUAL", "AGGREGATED"):
        for nome, fn in (("rede_total", rede_total), ("rede_consenso", rede_consenso), ("no_lider", no_lider),
                         ("no_replica", no_replica)):
            casos.append((modo, nome, fn))
    for modo, nome, fn in casos:
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "d.csv"
            with open(p, "w", newline="") as fh:
                w = csv.writer(fh)
                w.writerow(["n", "bytes_trafegados"])
                for n in ns:
                    for _ in range(5):          # 5 replicacoes identicas (dado do modelo e deterministico)
                        w.writerow([n, fn(n, modo)])
            for esp in ("linear", "quadratico"):
                r = subprocess.run([sys.executable, str(val), str(p), "--esperado", esp, "--protocolo", f"{modo}/{nome}"],
                                   capture_output=True, text=True)
                linhas = [l for l in r.stdout.splitlines() if l.startswith(("linear", "quadratico:", "melhor"))]
                print(f"[{modo:10s}] {nome:13s} esperado={esp:10s} rc={r.returncode} | " + " | ".join(linhas))


if __name__ == "__main__":
    tabela()
    if "--autoteste" in sys.argv:
        print()
        autoteste()

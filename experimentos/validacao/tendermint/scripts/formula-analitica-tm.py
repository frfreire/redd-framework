#!/usr/bin/env python3
"""FORMULA ANALITICA do trafego por altura do Tendermint do JABS (honesto, round 0, sem timeout), derivada do CODIGO LIDO em
bc07355 (src/ == tree a1b464ee, o mesmo de 76932b9): Tendermint.java (execute/BROADCAST_*), TendermintProposalMessage,
TendermintVoteMessage, TendermintP2P (malha completa SEM o proprio no), PeerBlockchainNode (hook de trafego), NetworkHeightLedger.
NAO e derivada de dado simulado: NENHUMA celula foi rodada. E a MESMA fonte (codigo) que gera o dado: ela confere que o simulador faz
o que o codigo diz, NAO que o modelo reproduz o Tendermint do artigo (mesma ressalva do V-ABC do HotStuff, ADR-0015 16/11, item 3).

Por altura decidida (n nos; H=32 hash; O=overhead; sv=assinatura do voto; sp=assinatura do proponente; P=lote*tx = carga do bloco):
  proposta (proponente -> n-1)       pacote = H+O+sp+P ; parte de consenso Pc = H+O+sp         [(n-1) pacotes]
  prevote  (cada no -> n-1)          V = H+O+sv                                                 [n(n-1) pacotes]
  precommit(cada no -> n-1)          V                                                          [n(n-1) pacotes]
  total de pacotes por altura        (n-1) + 2n(n-1) = (n-1)(2n+1)
  bytes de consenso (sem carga)      (n-1)*Pc + 2n(n-1)*V
  bytes totais (com carga)           consenso + (n-1)*P
Votos nil: 0 sem timeout (um voto nil so sai de timeout/valor invalido). Autoentrega: 0 (TendermintP2P nao liga o no a si mesmo).
POR NO, por altura (round 0, proponente = no (altura_bloco-1) mod n, `TendermintCore.roundRobin`):
  proponente:  recebe 2(n-1) votos         -> 2(n-1) pacotes ; 2(n-1)*V bytes (tudo e consenso)
  validador:   recebe a proposta + 2(n-1)  -> 2n-1 pacotes   ; Pc+P + 2(n-1)*V bytes
Ressalva de janela: contagem "sem corte local" por altura (NetworkHeightLedger); janela de regime = alturas 2..hmax-2 (CbRedeMedida).
Uso: formula-analitica-tm.py [--autoteste]
"""
import csv
import math
import subprocess
import sys
import tempfile
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[4]
H, O, SV, SP = 32, 10, 64, 64      # SV, SP, O: FIXTURES SEM FONTE (ADR-0015 Q1/Q3); SP igual a SV e escolha provisoria (A-02)
L, TX = 100, 512
P = L * TX


def fmax(n):
    return (n - 1) // 3


def voto():
    return H + O + SV


def pc():
    return H + O + SP


def rede_msgs(n):
    return (n - 1) * (2 * n + 1)


def rede_consenso(n):
    return (n - 1) * pc() + 2 * n * (n - 1) * voto()


def rede_total(n):
    return rede_consenso(n) + (n - 1) * P


def no_msgs(n, papel):
    return 2 * (n - 1) if papel == "proponente" else 2 * n - 1


def no_bytes_total(n, papel):
    base = 2 * (n - 1) * voto()
    return base if papel == "proponente" else base + pc() + P


def no_bytes_consenso(n, papel):
    base = 2 * (n - 1) * voto()
    return base if papel == "proponente" else base + pc()


def ols_b(ns, ys):
    x = [math.log(n) for n in ns]
    y = [math.log(v) for v in ys]
    mx, my = sum(x) / len(x), sum(y) / len(y)
    return sum((a - mx) * (c - my) for a, c in zip(x, y)) / sum((a - mx) ** 2 for a in x)


def classe(b):
    return "O(1)" if b < 0.5 else ("O(n)" if b < 1.5 else ("O(n^2)" if b < 2.5 else ">O(n^2)"))


NS = (4, 7, 10, 16, 31)
MEDIDAS = {"rede_msgs": rede_msgs, "rede_consenso": rede_consenso, "rede_total": rede_total,
           "no_validador_msgs": lambda n: no_msgs(n, "validador"), "no_proponente_msgs": lambda n: no_msgs(n, "proponente"),
           "no_validador_bytes_consenso": lambda n: no_bytes_consenso(n, "validador"),
           "no_proponente_bytes_consenso": lambda n: no_bytes_consenso(n, "proponente"),
           "no_validador_bytes_total": lambda n: no_bytes_total(n, "validador"),
           "no_proponente_bytes_total": lambda n: no_bytes_total(n, "proponente")}


def previsoes():
    out = {}
    for nome, fn in MEDIDAS.items():
        v = [fn(n) for n in NS]
        b = ols_b(NS, v)
        out[nome] = {"por_n": dict(zip(map(str, NS), v)), "b": round(b, 5), "classe": classe(b)}
    return out


def main():
    prev = previsoes()
    print(f"H={H} O={O} SV={SV} SP={SP} L={L} TX={TX} P={P}  (SV, SP, O: fixtures sem fonte)")
    for nome, d in prev.items():
        print(f"{nome:30s} b={d['b']:.4f} {d['classe']:7s} " + " ".join(f"n={n}:{v}" for n, v in d["por_n"].items()))
    if "--json" in sys.argv:
        print(__import__("json").dumps(prev, indent=1))
    if "--autoteste" in sys.argv:
        val = RAIZ / "scripts" / "jabs" / "validar-complexidade.py"
        for nome in ("rede_msgs", "rede_consenso", "rede_total"):
            with tempfile.TemporaryDirectory() as td:
                p = Path(td) / "d.csv"
                with open(p, "w", newline="") as fh:
                    w = csv.writer(fh)
                    w.writerow(["n", "bytes_trafegados"])
                    for n in NS:
                        for _ in range(5):
                            w.writerow([n, MEDIDAS[nome](n)])
                for esp in ("linear", "quadratico"):
                    r = subprocess.run([sys.executable, str(val), str(p), "--esperado", esp, "--protocolo", f"TENDERMINT/{nome}"],
                                       capture_output=True, text=True)
                    print(f"[autoteste sobre a FORMULA, nao sobre o simulador] {nome} esperado={esp} rc={r.returncode}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Repete por script as contas FEITAS A MAO na Q35 (ADR-0015, 17/11/2026). Biblioteca padrao apenas.

Replica a regra de RandomnessEngine.sampleDistributionWithBins (RandomnessEngine.java:30-41): sorteia u~U[0,1); percorre
dist[0 .. len-2]; se u < dist[i] devolve bins[i] + (u/dist[i])*(bins[i+1]-bins[i]) (truncado); senao u -= dist[i]; se
nenhuma entrada captura u, devolve bins[ultimo]. Tabelas copiadas de TransactionFactory.java:27-45.
Gas: BITCOIN_TRANSACTION_GAS_DISTRIBUTION (20 probs) com ETHEREUM_TRANSACTION_GAS_BINS (21 bins).
Limite: MAXIMUM_BLOCK_GAS = 12_500_000 (EthereumMinerNode.java:27).
O Monte Carlo usa o gerador do Python (NAO o MersenneTwister do JABS): so ordem de grandeza.
"""
import random
import sys

GAS_DIST = [0.000016894, 0.000069723, 0.000020709, 0.000260152, 0.001366569, 0.000187092, 0.000253752, 0.000358685,
            0.002723765, 0.001816119, 0.003954819, 0.012228619, 0.023134672, 0.139374064, 0.146851576, 0.241159595,
            0.110655111, 0.060103259, 0.032087937, 0.023120530]
GAS_BINS = [21001, 21003, 21007, 21015, 21031, 21063, 21127, 21255, 21511, 22023, 23048, 25097, 29204, 37401, 53785,
            86553, 152114, 283357, 548945, 1141405, 12475587]
SZ_DIST = [0.33566250, 0.00029251, 0.03196772, 0.00135259, 0.00431051, 0.04577845, 0.46076570, 0.05192406, 0.03867314,
           0.0175110, 0.00219022, 0.00126404, 0.00041145]
SZ_BINS = [214, 216, 220, 228, 244, 276, 340, 470, 729, 1298, 2639, 5084, 12630, 296985]
LIMITE = 12_500_000


def amostra(u, dist, bins):
    for i in range(len(dist) - 1):
        if u < dist[i]:
            return bins[i] + int((u / dist[i]) * (bins[i + 1] - bins[i]))
        u -= dist[i]
    return bins[-1]


def esperanca_exata(dist, bins):
    """E[valor]: cada i < len-1 contribui dist[i] * (bins[i]+bins[i+1])/2 (uniforme em u/dist[i]); resto vai ao ultimo bin."""
    e = 0.0
    for i in range(len(dist) - 1):
        e += dist[i] * (bins[i] + bins[i + 1]) / 2.0
    resto = 1.0 - sum(dist[:-1])
    return e + resto * bins[-1], resto


def main():
    print("== Gas (tabela com %d probs e %d bins) ==" % (len(GAS_DIST), len(GAS_BINS)))
    print("soma das %d entradas: %.6f; soma das %d testadas pelo laco: %.6f" % (
        len(GAS_DIST), sum(GAS_DIST), len(GAS_DIST) - 1, sum(GAS_DIST[:-1])))
    e, resto = esperanca_exata(GAS_DIST, GAS_BINS)
    print("P(gas == bins[ultimo]=%d) = 1 - soma das testadas = %.4f  (%.2f%%)" % (GAS_BINS[-1], resto, 100 * resto))
    print("gas medio por tx (exato, sob a regra do laco): %.0f" % e)
    print("12_500_000 / gas medio = %.2f tx por bloco (razao de medias, NAO o numero amostrado)" % (LIMITE / e))
    print("duas tx de gas maximo cabem? 2*%d = %d > %d: %s" % (GAS_BINS[-1], 2 * GAS_BINS[-1], LIMITE,
                                                              "NAO" if 2 * GAS_BINS[-1] > LIMITE else "SIM"))

    print("\n== Tamanho (tabela com %d probs e %d bins) ==" % (len(SZ_DIST), len(SZ_BINS)))
    es, rs = esperanca_exata(SZ_DIST, SZ_BINS)
    print("soma das %d entradas: %.6f; soma das %d testadas: %.6f" % (
        len(SZ_DIST), sum(SZ_DIST), len(SZ_DIST) - 1, sum(SZ_DIST[:-1])))
    print("P(tamanho == %d B) = %.4f (%.2f%%); tamanho medio por tx: %.0f B" % (SZ_BINS[-1], rs, 100 * rs, es))

    print("\n== Monte Carlo (Python random; ordem de grandeza) ==")
    for semente in (1, 2, 3):
        rng = random.Random(semente)
        n_amostras = 200_000
        gigantes = sum(1 for _ in range(n_amostras) if amostra(rng.random(), GAS_DIST, GAS_BINS) == GAS_BINS[-1])
        # bloco: percorre tx ate a primeira que estoura (EthereumMinerNode.java:51-59, break em :54-56)
        cont = []
        for _ in range(20000):
            gas, k = 0, 0
            while True:
                g = amostra(rng.random(), GAS_DIST, GAS_BINS)
                if gas + g > LIMITE:
                    break
                gas += g
                k += 1
            cont.append(k)
        cont.sort()
        print("semente %d: gigantes=%.4f ; tx por bloco: media=%.2f mediana=%d p10=%d p90=%d min=%d max=%d" % (
            semente, gigantes / n_amostras, sum(cont) / len(cont), cont[len(cont) // 2],
            cont[len(cont) // 10], cont[9 * len(cont) // 10], cont[0], cont[-1]))

    print("\n== Trava g*L <= limite (Q35 A (i)) ==")
    for L in (10, 100, 500):
        print("L=%d: g maximo = floor(%d/L) = %d" % (L, LIMITE, LIMITE // L))
    print("menor bin da tabela: %d; menor bin <= 25000? %s" % (GAS_BINS[0], GAS_BINS[0] <= 25000))
    return 0


if __name__ == "__main__":
    sys.exit(main())

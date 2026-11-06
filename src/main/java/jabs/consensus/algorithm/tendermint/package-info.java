/**
 * Tendermint (Buchman, Kwon e Milosevic, "The latest gossip on BFT consensus", arXiv:1807.04938, 2018, Algoritmo 1).
 *
 * <p>Mesmo padrao do pacote {@code hotstuff}: o {@link jabs.consensus.algorithm.tendermint.TendermintCore} e puro
 * (sem rede, sem simulador, sem tempo) e devolve {@link jabs.consensus.algorithm.tendermint.TendermintAction}; o
 * adaptador JABS (etapa seguinte) executa as acoes: envia pacotes, agenda timeouts e finaliza blocos.
 */
package jabs.consensus.algorithm.tendermint;

package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;

/**
 * Pergunta "este no e o lider desta visao?" para um config e um n fixos. Nao reimplementa a regra: delega a
 * {@link HotStuffConsensusConfig#leaderFor(long, int, HotStuffConsensusConfig.LeaderPolicy)} (fonte unica; ROTATING:
 * {@code view mod n}; STABLE: no 0). Imutavel, sem estado global.
 *
 * <p><b>A conferir com o artigo:</b> que a rotacao {@code view mod n} e a escolha de lider do HotStuff basico (o
 * artigo so exige uma funcao deterministica de visao para lider; a escolha concreta e do config).
 */
public final class ViewLeaders {

    private final HotStuffConsensusConfig config;
    private final int n;

    /**
     * @param config configuracao (define a politica de lideranca; nao nula)
     * @param n numero de nos (&gt;= 4)
     * @throws IllegalArgumentException se {@code config} for nulo ou {@code n} &lt; 4
     */
    public ViewLeaders(HotStuffConsensusConfig config, int n) {
        if (config == null) {
            throw new IllegalArgumentException("config obrigatorio");
        }
        config.validateFor(n);
        this.config = config;
        this.n = n;
    }

    /**
     * Lider da visao.
     *
     * @param view visao (&gt;= 0)
     * @return id do lider em [0, n)
     * @throws IllegalArgumentException se {@code view} &lt; 0
     */
    public int leaderOf(long view) {
        return HotStuffConsensusConfig.leaderFor(view, n, config.getLeaderPolicy());
    }

    /**
     * O no e o lider da visao?
     *
     * @param view visao (&gt;= 0)
     * @param nodeId id do no, em [0, n)
     * @return {@code true} se {@code nodeId} lidera {@code view}
     * @throws IllegalArgumentException se {@code view} &lt; 0 ou {@code nodeId} fora de [0, n)
     */
    public boolean isLeader(long view, int nodeId) {
        if (nodeId < 0 || nodeId >= n) {
            throw new IllegalArgumentException("nodeId fora de [0," + n + "): " + nodeId);
        }
        return leaderOf(view) == nodeId;
    }
}

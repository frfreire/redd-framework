package jabs.network.message;

import jabs.consensus.algorithm.tendermint.TendermintVote;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.network.node.nodes.Node;

/**
 * {@code <PREVOTE, h, r, id(v)>} ou {@code <PRECOMMIT, h, r, id(v)>} no fio. O voto NAO carrega a carga do
 * bloco: leva so o identificador (hash de 32 B), o overhead do config e a assinatura do voto:
 * {@code tamanho = hash + messageOverheadBytes + signatureSizeBytes}. {@code block == null} e o voto nil (mesmo
 * tamanho). O tamanho da assinatura e parametro de pesquisa, sem padrao, <b>a definir com o humano</b> (ADR-0010
 * PI-08 e PI-16). Este calculo vale so para o Tendermint; nao e unificado com pBFT/HotStuff (assimetria registrada
 * no ADR-0010 PI-08).
 */
public final class TendermintVoteMessage extends Message {
    private final Node sender;
    private final TendermintVote.Type type;
    private final long height;
    private final int round;
    private final TendermintBlock block;

    public TendermintVoteMessage(Node sender, TendermintVote.Type type, long height, int round,
                                 TendermintBlock block, TendermintConsensusConfig config) {
        super(sizeOf(config));
        if (sender == null || type == null || config == null) {
            throw new IllegalArgumentException("sender, type e config obrigatorios");
        }
        if (height < 0) {
            throw new IllegalArgumentException("height deve ser >= 0: " + height);
        }
        if (round < 0) {
            throw new IllegalArgumentException("round deve ser >= 0: " + round);
        }
        this.sender = sender;
        this.type = type;
        this.height = height;
        this.round = round;
        this.block = block;
    }

    public TendermintVote<TendermintBlock> toVote() {
        return new TendermintVote<>(type, height, round, block, sender.getNodeID());
    }

    private static int sizeOf(TendermintConsensusConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config obrigatorio");
        }
        return jabs.ledgerdata.tendermint.TendermintBlock.TENDERMINT_BLOCK_HASH_SIZE
                + config.getMessageOverheadBytes()
                + config.getSignatureSizeBytes();
    }

    public Node getSender() { return sender; }
    public TendermintVote.Type getType() { return type; }
    public long getHeight() { return height; }
    public int getRound() { return round; }
    public TendermintBlock getBlock() { return block; }
    public boolean isNil() { return block == null; }
}

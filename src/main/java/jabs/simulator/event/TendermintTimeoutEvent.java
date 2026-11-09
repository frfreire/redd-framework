package jabs.simulator.event;

import jabs.consensus.algorithm.Tendermint;
import jabs.consensus.algorithm.tendermint.TimeoutKind;

/**
 * Entrega um temporizador do Algoritmo 1 ao no dono. Timeout obsoleto e filtrado pelo nucleo
 * ({@code STALE_TIMEOUT}), entao o adaptador nao precisa cancelar eventos.
 */
public final class TendermintTimeoutEvent implements Event {
    private final Tendermint<?, ?> algorithm;
    private final TimeoutKind kind;
    private final long height;
    private final int round;

    public TendermintTimeoutEvent(Tendermint<?, ?> algorithm, TimeoutKind kind, long height, int round) {
        if (algorithm == null || kind == null) {
            throw new IllegalArgumentException("algorithm e kind obrigatorios");
        }
        if (height < 0) {
            throw new IllegalArgumentException("height deve ser >= 0: " + height);
        }
        if (round < 0) {
            throw new IllegalArgumentException("round deve ser >= 0: " + round);
        }
        this.algorithm = algorithm;
        this.kind = kind;
        this.height = height;
        this.round = round;
    }

    @Override
    public void execute() {
        algorithm.onTimeout(kind, height, round);
    }

    public TimeoutKind getKind() { return kind; }
    public long getHeight() { return height; }
    public int getRound() { return round; }
}

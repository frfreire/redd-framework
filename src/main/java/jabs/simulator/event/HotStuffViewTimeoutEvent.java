package jabs.simulator.event;

import jabs.consensus.algorithm.HotStuff;

/**
 * Entrega ao no dono o timeout da visao {@code view} do HotStuff (Yin et al., secao 4.1, "nextView interrupt").
 * Timeout obsoleto (a replica ja passou da visao) e filtrado e contado pelo nucleo
 * ({@code DiscardReason.STALE_VIEW_TIMEOUT}), entao o adaptador nao cancela eventos (mesmo estilo do
 * {@link TendermintTimeoutEvent}). Nao consome aleatoriedade: so guarda o algoritmo e a visao.
 */
public final class HotStuffViewTimeoutEvent implements Event {
    private final HotStuff<?, ?> algorithm;
    private final long view;

    public HotStuffViewTimeoutEvent(HotStuff<?, ?> algorithm, long view) {
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm obrigatorio");
        }
        if (view < 0) {
            throw new IllegalArgumentException("view deve ser >= 0: " + view);
        }
        this.algorithm = algorithm;
        this.view = view;
    }

    @Override
    public void execute() {
        algorithm.onViewTimeout(view);
    }

    public long getView() { return view; }
}

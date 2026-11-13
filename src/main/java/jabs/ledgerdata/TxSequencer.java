package jabs.ledgerdata;

/**
 * Sequenciador de transacoes de UMA simulacao (uma instancia por registrador, nunca estatico): da a cada
 * {@link Tx} uma chave estavel POR VALOR ({@link Long}, ids 0, 1, 2... na ordem em que o registrador as ve pela
 * primeira vez), sem {@code hashCode} de identidade.
 *
 * <p><b>Por que existe.</b> {@link Data#getHash()} devolve um {@link Hash} sem {@code hashCode}/{@code equals}: como
 * chave de {@code HashSet}/{@code HashMap} ele cai em {@code Object.hashCode} (identidade). Com
 * {@code -XX:+UnlockExperimentalVMOptions -XX:hashCode=2} (condicao do registro M1a, {@code GarantiaHashCode}) todo
 * objeto tem o mesmo hash, o balde vira lista e a busca por bloco fica O(n^2) (HotStuff ROTATING n=4, lote 500, 30 s,
 * semente 16346: 0,56 s sem a flag, 58,67 s com ela). A chave {@link Long} tem {@code hashCode} por valor, entao o
 * custo nao depende do modo do hash de identidade.
 *
 * <p><b>Escopo da sequencia.</b> O contador e do sequenciador, e cada simulacao cria o seu: duas simulacoes na mesma
 * JVM (em sequencia ou em paralelo, em threads diferentes) numeram do zero e de forma independente. Um contador
 * estatico global quebraria isso (o id de uma simulacao dependeria de quantas rodaram antes ou ao lado). A posicao de
 * cada transacao na sequencia fica guardada na propria {@link Tx} (por sequenciador), de modo que a mesma transacao
 * recebe sempre o mesmo id do mesmo sequenciador.
 *
 * <p>Uso previsto: chamado pela thread da simulacao (o laco de eventos e de thread unica). Nao usa aleatoriedade,
 * relogio nem {@code identityHashCode}.
 */
public final class TxSequencer {
    private long next = 0L;

    /**
     * Id desta transacao neste sequenciador: o mesmo valor em toda chamada com a mesma {@code tx}; uma transacao nova
     * recebe o proximo inteiro (0, 1, 2...).
     *
     * @throws IllegalArgumentException se {@code tx} for {@code null}
     */
    public long idOf(Tx<?> tx) {
        if (tx == null) {
            throw new IllegalArgumentException("tx obrigatoria");
        }
        return tx.sequenceIn(this);
    }

    /** Quantas transacoes distintas este sequenciador ja numerou. */
    public long assigned() {
        return next;
    }

    /** Reservado a {@link Tx#sequenceIn(TxSequencer)}. */
    long take() {
        return next++;
    }
}

package jabs.ledgerdata.tendermint;

import jabs.ledgerdata.Tx;

public final class TendermintTx extends Tx<TendermintTx> {
    public TendermintTx(int size, int hashSize) {
        super(size, hashSize);
    }
}

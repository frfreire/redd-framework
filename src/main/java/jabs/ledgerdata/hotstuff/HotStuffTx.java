package jabs.ledgerdata.hotstuff;

import jabs.ledgerdata.Tx;

public final class HotStuffTx extends Tx<HotStuffTx> {
    public HotStuffTx(int size, int hashSize) {
        super(size, hashSize);
    }
}

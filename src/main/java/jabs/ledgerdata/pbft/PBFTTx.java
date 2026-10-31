package jabs.ledgerdata.pbft;

import jabs.ledgerdata.Tx;

public class PBFTTx extends Tx<PBFTTx> {
    public PBFTTx(int size, int hashSize) {
        super(size, hashSize);
    }
}

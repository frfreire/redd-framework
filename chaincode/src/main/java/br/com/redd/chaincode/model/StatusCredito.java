package br.com.redd.chaincode.model;

/**
 * Ciclo de vida de um {@link CreditoCarbono} no ledger.
 */
public enum StatusCredito {
    EMITIDO,
    TRANSFERIDO,
    APOSENTADO
}

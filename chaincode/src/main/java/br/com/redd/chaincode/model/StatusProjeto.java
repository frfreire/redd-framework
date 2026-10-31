package br.com.redd.chaincode.model;

/**
 * Ciclo de vida de um {@link ProjetoREDD} no ledger.
 */
public enum StatusProjeto {
    REGISTRADO,
    EM_MONITORAMENTO,
    SUSPENSO,
    ENCERRADO
}

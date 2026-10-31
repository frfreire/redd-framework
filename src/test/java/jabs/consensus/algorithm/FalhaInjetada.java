package jabs.consensus.algorithm;

/**
 * Falha injetada pelos testes do criterio 4 (modelo de falha explicito). Tipo proprio, para o teste provar que
 * foi EXATAMENTE a falha injetada que atravessou o hook (e nao uma NPE qualquer por outro motivo).
 */
public final class FalhaInjetada extends RuntimeException {
    public FalhaInjetada(String onde) {
        super("falha injetada: " + onde);
    }
}

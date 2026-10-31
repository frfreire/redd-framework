package br.com.redd.chaincode;

/**
 * Aritmetica de toneladas (INV-11, ADR-0002).
 *
 * <p>Unidade: kgCO2e, 1 unidade = 1 kg (1 tCO2e = 1000 unidades). Tipo
 * {@code long}. Nunca {@code double}/{@code float}. Toda soma e
 * multiplicacao passa por {@link Math#addExact(long, long)} /
 * {@link Math#multiplyExact(long, long)}; estouro vira
 * {@link RedContractException}, nunca valor truncado em silencio.</p>
 *
 * <p>Arredondamento R1: o contrato rejeita entrada que nao seja inteira em
 * kgCO2e. Os argumentos de transacao sao {@code long}: o shim recusa "1.5",
 * "1e3" e "1500.0" antes de chegar ao contrato. Nao ha arredondamento no
 * ledger; conversao de tCO2e decimal fica na borda (API/cliente).</p>
 */
public final class Toneladas {

    private Toneladas() {
    }

    /** Exige valor estritamente positivo. */
    public static long exigirPositivo(long valor, String campo) {
        if (valor <= 0) {
            throw new RedContractException(campo + " deve ser inteiro positivo em kgCO2e (recebido: " + valor + ").");
        }
        return valor;
    }

    /** Soma exata; estouro de long e rejeitado. */
    public static long somar(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new RedContractException("Estouro de long ao somar toneladas (kgCO2e): " + a + " + " + b + ".");
        }
    }

    /** Multiplicacao exata; estouro de long e rejeitado. */
    public static long multiplicar(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new RedContractException("Estouro de long ao multiplicar toneladas (kgCO2e): " + a + " * " + b + ".");
        }
    }
}

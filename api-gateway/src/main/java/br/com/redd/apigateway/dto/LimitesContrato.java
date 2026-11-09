package br.com.redd.apigateway.dto;

/**
 * Limites espelhados do contrato {@code RedContract} (fonte de verdade:
 * {@code chaincode/src/main/java/br/com/redd/chaincode/RedContract.java}).
 * Se o contrato mudar, esta classe muda junto; a API valida na borda para
 * dar erro claro (400) antes de gastar uma chamada ao peer, mas o contrato
 * continua sendo a autoridade (nao ha ajuste silencioso de valor).
 */
public final class LimitesContrato {

    private LimitesContrato() {
    }

    /**
     * {@code RedContract.PAGE_SIZE_MAXIMO}. O proprio contrato registra que o
     * valor definitivo e "a definir com o humano (ADR)"; 100 e o valor
     * vigente no codigo aprovado.
     */
    public static final int PAGE_SIZE_MAXIMO = 100;

    /** {@code RedContract.BOOKMARK_TAMANHO_MAXIMO}. */
    public static final int BOOKMARK_TAMANHO_MAXIMO = 2048;

    /** {@code RedContract.MAX_ID} (code points). */
    public static final int MAX_ID = 128;

    /** {@code RedContract.MAX_MOTIVO} (code points). */
    public static final int MAX_MOTIVO = 256;

    /**
     * Beneficiario (INV-08/INV-14): {@code "sha256:"} + 64 hex minusculos.
     * Forma unica, sem normalizacao. Sem ancoras: {@code @Pattern} exige
     * casamento do valor inteiro.
     */
    public static final String BENEFICIARIO_REGEX = "sha256:[0-9a-f]{64}";

    public static final String BENEFICIARIO_FORMATO_DESCRICAO =
            "sha256:<64 caracteres hexadecimais minusculos [0-9a-f]>";
}

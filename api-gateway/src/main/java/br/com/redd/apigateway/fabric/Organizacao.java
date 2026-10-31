package br.com.redd.apigateway.fabric;

import br.com.redd.apigateway.exception.OrgInvalidaException;

import java.util.Locale;

/**
 * Organizacao MSP suportada pela API, selecionavel via header HTTP
 * {@code X-Org} (ver {@link br.com.redd.apigateway.filter.OrgHeaderFilter}).
 */
public enum Organizacao {

    DEVELOPER("DeveloperMSP"),
    VERIFIER("VerifierMSP"),
    MARKETPLACE("MarketplaceMSP");

    private final String mspId;

    Organizacao(String mspId) {
        this.mspId = mspId;
    }

    public String getMspId() {
        return mspId;
    }

    /** Valor default quando o header {@code X-Org} nao e enviado pelo cliente. */
    public static final Organizacao DEFAULT = DEVELOPER;

    public static Organizacao fromHeader(String valor) {
        if (valor == null || valor.isBlank()) {
            return DEFAULT;
        }
        try {
            return Organizacao.valueOf(valor.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OrgInvalidaException(
                    "Valor invalido para o header X-Org: '" + valor
                            + "'. Valores aceitos: developer, verifier, marketplace.");
        }
    }
}

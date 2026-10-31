package br.com.redd.apigateway.fabric;

/**
 * Contexto thread-local com a organizacao MSP selecionada para a requisicao
 * HTTP atual (via header {@code X-Org}), preenchido por
 * {@link br.com.redd.apigateway.filter.OrgHeaderFilter} e consumido pelos
 * controllers/servicos ao escolher qual identidade usar no
 * {@link FabricGatewayService}.
 */
public final class OrgContext {

    private static final ThreadLocal<Organizacao> ATUAL = new ThreadLocal<>();

    private OrgContext() {
    }

    public static void set(Organizacao org) {
        ATUAL.set(org);
    }

    public static Organizacao get() {
        Organizacao org = ATUAL.get();
        return org != null ? org : Organizacao.DEFAULT;
    }

    public static void clear() {
        ATUAL.remove();
    }
}

package br.com.redd.apigateway.dto;

import br.com.redd.apigateway.exception.RequisicaoInvalidaException;

/**
 * Validacao dos parametros de paginacao na borda, com os mesmos limites do
 * contrato (ver {@link LimitesContrato}). Fora do limite a API responde 400
 * com erro claro; nunca ajusta o valor em silencio (o contrato tambem nao).
 */
public final class Paginacao {

    private Paginacao() {
    }

    /** {@code pageSize} entre 1 e {@link LimitesContrato#PAGE_SIZE_MAXIMO}. */
    public static int exigirPageSize(int pageSize) {
        if (pageSize < 1 || pageSize > LimitesContrato.PAGE_SIZE_MAXIMO) {
            throw new RequisicaoInvalidaException("pageSize deve estar entre 1 e "
                    + LimitesContrato.PAGE_SIZE_MAXIMO + " (recebido: " + pageSize + ").");
        }
        return pageSize;
    }

    /** {@code offset >= 0}. */
    public static int exigirOffset(int offset) {
        if (offset < 0) {
            throw new RequisicaoInvalidaException("offset deve ser maior ou igual a 0 (recebido: " + offset + ").");
        }
        return offset;
    }

    /**
     * Bookmark opaco do CouchDB: ausente equivale a primeira pagina (""). A
     * mensagem de erro nao repete o valor recebido.
     */
    public static String normalizarBookmark(String bookmark) {
        String marcador = bookmark == null ? "" : bookmark;
        if (marcador.length() > LimitesContrato.BOOKMARK_TAMANHO_MAXIMO) {
            throw new RequisicaoInvalidaException(
                    "bookmark excede " + LimitesContrato.BOOKMARK_TAMANHO_MAXIMO + " caracteres.");
        }
        return marcador;
    }

    /** Id de path: obrigatorio, sem brancos nas pontas, ate {@link LimitesContrato#MAX_ID} code points. */
    public static String exigirId(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new RequisicaoInvalidaException(campo + " e obrigatorio.");
        }
        if (!valor.equals(valor.strip())) {
            throw new RequisicaoInvalidaException(campo + " nao pode ter espacos nas pontas.");
        }
        if (valor.codePointCount(0, valor.length()) > LimitesContrato.MAX_ID) {
            throw new RequisicaoInvalidaException(
                    campo + " excede o tamanho maximo de " + LimitesContrato.MAX_ID + " caracteres.");
        }
        return valor;
    }
}

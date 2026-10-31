package br.com.redd.chaincode.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Pagina de resultado de {@code listarCreditosPorProjeto}. Formato fixo e
 * deterministico: ordem dos campos declarada em {@link JsonPropertyOrder}
 * ({@code bookmark}, {@code creditos}, {@code pageSize}, {@code quantidade}) e
 * ordem dos creditos dada pelo {@code sort} da consulta (idProjeto, _id).
 *
 * <p>{@code bookmark} e opaco (devolvido pelo CouchDB) e deve ser repassado
 * tal qual na chamada seguinte. Pagina com {@code quantidade < pageSize} e a
 * ultima; pagina cheia pode ou nao ser a ultima (a seguinte vem vazia).</p>
 */
@JsonPropertyOrder({"bookmark", "creditos", "pageSize", "quantidade"})
public class PaginaCreditos {

    private final String bookmark;
    private final CreditoCarbono[] creditos;
    private final int pageSize;
    private final int quantidade;

    public PaginaCreditos(String bookmark, CreditoCarbono[] creditos, int pageSize) {
        this.bookmark = bookmark == null ? "" : bookmark;
        this.creditos = creditos;
        this.pageSize = pageSize;
        this.quantidade = creditos.length;
    }

    public String getBookmark() {
        return bookmark;
    }

    public CreditoCarbono[] getCreditos() {
        return creditos;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getQuantidade() {
        return quantidade;
    }
}

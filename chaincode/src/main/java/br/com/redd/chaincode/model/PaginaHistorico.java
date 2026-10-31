package br.com.redd.chaincode.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Pagina do historico de versoes de um credito ({@code historicoCredito}).
 * Mesmo espirito de {@link PaginaCreditos}: formato fixo e deterministico, com
 * ordem dos campos declarada em {@link JsonPropertyOrder} ({@code eventos},
 * {@code offset}, {@code pageSize}, {@code quantidade}, {@code temMais}).
 *
 * <p>A paginacao e por deslocamento (offset) sobre a sequencia de versoes
 * validas na ordem do ledger ({@code getHistoryForKey} nao tem bookmark).
 * {@code temMais} diz se existe ao menos uma versao alem desta pagina; a
 * pagina seguinte comeca em {@code offset + quantidade}.</p>
 */
@JsonPropertyOrder({"eventos", "offset", "pageSize", "quantidade", "temMais"})
public class PaginaHistorico {

    private final TransferenciaHistorico[] eventos;
    private final int offset;
    private final int pageSize;
    private final int quantidade;
    private final boolean temMais;

    public PaginaHistorico(TransferenciaHistorico[] eventos, int offset, int pageSize, boolean temMais) {
        this.eventos = eventos;
        this.offset = offset;
        this.pageSize = pageSize;
        this.quantidade = eventos.length;
        this.temMais = temMais;
    }

    public TransferenciaHistorico[] getEventos() {
        return eventos;
    }

    public int getOffset() {
        return offset;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getQuantidade() {
        return quantidade;
    }

    public boolean isTemMais() {
        return temMais;
    }
}

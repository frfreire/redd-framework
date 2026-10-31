package br.com.redd.chaincode.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

import java.time.Instant;

/**
 * Entrada do historico de transferencias de um {@link CreditoCarbono},
 * mantida dentro do proprio asset (alem do historico de versoes do ledger,
 * que e consultado via {@code getHistoryForKey} em {@code historicoCredito}).
 */
@DataType()
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransferenciaHistorico {

    @Property()
    private String deMspId;
    @Property()
    private String paraMspId;
    @Property()
    private Instant timestamp;
    /** "EMISSAO", "TRANSFERENCIA" ou "APOSENTADORIA". */
    @Property()
    private String tipoEvento;

    public TransferenciaHistorico() {
        // construtor vazio exigido pelo Jackson para desserializacao
    }

    public TransferenciaHistorico(String deMspId, String paraMspId, Instant timestamp, String tipoEvento) {
        this.deMspId = deMspId;
        this.paraMspId = paraMspId;
        this.timestamp = timestamp;
        this.tipoEvento = tipoEvento;
    }

    public String getDeMspId() {
        return deMspId;
    }

    public void setDeMspId(String deMspId) {
        this.deMspId = deMspId;
    }

    public String getParaMspId() {
        return paraMspId;
    }

    public void setParaMspId(String paraMspId) {
        this.paraMspId = paraMspId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getTipoEvento() {
        return tipoEvento;
    }

    public void setTipoEvento(String tipoEvento) {
        this.tipoEvento = tipoEvento;
    }
}

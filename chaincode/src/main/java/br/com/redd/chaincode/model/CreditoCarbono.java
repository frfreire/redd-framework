package br.com.redd.chaincode.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Asset de ledger representando um credito de carbono emitido a partir de
 * um {@link RelatorioMRV} verificado.
 */
@DataType()
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreditoCarbono {

    @Property()
    private String id;
    @Property()
    private String idProjeto;
    @Property()
    private String idRelatorioMRV;
    @Property()
    private long toneladasCO2e;
    /** MSP ID (ou identidade de cliente) do proprietario atual do credito. */
    @Property()
    private String proprietarioAtual;
    @Property()
    private StatusCredito status;
    @Property()
    private List<TransferenciaHistorico> historico = new ArrayList<>();
    /** Preenchido apenas quando status == APOSENTADO. */
    @Property()
    private String motivoAposentadoria;
    /**
     * Identificador do beneficiario da aposentadoria (INV-14): referencia
     * opaca, rotulo ou hash. NUNCA dado pessoal direto (CPF, nome, etc.,
     * INV-08): o estado e publico entre as organizacoes e nao pode ser
     * apagado. Preenchido apenas quando status == APOSENTADO.
     */
    @Property()
    private String beneficiarioAposentadoria;
    /** MSP ID de quem aposentou (vem de ClientIdentity#getMSPID, nunca de argumento). */
    @Property()
    private String aposentadoPorMspId;
    /**
     * Referencia opaca do assinante: "sha256:" + hex de SHA-256(sal|MSP|ClientIdentity#getId).
     * Nunca o id bruto (base64 do DN, pode ter CN/nome; INV-08). Nunca de argumento.
     */
    @Property()
    private String aposentadoPorId;
    /** Instante da transacao de aposentadoria (stub.getTxTimestamp()). */
    @Property()
    private Instant aposentadoEm;

    public CreditoCarbono() {
        // construtor vazio exigido pelo Jackson para desserializacao
    }

    public CreditoCarbono(String id, String idProjeto, String idRelatorioMRV, long toneladasCO2e,
                           String proprietarioAtual, StatusCredito status) {
        this.id = id;
        this.idProjeto = idProjeto;
        this.idRelatorioMRV = idRelatorioMRV;
        this.toneladasCO2e = toneladasCO2e;
        this.proprietarioAtual = proprietarioAtual;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIdProjeto() {
        return idProjeto;
    }

    public void setIdProjeto(String idProjeto) {
        this.idProjeto = idProjeto;
    }

    public String getIdRelatorioMRV() {
        return idRelatorioMRV;
    }

    public void setIdRelatorioMRV(String idRelatorioMRV) {
        this.idRelatorioMRV = idRelatorioMRV;
    }

    public long getToneladasCO2e() {
        return toneladasCO2e;
    }

    public void setToneladasCO2e(long toneladasCO2e) {
        this.toneladasCO2e = toneladasCO2e;
    }

    public String getProprietarioAtual() {
        return proprietarioAtual;
    }

    public void setProprietarioAtual(String proprietarioAtual) {
        this.proprietarioAtual = proprietarioAtual;
    }

    public StatusCredito getStatus() {
        return status;
    }

    public void setStatus(StatusCredito status) {
        this.status = status;
    }

    public List<TransferenciaHistorico> getHistorico() {
        return historico;
    }

    public void setHistorico(List<TransferenciaHistorico> historico) {
        this.historico = historico;
    }

    public String getMotivoAposentadoria() {
        return motivoAposentadoria;
    }

    public void setMotivoAposentadoria(String motivoAposentadoria) {
        this.motivoAposentadoria = motivoAposentadoria;
    }

    public String getBeneficiarioAposentadoria() {
        return beneficiarioAposentadoria;
    }

    public void setBeneficiarioAposentadoria(String beneficiarioAposentadoria) {
        this.beneficiarioAposentadoria = beneficiarioAposentadoria;
    }

    public String getAposentadoPorMspId() {
        return aposentadoPorMspId;
    }

    public void setAposentadoPorMspId(String aposentadoPorMspId) {
        this.aposentadoPorMspId = aposentadoPorMspId;
    }

    public String getAposentadoPorId() {
        return aposentadoPorId;
    }

    public void setAposentadoPorId(String aposentadoPorId) {
        this.aposentadoPorId = aposentadoPorId;
    }

    public Instant getAposentadoEm() {
        return aposentadoEm;
    }

    public void setAposentadoEm(Instant aposentadoEm) {
        this.aposentadoEm = aposentadoEm;
    }
}

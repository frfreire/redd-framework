package br.com.redd.chaincode.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

import java.time.Instant;

/**
 * Asset de ledger representando um relatorio de MRV (Monitoramento, Relato e
 * Verificacao) submetido por um proponente de projeto REDD+.
 *
 * <p>O relatorio nao armazena o documento de evidencia em si (ledger nao
 * guarda blobs) -- apenas {@link #hashEvidencia}, o hash criptografico do
 * documento, cuja integridade pode ser auditada offline.</p>
 */
@DataType()
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RelatorioMRV {

    @Property()
    private String id;
    @Property()
    private String idProjeto;
    @Property()
    private Instant periodoInicio;
    @Property()
    private Instant periodoFim;
    @Property()
    private long toneladasCO2eEstimadas;
    /**
     * INV-12: toneladas atestadas pelo verificador (kgCO2e, ADR-0002), grandeza
     * separada de {@link #toneladasCO2eEstimadas}. 0 enquanto o relatorio nao
     * esta VERIFICADO (SUBMETIDO ou REJEITADO). Gravada uma unica vez, na
     * verificacao; depois disso o relatorio e terminal (INV-09) e o valor e
     * imutavel. A emissao usa somente este campo.
     */
    @Property()
    private long toneladasVerificadas;
    /**
     * INV-03: acumulado de kgCO2e ja emitido como credito a partir deste
     * relatorio. Comeca em 0, fica em 0 na verificacao e so e alterado por
     * emitirCredito, na mesma transacao que cria o credito, via
     * Toneladas.somar. Invariante: toneladasEmitidas &lt;= toneladasVerificadas.
     */
    @Property()
    private long toneladasEmitidas;
    /** Hash (ex.: SHA-256) do documento de evidencia -- nunca o blob em si. */
    @Property()
    private String hashEvidencia;
    @Property()
    private StatusRelatorioMRV status;
    /** MSP ID de quem verificou (preenchido somente apos verificarRelatorioMRV). */
    @Property()
    private String verificadoPorMspId;
    /** Justificativa dada pelo verificador (aprovacao ou rejeicao). */
    @Property()
    private String justificativaVerificacao;

    public RelatorioMRV() {
        // construtor vazio exigido pelo Jackson para desserializacao
    }

    public RelatorioMRV(String id, String idProjeto, Instant periodoInicio, Instant periodoFim,
                         long toneladasCO2eEstimadas, String hashEvidencia, StatusRelatorioMRV status) {
        this.id = id;
        this.idProjeto = idProjeto;
        this.periodoInicio = periodoInicio;
        this.periodoFim = periodoFim;
        this.toneladasCO2eEstimadas = toneladasCO2eEstimadas;
        this.hashEvidencia = hashEvidencia;
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

    public Instant getPeriodoInicio() {
        return periodoInicio;
    }

    public void setPeriodoInicio(Instant periodoInicio) {
        this.periodoInicio = periodoInicio;
    }

    public Instant getPeriodoFim() {
        return periodoFim;
    }

    public void setPeriodoFim(Instant periodoFim) {
        this.periodoFim = periodoFim;
    }

    public long getToneladasCO2eEstimadas() {
        return toneladasCO2eEstimadas;
    }

    public void setToneladasCO2eEstimadas(long toneladasCO2eEstimadas) {
        this.toneladasCO2eEstimadas = toneladasCO2eEstimadas;
    }

    public long getToneladasVerificadas() {
        return toneladasVerificadas;
    }

    public void setToneladasVerificadas(long toneladasVerificadas) {
        this.toneladasVerificadas = toneladasVerificadas;
    }

    public long getToneladasEmitidas() {
        return toneladasEmitidas;
    }

    public void setToneladasEmitidas(long toneladasEmitidas) {
        this.toneladasEmitidas = toneladasEmitidas;
    }

    public String getHashEvidencia() {
        return hashEvidencia;
    }

    public void setHashEvidencia(String hashEvidencia) {
        this.hashEvidencia = hashEvidencia;
    }

    public StatusRelatorioMRV getStatus() {
        return status;
    }

    public void setStatus(StatusRelatorioMRV status) {
        this.status = status;
    }

    public String getVerificadoPorMspId() {
        return verificadoPorMspId;
    }

    public void setVerificadoPorMspId(String verificadoPorMspId) {
        this.verificadoPorMspId = verificadoPorMspId;
    }

    public String getJustificativaVerificacao() {
        return justificativaVerificacao;
    }

    public void setJustificativaVerificacao(String justificativaVerificacao) {
        this.justificativaVerificacao = justificativaVerificacao;
    }
}

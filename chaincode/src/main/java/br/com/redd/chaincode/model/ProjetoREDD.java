package br.com.redd.chaincode.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

/**
 * Asset de ledger representando um projeto REDD+ (reducao de emissoes por
 * desmatamento e degradacao evitados).
 *
 * <p>Persistido via {@code ChaincodeStub.putStringState} serializado em JSON
 * (uma instancia por chave de projeto no ledger).</p>
 *
 * <p>{@code @DataType}/{@code @Property} sao exigidas pelo
 * {@code JSONTransactionSerializer} do fabric-chaincode-shim para montar a
 * metadata de tipo usada ao serializar o retorno de funcoes de transacao
 * (sem elas, a invocacao real via peer falha em runtime com
 * NullPointerException em {@code DataTypeDefinition.getProperties()} --
 * nao detectavel pelos testes unitarios, que chamam os metodos do contrato
 * diretamente sem passar pelo {@code ContractExecutionService}).</p>
 */
@DataType()
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjetoREDD {

    @Property()
    private String id;
    @Property()
    private String nome;
    /** Area do poligono do projeto, em hectares. */
    @Property()
    private double areaHectares;
    /** MSP ID da organizacao proponente (ex.: DeveloperMSP). Nunca aceito do payload do cliente para fins de autorizacao -- apenas registrado para consulta. */
    @Property()
    private String proponenteMspId;
    /** Metodologia de referencia (ex.: "VCS VM0007", "CDM AR-ACM0003"). */
    @Property()
    private String metodologia;
    @Property()
    private StatusProjeto status;

    public ProjetoREDD() {
        // construtor vazio exigido pelo Jackson para desserializacao
    }

    public ProjetoREDD(String id, String nome, double areaHectares, String proponenteMspId,
                        String metodologia, StatusProjeto status) {
        this.id = id;
        this.nome = nome;
        this.areaHectares = areaHectares;
        this.proponenteMspId = proponenteMspId;
        this.metodologia = metodologia;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public double getAreaHectares() {
        return areaHectares;
    }

    public void setAreaHectares(double areaHectares) {
        this.areaHectares = areaHectares;
    }

    public String getProponenteMspId() {
        return proponenteMspId;
    }

    public void setProponenteMspId(String proponenteMspId) {
        this.proponenteMspId = proponenteMspId;
    }

    public String getMetodologia() {
        return metodologia;
    }

    public void setMetodologia(String metodologia) {
        this.metodologia = metodologia;
    }

    public StatusProjeto getStatus() {
        return status;
    }

    public void setStatus(StatusProjeto status) {
        this.status = status;
    }
}

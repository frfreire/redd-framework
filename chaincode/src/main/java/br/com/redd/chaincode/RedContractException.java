package br.com.redd.chaincode;

import org.hyperledger.fabric.shim.ChaincodeException;

/**
 * Especializacao de {@link ChaincodeException} usada por todo o contrato
 * REDD+ para erros de regra de negocio (transicao de estado invalida,
 * autorizacao negada, conflito de interesse, idempotencia violada, etc.).
 *
 * <p>Mantem o padrao exigido pelo Gauntlet Loop: nunca lancar
 * {@code RuntimeException} generica para falha de regra de negocio -- sempre
 * uma {@link ChaincodeException} com mensagem especifica, que o peer Fabric
 * devolve ao cliente como motivo do ENDORSEMENT_POLICY_FAILURE/erro de
 * invocacao.</p>
 */
public class RedContractException extends ChaincodeException {

    public RedContractException(String message) {
        super(message);
    }

    public RedContractException(String message, String payload) {
        super(message, payload);
    }
}

package br.com.redd.apigateway.exception;

/**
 * Parametro de requisicao (query/path) invalido, detectado na borda antes de
 * chamar o peer. Mapeada para HTTP 400. A mensagem nunca deve repetir dado
 * recebido que possa ser pessoal.
 */
public class RequisicaoInvalidaException extends RuntimeException {

    public RequisicaoInvalidaException(String message) {
        super(message);
    }
}

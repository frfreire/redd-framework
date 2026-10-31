package br.com.redd.apigateway.exception;

/** Lancada quando o header HTTP {@code X-Org} contem um valor nao suportado. */
public class OrgInvalidaException extends RuntimeException {

    public OrgInvalidaException(String message) {
        super(message);
    }
}

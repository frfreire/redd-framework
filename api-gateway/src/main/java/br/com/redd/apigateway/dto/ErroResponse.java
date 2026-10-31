package br.com.redd.apigateway.dto;

/** Corpo de resposta de erro, retornado por todo {@code @ExceptionHandler} global. */
public record ErroResponse(String erro, String detalhe) {
}

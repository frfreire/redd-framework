package br.com.redd.apigateway.filter;

import br.com.redd.apigateway.dto.ErroResponse;
import br.com.redd.apigateway.exception.OrgInvalidaException;
import br.com.redd.apigateway.fabric.Organizacao;
import br.com.redd.apigateway.fabric.OrgContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Le o header HTTP {@code X-Org} (developer/verifier/marketplace, default
 * developer) e popula {@link OrgContext} para a duracao da requisicao.
 *
 * <p>Simplificacao de demo: ver Javadoc de
 * {@link br.com.redd.apigateway.fabric.FabricGatewayService}.</p>
 *
 * <p>Este filtro roda fora do dispatcher do Spring MVC (antes dele), logo
 * {@link br.com.redd.apigateway.exception.GlobalExceptionHandler} (que e um
 * {@code @RestControllerAdvice}, so intercepta excecoes lancadas dentro do
 * dispatcher) nunca veria uma {@link OrgInvalidaException} lancada aqui --
 * por isso ela e tratada e serializada manualmente neste proprio filtro,
 * mantendo o mesmo formato {@code {"erro":...,"detalhe":...}} e o status
 * 400 usado pelo restante da API.</p>
 */
@Component
public class OrgHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER_X_ORG = "X-Org";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            Organizacao org = Organizacao.fromHeader(request.getHeader(HEADER_X_ORG));
            OrgContext.set(org);
            filterChain.doFilter(request, response);
        } catch (OrgInvalidaException e) {
            response.resetBuffer();
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    new ErroResponse("Header X-Org invalido", e.getMessage())));
        } finally {
            OrgContext.clear();
        }
    }
}

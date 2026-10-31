package br.com.redd.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS liberado para qualquer origem/metodo/header em todos os endpoints
 * {@code /api/**}.
 *
 * <p>DECISAO DE ARQUITETURA — demo, nao producao: a web-ui estatica (ver
 * {@code web-ui/}) e servida por um container nginx totalmente separado da
 * API (origem diferente: porta/host do nginx vs porta/host desta API), e
 * chama a API diretamente do browser via {@code fetch} usando o
 * {@code X-Org} como header customizado para selecionar a identidade/MSP
 * (ver {@link br.com.redd.apigateway.filter.OrgHeaderFilter}). Sem esta
 * configuracao, o browser bloqueia a chamada ja na fase de preflight
 * (OPTIONS), pois a resposta nao traz {@code Access-Control-Allow-Origin}
 * -- confirmado via teste manual
 * ({@code curl -X OPTIONS .../api/v1/projetos -H "Origin: http://localhost:8090"}
 * retornava 403 "Invalid CORS request" antes desta classe existir).
 *
 * <p>Liberar qualquer origem ({@code allowedOriginPatterns("*")}) e seguro
 * aqui porque: (1) esta API nao usa cookies/sessao para autenticacao -- a
 * "autenticacao" de demo e o header {@code X-Org}, nunca credencial
 * ambiente-dependente de origem; (2) a autorizacao real de qualquer
 * operacao e sempre validada pelo chaincode via identidade MSP assinada
 * criptograficamente, nunca pelo CORS. Em producao, cada organizacao teria
 * seu proprio gateway com a origem do seu proprio frontend explicitamente
 * listada, nao um wildcard.</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}

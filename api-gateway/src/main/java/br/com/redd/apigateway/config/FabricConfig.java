package br.com.redd.apigateway.config;

import br.com.redd.apigateway.fabric.FabricGatewayService;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Habilita {@link FabricProperties} e garante shutdown gracioso das conexoes Fabric Gateway. */
@Configuration
@EnableConfigurationProperties(FabricProperties.class)
public class FabricConfig {

    private final FabricGatewayService fabricGatewayService;

    public FabricConfig(FabricGatewayService fabricGatewayService) {
        this.fabricGatewayService = fabricGatewayService;
    }

    @PreDestroy
    public void shutdown() {
        fabricGatewayService.shutdown();
    }
}

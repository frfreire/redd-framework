package br.com.redd.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API REST que expoe o chaincode {@code redd-creditos} (contrato
 * {@code RedContract}, canal {@code redchannel}) via Hyperledger Fabric
 * Gateway SDK ({@code org.hyperledger.fabric:fabric-gateway}, gRPC) --
 * nunca o SDK legado {@code fabric-sdk-java}.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}

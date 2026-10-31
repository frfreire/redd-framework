package br.com.redd.apigateway.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Paths de material criptografico (MSP + TLS) para cada uma das 3
 * organizacoes da rede Fabric (DeveloperMSP, VerifierMSP, MarketplaceMSP).
 *
 * <p>Convencao de nomes de variavel de ambiente (profile {@code docker}):
 * {@code FABRIC_ORG_<ORG>_<CAMPO>}, por exemplo
 * {@code FABRIC_ORG_DEVELOPER_CERT_PATH}, {@code FABRIC_ORG_DEVELOPER_KEY_PATH},
 * {@code FABRIC_ORG_DEVELOPER_TLS_CA_PATH}, {@code FABRIC_ORG_DEVELOPER_PEER_ENDPOINT},
 * {@code FABRIC_ORG_DEVELOPER_PEER_HOST_OVERRIDE}, {@code FABRIC_ORG_DEVELOPER_MSP_ID}
 * -- ver {@code application.yml} para o mapeamento completo e os defaults
 * usados fora de container (profile default/local, apontando para os paths
 * absolutos reais do host dentro de
 * {@code docker/fabric-network/organizations/...}).</p>
 */
@ConfigurationProperties(prefix = "fabric")
public class FabricProperties {

    @NestedConfigurationProperty
    private OrgCredentials developer = new OrgCredentials();

    @NestedConfigurationProperty
    private OrgCredentials verifier = new OrgCredentials();

    @NestedConfigurationProperty
    private OrgCredentials marketplace = new OrgCredentials();

    public OrgCredentials getDeveloper() {
        return developer;
    }

    public void setDeveloper(OrgCredentials developer) {
        this.developer = developer;
    }

    public OrgCredentials getVerifier() {
        return verifier;
    }

    public void setVerifier(OrgCredentials verifier) {
        this.verifier = verifier;
    }

    public OrgCredentials getMarketplace() {
        return marketplace;
    }

    public void setMarketplace(OrgCredentials marketplace) {
        this.marketplace = marketplace;
    }

    /** Credenciais e endpoint de peer de uma unica organizacao MSP. */
    public static class OrgCredentials {

        @NotBlank
        private String mspId;
        @NotBlank
        private String certPath;
        @NotBlank
        private String keyPath;
        @NotBlank
        private String tlsCaPath;
        @NotBlank
        private String peerEndpoint;
        @NotBlank
        private String peerHostOverride;

        public String getMspId() {
            return mspId;
        }

        public void setMspId(String mspId) {
            this.mspId = mspId;
        }

        public String getCertPath() {
            return certPath;
        }

        public void setCertPath(String certPath) {
            this.certPath = certPath;
        }

        public String getKeyPath() {
            return keyPath;
        }

        public void setKeyPath(String keyPath) {
            this.keyPath = keyPath;
        }

        public String getTlsCaPath() {
            return tlsCaPath;
        }

        public void setTlsCaPath(String tlsCaPath) {
            this.tlsCaPath = tlsCaPath;
        }

        public String getPeerEndpoint() {
            return peerEndpoint;
        }

        public void setPeerEndpoint(String peerEndpoint) {
            this.peerEndpoint = peerEndpoint;
        }

        public String getPeerHostOverride() {
            return peerHostOverride;
        }

        public void setPeerHostOverride(String peerHostOverride) {
            this.peerHostOverride = peerHostOverride;
        }
    }
}

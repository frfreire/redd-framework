package br.com.redd.apigateway.fabric;

import br.com.redd.apigateway.config.FabricProperties;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Identity;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabrica/cache de conexoes {@link Gateway} do fabric-gateway SDK, uma por
 * organizacao MSP (ver {@link Organizacao}).
 *
 * <p><strong>Simplificacao de demo:</strong> em um ambiente de producao real,
 * cada organizacao operaria seu proprio processo de API Gateway, com apenas
 * a identidade/credenciais daquela organizacao especifica carregadas em
 * memoria -- nunca as credenciais privadas de 3 organizacoes diferentes
 * dentro do mesmo processo. Aqui, por se tratar de uma demonstracao didatica
 * do ciclo de vida completo (proponente -> verificador -> mercado) atraves
 * de uma unica API REST, mantemos as 3 identidades carregadas e
 * selecionamos qual delas assina cada chamada via o header HTTP
 * {@code X-Org} (ver {@link br.com.redd.apigateway.filter.OrgHeaderFilter}
 * e {@link OrgContext}). Isso jamais deveria ser replicado em producao: a
 * posse da chave privada de uma organizacao deve ficar restrita ao processo
 * daquela organizacao.</p>
 *
 * <p>Cada {@link Gateway} mantem seu proprio {@link ManagedChannel} gRPC TLS
 * dedicado ao peer daquela organizacao (porta 7051/8051/9051), conforme
 * {@link FabricProperties}. Os canais sao abertos de forma lazy (na primeira
 * chamada) e reaproveitados (cache) para toda a vida da aplicacao, fechados
 * apenas no shutdown via {@link #shutdown()}.</p>
 */
@Service
public class FabricGatewayService {

    private static final Logger log = LoggerFactory.getLogger(FabricGatewayService.class);

    private static final String CANAL = "redchannel";
    private static final String CHAINCODE = "redd-creditos";
    private static final String CONTRATO = "RedContract";

    private final FabricProperties properties;
    private final Map<Organizacao, ManagedChannel> channels = new EnumMap<>(Organizacao.class);
    private final Map<Organizacao, Gateway> gateways = new ConcurrentHashMap<>();

    public FabricGatewayService(FabricProperties properties) {
        this.properties = properties;
    }

    /**
     * Devolve o {@link Contract} do RedContract no canal redchannel,
     * assinado com a identidade da organizacao informada. Conexao gRPC e
     * {@link Gateway} sao criados de forma lazy e cacheados por
     * organizacao.
     */
    public Contract getContract(Organizacao org) {
        Gateway gateway = gateways.computeIfAbsent(org, this::connect);
        return gateway.getNetwork(CANAL).getContract(CHAINCODE, CONTRATO);
    }

    private synchronized Gateway connect(Organizacao org) {
        Gateway existente = gateways.get(org);
        if (existente != null) {
            return existente;
        }

        FabricProperties.OrgCredentials creds = credenciaisDe(org);
        try {
            X509Certificate certificado = Identities.readX509Certificate(lerArquivo(creds.getCertPath()));
            PrivateKey chavePrivada = Identities.readPrivateKey(lerArquivo(creds.getKeyPath()));
            Identity identity = new X509Identity(creds.getMspId(), certificado);
            Signer signer = Signers.newPrivateKeySigner(chavePrivada);

            ManagedChannel channel = channels.computeIfAbsent(org, o -> criarCanalGrpc(creds));

            Gateway gateway = Gateway.newInstance()
                    .connection(channel)
                    .identity(identity)
                    .signer(signer)
                    .connect();

            log.info("Conexao Fabric Gateway estabelecida para org={} mspId={} peer={}",
                    org, creds.getMspId(), creds.getPeerEndpoint());
            return gateway;
        } catch (CertificateException | IOException | java.security.InvalidKeyException e) {
            throw new IllegalStateException(
                    "Falha ao carregar material criptografico da organizacao " + org
                            + " (certPath=" + creds.getCertPath() + ", keyPath=" + creds.getKeyPath() + ").", e);
        }
    }

    private ManagedChannel criarCanalGrpc(FabricProperties.OrgCredentials creds) {
        try {
            X509Certificate tlsCa = Identities.readX509Certificate(lerArquivo(creds.getTlsCaPath()));
            TlsChannelCredentials.Builder tlsBuilder = TlsChannelCredentials.newBuilder();
            try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(
                    Identities.toPemString(tlsCa).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                tlsBuilder.trustManager(in);
            }
            return Grpc.newChannelBuilder(creds.getPeerEndpoint(), tlsBuilder.build())
                    .overrideAuthority(creds.getPeerHostOverride())
                    .build();
        } catch (CertificateException | IOException e) {
            throw new UncheckedIOException(
                    "Falha ao configurar canal gRPC TLS para peer " + creds.getPeerEndpoint(), new IOException(e));
        }
    }

    private FabricProperties.OrgCredentials credenciaisDe(Organizacao org) {
        return switch (org) {
            case DEVELOPER -> properties.getDeveloper();
            case VERIFIER -> properties.getVerifier();
            case MARKETPLACE -> properties.getMarketplace();
        };
    }

    private Reader lerArquivo(String path) throws IOException {
        return new FileReader(path, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Fecha todas as conexoes Gateway/gRPC abertas. Chamado no shutdown da aplicacao. */
    public void shutdown() {
        gateways.values().forEach(Gateway::close);
        channels.values().forEach(ch -> {
            try {
                ch.shutdownNow();
            } catch (Exception e) {
                log.warn("Erro ao encerrar canal gRPC: {}", e.getMessage());
            }
        });
    }
}

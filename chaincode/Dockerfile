# Dockerfile do chaincode REDD+ empacotado como Chaincode-as-a-Service (CCaaS).
#
# Contexto: o builder Docker nativo legado do Fabric (que compila e empacota
# o chaincode dentro do proprio peer, via "docker build" interno disparado
# pelo lifecycle do Fabric) e incompativel com o Docker Engine deste host.
# A solucao e empacotar o chaincode como um container CCaaS independente
# (feature nativa do fabric-chaincode-shim >= 2.4), que o peer apenas
# invoca via rede (CHAINCODE_SERVER_ADDRESS) em vez de pedir ao Docker
# Engine para construir uma imagem on-the-fly.
#
# Nenhuma mudanca de codigo Java foi necessaria: a classe
# org.hyperledger.fabric.contract.ContractRouter (ja configurada como
# mainClass pelo maven-shade-plugin no pom.xml) decide em runtime, via
# ChaincodeBase.isServer(), se inicia um NettyChaincodeServer (modo CCaaS)
# ou o cliente stdin/stdout legado -- dependendo apenas da variavel de
# ambiente CHAINCODE_SERVER_ADDRESS estar ou nao definida no container.
# Essas variaveis de ambiente sao responsabilidade do docker-compose.yaml
# da rede Fabric (fora do escopo deste worker).

# ---- Stage 1: build -------------------------------------------------------
# Imagem via mirror.gcr.io (Docker Hub direto falha com reset de conexao TLS
# neste ambiente -- ver AGENTS.md). A imagem eclipse-temurin:21-jdk NAO
# inclui Maven, e instalar via apt-get falhou neste ambiente (sem acesso aos
# mirrors Ubuntu). Por isso usamos a imagem oficial maven:3.9-eclipse-temurin-21
# (tambem confirmada pullable via mirror.gcr.io), que ja traz Maven + JDK 21.
FROM mirror.gcr.io/library/maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copia primeiro o pom.xml para aproveitar cache de camada das dependencias
# quando apenas o codigo-fonte mudar.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true

COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- Stage 2: runtime ------------------------------------------------------
# Imagem final menor, apenas com JRE (nao precisa de JDK/compilador em
# producao). Confirmado via "docker pull" real que
# mirror.gcr.io/library/eclipse-temurin:21-jre existe e e pullable.
FROM mirror.gcr.io/library/eclipse-temurin:21-jre

WORKDIR /app

# JAR shaded (uber-jar) gerado pelo maven-shade-plugin, conforme
# <finalName>chaincode-java</finalName> no pom.xml.
COPY --from=build /build/target/chaincode-java.jar /app/chaincode.jar

# Porta arbitraria escolhida para o servidor CCaaS (NettyChaincodeServer).
# Deve corresponder ao valor configurado em CHAINCODE_SERVER_ADDRESS pelo
# container que consome esta imagem (docker-compose.yaml da rede Fabric).
EXPOSE 9999

# O main() de ContractRouter (classe configurada no manifest via
# maven-shade-plugin) decide sozinho, em runtime, se inicia o servidor
# CCaaS (NettyChaincodeServer) ou o cliente stdin/stdout legado, com base
# apenas nas variaveis de ambiente injetadas no container:
#   CHAINCODE_SERVER_ADDRESS  (ex.: 0.0.0.0:9999)  -> ativa modo servidor
#   CORE_CHAINCODE_ID_NAME    (formato label:hash do pacote instalado)
#   CORE_PEER_LOCALMSPID
#   CORE_PEER_TLS_ENABLED / CORE_PEER_TLS_ROOTCERT_FILE (se TLS habilitado)
ENTRYPOINT ["java", "-jar", "/app/chaincode.jar"]

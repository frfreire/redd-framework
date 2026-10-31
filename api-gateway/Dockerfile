# Dockerfile da API Gateway REDD+ (Spring Boot + Hyperledger Fabric Gateway SDK).
#
# Mesma estrategia de registry do chaincode (ver chaincode-java/Dockerfile e
# AGENTS.md): Docker Hub direto falha com reset de conexao TLS neste
# ambiente, entao todas as imagens Hyperledger/Temurin usadas aqui vem de
# mirror.gcr.io (confirmado alcancavel).

# ---- Stage 1: build ---------------------------------------------------
# Mesma imagem maven+JDK 21 usada pelo chaincode-java (ja confirmada
# pullable via mirror.gcr.io).
FROM mirror.gcr.io/library/maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copia primeiro o pom.xml para aproveitar cache de camada das dependencias
# quando apenas o codigo-fonte mudar.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true

COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- Stage 2: runtime ---------------------------------------------------
# Imagem final menor, apenas com JRE -- nunca o JDK completo em producao.
FROM mirror.gcr.io/library/eclipse-temurin:21-jre

WORKDIR /app

# Jar executavel gerado pelo spring-boot-maven-plugin (<finalName>api-gateway</finalName>).
COPY --from=build /build/target/api-gateway.jar /app/api-gateway.jar

# Paths esperados (volume de credenciais Fabric montado pelo
# docker-compose da rede -- responsabilidade de outro agente). O profile
# "docker" do application.yml le estes paths via variaveis de ambiente
# FABRIC_ORG_<ORG>_*_PATH, com defaults apontando para /crypto/... .
VOLUME ["/crypto"]

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/api-gateway.jar", "--spring.profiles.active=docker"]

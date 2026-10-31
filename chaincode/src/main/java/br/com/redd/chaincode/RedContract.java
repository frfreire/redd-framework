package br.com.redd.chaincode;

import br.com.redd.chaincode.model.CreditoCarbono;
import br.com.redd.chaincode.model.PaginaCreditos;
import br.com.redd.chaincode.model.PaginaHistorico;
import br.com.redd.chaincode.model.ProjetoREDD;
import br.com.redd.chaincode.model.RelatorioMRV;
import br.com.redd.chaincode.model.StatusCredito;
import br.com.redd.chaincode.model.StatusProjeto;
import br.com.redd.chaincode.model.StatusRelatorioMRV;
import br.com.redd.chaincode.model.TransferenciaHistorico;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contact;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Info;
import org.hyperledger.fabric.contract.annotation.License;
import org.hyperledger.fabric.contract.annotation.Transaction;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ledger.CompositeKey;
import org.hyperledger.fabric.shim.ledger.KeyModification;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIterator;
import org.hyperledger.fabric.shim.ledger.QueryResultsIteratorWithMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Contrato inteligente REDD+: registro de projeto, submissao de MRV,
 * verificacao independente, emissao de credito, transferencia e
 * aposentadoria (retirement) de creditos de carbono.
 *
 * <p>Papeis mapeados estritamente por MSP ID (ver
 * {@code ctx.getClientIdentity().getMSPID()}, nunca por campo vindo do
 * payload do cliente):</p>
 * <ul>
 *   <li>{@code DeveloperMSP} -- proponente de projetos ("developer").</li>
 *   <li>{@code VerifierMSP} -- verificador independente de MRV.</li>
 *   <li>{@code MarketplaceMSP} -- participante de mercado (compra, detem e
 *       aposenta creditos).</li>
 * </ul>
 */
@Contract(
        name = "RedContract",
        info = @Info(
                title = "REDD+ Carbon Credit Contract",
                description = "Ciclo de vida de projetos REDD+ e creditos de carbono",
                version = "1.0.0",
                license = @License(name = "Apache-2.0"),
                contact = @Contact(email = "redd-demo@example.org", name = "REDD+ Blockchain Demo")
        )
)
@Default
public class RedContract implements ContractInterface {

    private static final String MSP_DEVELOPER = "DeveloperMSP";
    private static final String MSP_VERIFIER = "VerifierMSP";

    private static final String PREFIXO_PROJETO = "PROJETO";
    private static final String PREFIXO_RELATORIO = "RELATORIO_MRV";
    private static final String PREFIXO_CREDITO = "CREDITO";

    // Limites tecnicos de tamanho (em code points) para campos de texto da
    // aposentadoria; evitam estado inflado e chave de ledger abusiva.
    private static final int MAX_ID = 128;
    private static final int MAX_MOTIVO = 256;

    /**
     * Limite maximo de pageSize das consultas paginadas. A especificacao nao
     * fixa o valor: 100 e conservador (a resposta cabe com folga no limite de
     * mensagem do gRPC). Valor definitivo: a definir com o humano (ADR).
     */
    static final int PAGE_SIZE_MAXIMO = 100;
    /** Bookmark do CouchDB e opaco e curto; teto evita argumento abusivo. */
    static final int BOOKMARK_TAMANHO_MAXIMO = 2048;

    /**
     * Formato do beneficiario (INV-08/INV-14), imposto por estrutura e nao por
     * lista de padroes recusados: {@code "sha256:"} seguido de EXATAMENTE 64
     * caracteres hexadecimais em minusculas (digest SHA-256 de 32 bytes). Forma
     * unica e canonica: sem maiusculas, sem prefixo ausente, sem espaco. O
     * digest e calculado FORA do ledger, pelo chamador/API, sobre a identidade
     * real e com sal a cargo do chamador; o contrato nunca ve a identidade.
     */
    static final String SAL_REFERENCIA_AUTOR = "redd.aposentadoPorId.v1";
    static final String BENEFICIARIO_PREFIXO = "sha256:";
    static final int BENEFICIARIO_HEX_TAMANHO = 64;
    static final int BENEFICIARIO_TAMANHO = BENEFICIARIO_PREFIXO.length() + BENEFICIARIO_HEX_TAMANHO;
    static final String BENEFICIARIO_FORMATO_DESCRICAO =
            BENEFICIARIO_PREFIXO + "<" + BENEFICIARIO_HEX_TAMANHO + " caracteres hexadecimais minusculos [0-9a-f]>";

    /**
     * Sequencia de digitos a partir da qual um valor parece identificador
     * pessoal direto: CPF tem 11 digitos, CNPJ 14, telefone 8 ou mais. Vale
     * so para o motivo (texto livre), para cada palavra feita so de digitos.
     */
    private static final int MIN_DIGITOS_IDENTIFICADOR_DIRETO = 8;

    /**
     * Formas pontuadas de CPF, CNPJ e telefone no texto livre do motivo.
     * Telefone so casa com DDD entre parenteses, prefixo +pais ou celular de
     * 5 digitos iniciado em 9: {@code NNNN-NNNN} solto NAO casa, para nao
     * recusar faixa de anos legitima ("2024-2026"). Sao padroes mecanicos:
     * nao detectam todo dado pessoal.
     */
    private static final java.util.regex.Pattern PADRAO_CPF_PONTUADO =
            java.util.regex.Pattern.compile("(?<!\\d)\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}(?!\\d)");
    private static final java.util.regex.Pattern PADRAO_CNPJ_PONTUADO =
            java.util.regex.Pattern.compile("(?<!\\d)\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}(?!\\d)");
    private static final java.util.regex.Pattern PADRAO_TELEFONE_PONTUADO =
            java.util.regex.Pattern.compile("(?<!\\d)(?:\\(\\d{2}\\)\\s?\\d{4,5}-\\d{4}"
                    + "|\\+\\d{1,3}[\\s-]?\\d{2}[\\s-]?\\d{4,5}-\\d{4}|9\\d{4}-\\d{4})(?!\\d)");

    private final ObjectMapper mapper = buildObjectMapper();

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // INV-11/R1: fracao em campo inteiro (toneladas) nao e truncada em silencio.
        m.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        return m;
    }

    // ------------------------------------------------------------------
    // 1. registrarProjeto
    // ------------------------------------------------------------------

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ProjetoREDD registrarProjeto(final Context ctx, final String id, final String nome,
                                         final double areaHectares, final String metodologia) {
        String mspId = ctx.getClientIdentity().getMSPID();
        if (!MSP_DEVELOPER.equals(mspId)) {
            throw new RedContractException(
                    "Apenas identidades da organizacao proponente (" + MSP_DEVELOPER
                            + ") podem registrar projetos. MSP do chamador: " + mspId);
        }

        ChaincodeStub stub = ctx.getStub();
        String key = chaveProjeto(id);
        if (!isBlank(stub.getStringState(key))) {
            throw new RedContractException("Projeto com id '" + id + "' ja existe.");
        }
        if (isBlank(id) || isBlank(nome) || isBlank(metodologia)) {
            throw new RedContractException("id, nome e metodologia sao obrigatorios para registrarProjeto.");
        }
        if (areaHectares <= 0) {
            throw new RedContractException("areaHectares deve ser maior que zero.");
        }

        ProjetoREDD projeto = new ProjetoREDD(id, nome, areaHectares, mspId, metodologia, StatusProjeto.REGISTRADO);
        putState(stub, key, projeto);
        emitirEvento(stub, "ProjetoRegistrado", projeto);
        return projeto;
    }

    // ------------------------------------------------------------------
    // 2. submeterRelatorioMRV
    // ------------------------------------------------------------------

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public RelatorioMRV submeterRelatorioMRV(final Context ctx, final String idRelatorio, final String idProjeto,
                                              final String periodoInicioIso, final String periodoFimIso,
                                              final long toneladasCO2eEstimadas, final String hashEvidencia) {
        String mspId = ctx.getClientIdentity().getMSPID();
        if (!MSP_DEVELOPER.equals(mspId)) {
            throw new RedContractException(
                    "Apenas identidades da organizacao proponente (" + MSP_DEVELOPER
                            + ") podem submeter relatorios MRV. MSP do chamador: " + mspId);
        }

        ChaincodeStub stub = ctx.getStub();
        ProjetoREDD projeto = lerProjetoOuFalhar(stub, idProjeto);

        if (!mspId.equals(projeto.getProponenteMspId())) {
            throw new RedContractException(
                    "Apenas o proponente original do projeto '" + idProjeto + "' pode submeter MRV para ele.");
        }
        if (projeto.getStatus() != StatusProjeto.REGISTRADO && projeto.getStatus() != StatusProjeto.EM_MONITORAMENTO) {
            throw new RedContractException(
                    "Projeto '" + idProjeto + "' esta em status " + projeto.getStatus()
                            + "; MRV so pode ser submetido para projetos REGISTRADO ou EM_MONITORAMENTO.");
        }

        String key = chaveRelatorio(idRelatorio);
        if (!isBlank(stub.getStringState(key))) {
            throw new RedContractException("Relatorio MRV com id '" + idRelatorio + "' ja existe.");
        }
        if (isBlank(hashEvidencia)) {
            throw new RedContractException("hashEvidencia e obrigatorio (hash do documento, nao o blob).");
        }
        Toneladas.exigirPositivo(toneladasCO2eEstimadas, "toneladasCO2eEstimadas");

        Instant periodoInicio = parseInstant(periodoInicioIso, "periodoInicio");
        Instant periodoFim = parseInstant(periodoFimIso, "periodoFim");
        // INV-04: intervalo [inicio, fim] com inicio <= fim.
        if (periodoFim.isBefore(periodoInicio)) {
            throw new RedContractException("periodoFim (" + periodoFim + ") nao pode ser anterior a periodoInicio ("
                    + periodoInicio + ").");
        }

        // INV-04 / ADR-0005 (P1, Q2): rejeita se o periodo cruza, em intervalo
        // FECHADO, o de qualquer relatorio ja existente do mesmo projeto, em
        // qualquer status (REJEITADO continua ocupando o periodo). Todas as
        // validacoes rodam antes da primeira escrita.
        int totalRelatorios = exigirPeriodoLivre(stub, idProjeto, idRelatorio, periodoInicio, periodoFim);

        RelatorioMRV relatorio = new RelatorioMRV(idRelatorio, idProjeto, periodoInicio, periodoFim,
                toneladasCO2eEstimadas, hashEvidencia, StatusRelatorioMRV.SUBMETIDO);
        putState(stub, key, relatorio);
        registrarPeriodo(stub, idProjeto, idRelatorio, periodoInicio, periodoFim, totalRelatorios + 1);

        if (projeto.getStatus() == StatusProjeto.REGISTRADO) {
            projeto.setStatus(StatusProjeto.EM_MONITORAMENTO);
            putState(stub, chaveProjeto(idProjeto), projeto);
        }

        emitirEvento(stub, "RelatorioMRVSubmetido", relatorio);
        return relatorio;
    }

    // ------------------------------------------------------------------
    // 3. verificarRelatorioMRV
    // ------------------------------------------------------------------

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public RelatorioMRV verificarRelatorioMRV(final Context ctx, final String idRelatorio, final boolean aprovado,
                                               final long toneladasVerificadas, final String justificativa) {
        String mspId = ctx.getClientIdentity().getMSPID();
        if (!MSP_VERIFIER.equals(mspId)) {
            throw new RedContractException(
                    "Apenas identidades da organizacao verificadora (" + MSP_VERIFIER
                            + ") podem verificar relatorios MRV. MSP do chamador: " + mspId);
        }

        ChaincodeStub stub = ctx.getStub();
        RelatorioMRV relatorio = lerRelatorioOuFalhar(stub, idRelatorio);
        ProjetoREDD projeto = lerProjetoOuFalhar(stub, relatorio.getIdProjeto());

        // Barra 3 do Gauntlet Loop: separacao de papeis entre organizacoes
        // (criterio "separacao-de-papeis-entre-organizacoes").
        //
        // Defesa em profundidade: o gate de papel acima (apenas MSP_VERIFIER)
        // garante que o chamador tem o PAPEL correto, mas nao garante, por si
        // so, que o chamador nao e a MESMA organizacao que registrou o
        // projeto como proponente. A fonte de verdade sobre quem e o
        // proponente e o dado persistido no ledger
        // (projeto.getProponenteMspId()), nao uma inferencia estatica sobre a
        // topologia atual de papeis/MSPs do contrato. Essa topologia pode
        // mudar no futuro (novas organizacoes, migracao de papeis, correcao
        // manual de dados), e uma checagem dinamica contra o dado real e
        // estritamente mais segura do que confiar apenas no fluxo de codigo.
        if (mspId.equals(projeto.getProponenteMspId())) {
            throw new RedContractException(
                    "Conflito de interesse: o MSP '" + mspId
                            + "' e o mesmo proponente do projeto '" + projeto.getId()
                            + "' e nao pode verificar seu proprio relatorio MRV.");
        }

        if (relatorio.getStatus() != StatusRelatorioMRV.SUBMETIDO) {
            throw new RedContractException(
                    "Relatorio MRV '" + idRelatorio + "' esta em status " + relatorio.getStatus()
                            + "; apenas relatorios SUBMETIDO podem ser verificados.");
        }
        if (isBlank(justificativa)) {
            throw new RedContractException("justificativa e obrigatoria para aprovar ou rejeitar um relatorio MRV.");
        }

        // INV-12 / ADR-0002 (F): na aprovacao o verificador informa a quantidade
        // atestada (> 0, inteiro em kgCO2e); nao ha teto pelo estimado. Na
        // rejeicao nao ha quantidade verificada: o argumento deve ser 0.
        if (aprovado) {
            Toneladas.exigirPositivo(toneladasVerificadas, "toneladasVerificadas");
            relatorio.setToneladasVerificadas(toneladasVerificadas);
        } else if (toneladasVerificadas != 0) {
            throw new RedContractException(
                    "toneladasVerificadas deve ser 0 ao rejeitar um relatorio MRV (recebido: "
                            + toneladasVerificadas + ").");
        }

        // INV-03: a verificacao deixa o acumulado de emissao zerado.
        relatorio.setToneladasEmitidas(0L);
        relatorio.setStatus(aprovado ? StatusRelatorioMRV.VERIFICADO : StatusRelatorioMRV.REJEITADO);
        relatorio.setVerificadoPorMspId(mspId);
        relatorio.setJustificativaVerificacao(justificativa);
        putState(stub, chaveRelatorio(idRelatorio), relatorio);

        emitirEvento(stub, "RelatorioMRVVerificado", relatorio);
        return relatorio;
    }

    // ------------------------------------------------------------------
    // 4. emitirCredito
    // ------------------------------------------------------------------

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public CreditoCarbono emitirCredito(final Context ctx, final String idCredito, final String idRelatorio,
                                         final long toneladasCO2e) {
        String mspId = ctx.getClientIdentity().getMSPID();
        if (!MSP_DEVELOPER.equals(mspId)) {
            throw new RedContractException(
                    "Apenas identidades da organizacao proponente (" + MSP_DEVELOPER
                            + ") podem emitir creditos. MSP do chamador: " + mspId);
        }

        ChaincodeStub stub = ctx.getStub();
        RelatorioMRV relatorio = lerRelatorioOuFalhar(stub, idRelatorio);
        ProjetoREDD projeto = lerProjetoOuFalhar(stub, relatorio.getIdProjeto());

        if (!mspId.equals(projeto.getProponenteMspId())) {
            throw new RedContractException(
                    "Apenas o proponente original do projeto '" + projeto.getId()
                            + "' pode emitir credito a partir do seu relatorio MRV.");
        }

        // Barra 1: nunca emitir sem relatorio verificado -- essa e a trava
        // "nunca libera sem consenso" traduzida para codigo.
        if (relatorio.getStatus() != StatusRelatorioMRV.VERIFICADO) {
            throw new RedContractException(
                    "Relatorio MRV '" + idRelatorio + "' esta em status " + relatorio.getStatus()
                            + "; credito so pode ser emitido a partir de relatorio VERIFICADO.");
        }
        if (isBlank(idCredito)) {
            throw new RedContractException("idCredito e obrigatorio para emitirCredito.");
        }
        Toneladas.exigirPositivo(toneladasCO2e, "toneladasCO2e");

        // INV-12: a emissao usa SOMENTE toneladasVerificadas, nunca o estimado.
        // INV-03: emitidas + novo <= verificadas. Todas as validacoes rodam antes
        // de qualquer escrita. A leitura do relatorio e a escrita do acumulado
        // fazem parte do mesmo read/write set; o peer invalida no commit a
        // segunda de duas emissoes concorrentes sobre o mesmo relatorio
        // (MVCC), entao ambas nao passam. Granularidade E (ADR-0002): cada
        // emissao e um credito indivisivel; varias emissoes parciais sao
        // permitidas ate o verificado.
        long verificadas = relatorio.getToneladasVerificadas();
        long emitidas = relatorio.getToneladasEmitidas();
        long novoAcumulado = Toneladas.somar(emitidas, toneladasCO2e);
        if (novoAcumulado > verificadas) {
            throw new RedContractException(
                    "Emissao excede as toneladas verificadas do relatorio '" + idRelatorio
                            + "': emitidas (" + emitidas + ") + solicitado (" + toneladasCO2e
                            + ") = " + novoAcumulado + " kgCO2e > verificadas (" + verificadas
                            + "). Saldo emissivel: " + Math.max(0L, verificadas - emitidas) + " kgCO2e.");
        }

        String key = chaveCredito(idCredito);
        if (!isBlank(stub.getStringState(key))) {
            throw new RedContractException("Credito com id '" + idCredito + "' ja existe.");
        }

        CreditoCarbono credito = new CreditoCarbono(idCredito, projeto.getId(), idRelatorio, toneladasCO2e,
                mspId, StatusCredito.EMITIDO);
        credito.getHistorico().add(new TransferenciaHistorico(null, mspId, ctx.getStub().getTxTimestamp(), "EMISSAO"));
        relatorio.setToneladasEmitidas(novoAcumulado);
        // Acumulado do relatorio e credito entram no mesmo write set.
        putState(stub, chaveRelatorio(idRelatorio), relatorio);
        putState(stub, key, credito);

        emitirEvento(stub, "CreditoEmitido", credito);
        return credito;
    }

    // ------------------------------------------------------------------
    // 5. transferirCredito
    // ------------------------------------------------------------------

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public CreditoCarbono transferirCredito(final Context ctx, final String idCredito, final String novoProprietarioMspId) {
        String mspId = ctx.getClientIdentity().getMSPID();
        ChaincodeStub stub = ctx.getStub();
        CreditoCarbono credito = lerCreditoOuFalhar(stub, idCredito);

        if (credito.getStatus() == StatusCredito.APOSENTADO) {
            throw new RedContractException(
                    "Credito '" + idCredito + "' esta APOSENTADO e nao pode mais ser transferido.");
        }

        // Barra 2: identidade de quem chama nunca vem do payload --
        // comparamos o MSP de quem assina a transacao com o proprietario
        // atual gravado no ledger, nunca um campo "remetente" do cliente.
        if (!mspId.equals(credito.getProprietarioAtual())) {
            throw new RedContractException(
                    "Apenas o proprietario atual do credito pode transferi-lo. Proprietario atual: "
                            + credito.getProprietarioAtual() + "; chamador: " + mspId);
        }
        if (isBlank(novoProprietarioMspId)) {
            throw new RedContractException("novoProprietarioMspId e obrigatorio.");
        }
        if (novoProprietarioMspId.equals(mspId)) {
            throw new RedContractException("novoProprietarioMspId deve ser diferente do proprietario atual.");
        }

        String proprietarioAnterior = credito.getProprietarioAtual();
        credito.setProprietarioAtual(novoProprietarioMspId);
        credito.setStatus(StatusCredito.TRANSFERIDO);
        credito.getHistorico().add(new TransferenciaHistorico(
                proprietarioAnterior, novoProprietarioMspId, ctx.getStub().getTxTimestamp(), "TRANSFERENCIA"));
        putState(stub, chaveCredito(idCredito), credito);

        emitirEvento(stub, "CreditoTransferido", credito);
        return credito;
    }

    // ------------------------------------------------------------------
    // 6. aposentarCredito
    // ------------------------------------------------------------------

    /**
     * Aposenta o credito INTEIRO (ADR-0002, lote indivisivel; INV-14).
     *
     * <p>INV-05: APOSENTADO e terminal; nenhuma funcao tira o credito desse
     * estado e a segunda chamada falha. INV-06: so o proprietario atual
     * gravado no ledger aposenta. INV-10: autor (MSP e id) vem so de
     * {@code ctx.getClientIdentity()}; o instante vem de
     * {@code stub.getTxTimestamp()}. Todas as validacoes rodam antes da
     * primeira escrita (falha nao altera estado).</p>
     *
     * <p>{@code beneficiario} e um digest, nunca identidade: o contrato SO
     * aceita {@value #BENEFICIARIO_PREFIXO} + 64 hex minusculos (ver
     * {@link #BENEFICIARIO_FORMATO_DESCRICAO}). Opacidade por construcao: o
     * ledger e replicado entre organizacoes e nao pode ser apagado (INV-08), e
     * nenhum rotulo livre (nem "ORG-123:ref-9") e aceito. O contrato nao prova
     * que o digest foi calculado sobre a identidade real nem que o sal e
     * secreto: isso e do chamador (api-gateway). O motivo e texto simples
     * limitado e passa so pela recusa de padroes de identificador direto.</p>
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public CreditoCarbono aposentarCredito(final Context ctx, final String idCredito, final String motivo,
                                           final String beneficiario) {
        String idLimpo = exigirTextoSimples(idCredito, "idCredito", MAX_ID);
        if (!idLimpo.equals(idCredito)) {
            throw new RedContractException("idCredito nao pode ter espacos nas pontas.");
        }
        String motivoLimpo = exigirTextoSimples(motivo, "motivo", MAX_MOTIVO);
        exigirSemIdentificadorDiretoNoTexto(motivoLimpo, "motivo");
        String beneficiarioLimpo = exigirBeneficiarioDigest(beneficiario, "beneficiario");

        String mspId = ctx.getClientIdentity().getMSPID();
        String autorId = ctx.getClientIdentity().getId();
        if (isBlank(mspId) || isBlank(autorId)) {
            throw new RedContractException("Identidade do invocador ausente (MSP e id sao obrigatorios).");
        }

        ChaincodeStub stub = ctx.getStub();
        CreditoCarbono credito = lerCreditoOuFalhar(stub, idLimpo);

        // INV-05: segunda tentativa de aposentar falha explicitamente.
        if (credito.getStatus() == StatusCredito.APOSENTADO) {
            throw new RedContractException(
                    "Credito '" + idLimpo + "' ja esta APOSENTADO (aposentadoria e irreversivel e nao pode ser repetida).");
        }
        // INV-06: a comparacao e com o proprietario gravado no ledger.
        if (!mspId.equals(credito.getProprietarioAtual())) {
            throw new RedContractException(
                    "Apenas o proprietario atual do credito pode aposenta-lo. Proprietario atual: "
                            + credito.getProprietarioAtual() + "; chamador: " + mspId);
        }

        Instant agora = stub.getTxTimestamp();
        credito.setStatus(StatusCredito.APOSENTADO);
        credito.setMotivoAposentadoria(motivoLimpo);
        credito.setBeneficiarioAposentadoria(beneficiarioLimpo);
        credito.setAposentadoPorMspId(mspId);
        credito.setAposentadoPorId(referenciaOpacaDoAutor(mspId, autorId));
        credito.setAposentadoEm(agora);
        credito.getHistorico().add(new TransferenciaHistorico(mspId, mspId, agora, "APOSENTADORIA"));
        putState(stub, chaveCredito(idLimpo), credito);

        emitirEvento(stub, "CreditoAposentado", credito);
        return credito;
    }

    // ------------------------------------------------------------------
    // 7. Funcoes de leitura
    // ------------------------------------------------------------------

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public ProjetoREDD consultarProjeto(final Context ctx, final String idProjeto) {
        return lerProjetoOuFalhar(ctx.getStub(), idProjeto);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public CreditoCarbono consultarCredito(final Context ctx, final String idCredito) {
        return lerCreditoOuFalhar(ctx.getStub(), idCredito);
    }

    /**
     * Lista todos os creditos de um dado projeto usando rich query do
     * CouchDB (selector por campo {@code idProjeto}), apoiada pelo indice
     * explicito em {@code META-INF/statedb/couchdb/indexes/indexIdProjeto.json}
     * -- nunca {@code getStateByRange("", "")} seguido de filtro em memoria
     * sobre todo o ledger.
     *
     * <p>O filtro de prefixo usa o campo reservado {@code _id} do CouchDB
     * (a chave do estado no ledger, ex.: {@code CREDITO_cred-001}), nao o
     * campo de aplicacao {@code id} serializado dentro do JSON do asset
     * (que guarda apenas o id de negocio, ex.: {@code cred-001}, sem
     * prefixo). Usar {@code id} aqui e um bug: o regex de prefixo
     * {@code ^CREDITO_} nunca casaria com o valor sem prefixo, fazendo a
     * query devolver sempre uma lista vazia mesmo com creditos existentes
     * para o projeto.</p>
     *
     * <p>Paginada (unica rich query do contrato): {@code pageSize} entre 1 e
     * {@link #PAGE_SIZE_MAXIMO} (fora disso falha, sem ajuste silencioso) e
     * {@code bookmark} vazio para a primeira pagina. Retorno: String JSON de
     * {@link PaginaCreditos} (campos em ordem fixa). String e usada porque o
     * {@code JSONTransactionSerializer} do shim nao resolve {@code List<T>}
     * (type erasure) e o payload inclui o bookmark.</p>
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String listarCreditosPorProjeto(final Context ctx, final String idProjeto,
                                           final int pageSize, final String bookmark) {
        String projeto = exigirTextoSimples(idProjeto, "idProjeto", MAX_ID);
        if (pageSize < 1 || pageSize > PAGE_SIZE_MAXIMO) {
            throw new RedContractException("pageSize deve estar entre 1 e " + PAGE_SIZE_MAXIMO
                    + " (recebido: " + pageSize + ").");
        }
        String marcador = bookmark == null ? "" : bookmark;
        if (marcador.length() > BOOKMARK_TAMANHO_MAXIMO) {
            throw new RedContractException("bookmark excede " + BOOKMARK_TAMANHO_MAXIMO + " caracteres.");
        }

        ChaincodeStub stub = ctx.getStub();
        List<CreditoCarbono> itens = new ArrayList<>();
        String proximo;
        try (QueryResultsIteratorWithMetadata<KeyValue> results =
                     stub.getQueryResultWithPagination(seletorCreditosPorProjeto(projeto), pageSize, marcador)) {
            for (KeyValue kv : results) {
                itens.add(parse(kv.getStringValue(), CreditoCarbono.class));
            }
            proximo = results.getMetadata().getBookmark();
        }
        return toJson(new PaginaCreditos(proximo, itens.toArray(new CreditoCarbono[0]), pageSize));
    }

    /**
     * Seletor montado com Jackson (escape correto de qualquer caractere do
     * idProjeto; ordem de campos de insercao) e ordenado por
     * {@code idProjeto, _id}: a ordem total estavel que torna o bookmark
     * reprodutivel. {@code use_index} fixa o indice
     * {@code indexIdProjeto} ({@code ["idProjeto","_id"]}), que cobre o
     * igualdade em idProjeto, o regex de prefixo em _id e o sort; o filtro
     * {@code status $exists} nao esta no indice e e avaliado so sobre as
     * linhas ja limitadas pelo projeto.
     */
    private String seletorCreditosPorProjeto(String idProjeto) {
        ObjectNode raiz = mapper.createObjectNode();
        ObjectNode selector = raiz.putObject("selector");
        selector.put("idProjeto", idProjeto);
        selector.putObject("status").put("$exists", true);
        selector.putObject("_id").put("$regex", "^" + PREFIXO_CREDITO + "_");
        raiz.putArray("sort")
                .add(mapper.createObjectNode().put("idProjeto", "asc"))
                .add(mapper.createObjectNode().put("_id", "asc"));
        raiz.putArray("use_index").add("indexIdProjetoDoc").add("indexIdProjeto");
        return raiz.toString();
    }

    /**
     * Historico completo de versoes de um credito no ledger (nao o campo
     * {@code historico} do asset, mas as mutacoes de estado reais
     * registradas pelo peer via {@code getHistoryForKey}).
     *
     * <p>Paginado por deslocamento, porque {@code getHistoryForKey} nao tem
     * bookmark: {@code offset >= 0} conta versoes validas (sem delecao nem valor
     * vazio) na ordem do ledger; {@code pageSize} entre 1 e
     * {@link #PAGE_SIZE_MAXIMO} (fora disso falha, sem ajuste silencioso; o
     * valor definitivo do maximo segue a definir com o humano, como nas demais
     * consultas). O iterador e percorrido uma unica vez e a leitura para ao
     * encontrar uma versao alem da pagina (so o necessario para dizer
     * {@code temMais}); o iterador e sempre fechado. Offset alem do fim devolve
     * pagina vazia com {@code temMais=false}.</p>
     *
     * <p>Retorno: String JSON de {@link PaginaHistorico} (campos em ordem
     * fixa), pelo mesmo motivo documentado em
     * {@link #listarCreditosPorProjeto}. Assinatura mudou: antes
     * {@code (idCredito)} devolvia o array completo; agora
     * {@code (idCredito, offset, pageSize)}.</p>
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String historicoCredito(final Context ctx, final String idCredito,
                                   final int offset, final int pageSize) {
        if (offset < 0) {
            throw new RedContractException("offset deve ser maior ou igual a 0 (recebido: " + offset + ").");
        }
        if (pageSize < 1 || pageSize > PAGE_SIZE_MAXIMO) {
            throw new RedContractException("pageSize deve estar entre 1 e " + PAGE_SIZE_MAXIMO
                    + " (recebido: " + pageSize + ").");
        }

        ChaincodeStub stub = ctx.getStub();
        List<TransferenciaHistorico> eventos = new ArrayList<>();
        boolean temMais = false;
        long validas = 0;

        try (QueryResultsIterator<KeyModification> historico = stub.getHistoryForKey(chaveCredito(idCredito))) {
            Iterator<KeyModification> it = historico.iterator();
            while (it.hasNext()) {
                KeyModification mod = it.next();
                if (mod.isDeleted() || isBlank(mod.getStringValue())) {
                    continue;
                }
                long posicao = validas++;
                if (posicao < offset) {
                    continue;
                }
                if (eventos.size() == pageSize) {
                    temMais = true;
                    break;
                }
                CreditoCarbono versao = parse(mod.getStringValue(), CreditoCarbono.class);
                eventos.add(new TransferenciaHistorico(
                        null, versao.getProprietarioAtual(), mod.getTimestamp(), versao.getStatus().name()));
            }
        }
        return toJson(new PaginaHistorico(eventos.toArray(new TransferenciaHistorico[0]), offset, pageSize, temMais));
    }

    // ------------------------------------------------------------------
    // Helpers internos
    // ------------------------------------------------------------------

    private String chaveProjeto(String idProjeto) {
        return PREFIXO_PROJETO + "_" + idProjeto;
    }

    private String chaveRelatorio(String idRelatorio) {
        return PREFIXO_RELATORIO + "_" + idRelatorio;
    }

    private String chaveCredito(String idCredito) {
        return PREFIXO_CREDITO + "_" + idCredito;
    }

    // Indice de periodos por projeto (INV-04). Chaves compostas:
    //   PERIODO_MRV   (idProjeto, idRelatorio) -> {idRelatorio, inicio, fim}
    //   CONTROLE_PERIODOS_MRV (idProjeto)      -> {totalRelatorios}
    // A entrada de periodo nunca e apagada nem alterada depois (nem na
    // verificacao), logo relatorio REJEITADO continua ocupando o periodo (Q2).
    private static final String TIPO_PERIODO = "PERIODO_MRV";
    private static final String TIPO_CONTROLE_PERIODOS = "CONTROLE_PERIODOS_MRV";

    private static String chavePeriodo(String idProjeto, String idRelatorio) {
        return new CompositeKey(TIPO_PERIODO, idProjeto, idRelatorio).toString();
    }

    private static String chaveControlePeriodos(String idProjeto) {
        return new CompositeKey(TIPO_CONTROLE_PERIODOS, idProjeto).toString();
    }

    /**
     * Verifica que [inicio, fim] nao cruza nenhum periodo ja registrado para o
     * projeto e devolve o total atual de relatorios do projeto.
     *
     * <p>Busca: {@code getStateByPartialCompositeKey(PERIODO_MRV, idProjeto)},
     * limitada ao projeto (nao varre o ledger). Comparacao em {@link Instant}
     * vindos dos argumentos, sem relogio local: cruza quando
     * {@code inicioNovo <= fimExistente && inicioExistente <= fimNovo}
     * (intervalo fechado; borda igual conta, ADR-0005 P1).</p>
     *
     * <p>Concorrencia (leitura fantasma): o range da consulta entra no read set
     * e o peer o revalida no commit; alem disso a chave de controle
     * {@code CONTROLE_PERIODOS_MRV(idProjeto)} e LIDA aqui e ESCRITA em
     * {@link #registrarPeriodo} na mesma transacao. Dois submeterRelatorioMRV do
     * mesmo projeto simulados sobre o mesmo estado leem a mesma versao dessa
     * chave e ambos a escrevem; o segundo a ser ordenado no bloco e invalidado
     * por conflito de versao (MVCC), mesmo que os periodos sejam disjuntos do
     * ponto de vista de cada um. Projetos diferentes usam chaves de controle
     * diferentes e nao conflitam.</p>
     */
    private int exigirPeriodoLivre(ChaincodeStub stub, String idProjeto, String idRelatorio,
                                   Instant inicio, Instant fim) {
        // Chave composta nao aceita U+0000 nem U+10FFFF nos atributos.
        if (temCaractereReservado(idProjeto) || temCaractereReservado(idRelatorio)) {
            throw new RedContractException("idProjeto/idRelatorio contem caractere reservado de chave composta.");
        }
        // Leitura da chave de controle: entra no read set mesmo sem relatorios.
        String controleJson = stub.getStringState(chaveControlePeriodos(idProjeto));
        int total = 0;
        if (!isBlank(controleJson)) {
            total = lerJson(controleJson).path("totalRelatorios").asInt(0);
        }

        String prefixo = new CompositeKey(TIPO_PERIODO, idProjeto).toString();
        try (QueryResultsIterator<KeyValue> results = stub.getStateByPartialCompositeKey(prefixo)) {
            for (KeyValue kv : results) {
                JsonNode entrada = lerJson(kv.getStringValue());
                Instant inicioExistente = Instant.parse(entrada.path("inicio").asText());
                Instant fimExistente = Instant.parse(entrada.path("fim").asText());
                if (!inicio.isAfter(fimExistente) && !inicioExistente.isAfter(fim)) {
                    throw new RedContractException("Periodo [" + inicio + ", " + fim
                            + "] sobrepoe o periodo [" + inicioExistente + ", " + fimExistente
                            + "] do relatorio '" + entrada.path("idRelatorio").asText()
                            + "' do projeto '" + idProjeto + "' (intervalo fechado; borda igual conta; "
                            + "relatorio REJEITADO continua ocupando o periodo). Relatorio recusado: '"
                            + idRelatorio + "'.");
                }
            }
        }
        return total;
    }

    private void registrarPeriodo(ChaincodeStub stub, String idProjeto, String idRelatorio,
                                  Instant inicio, Instant fim, int novoTotal) {
        ObjectNode entrada = mapper.createObjectNode();
        entrada.put("idRelatorio", idRelatorio);
        entrada.put("inicio", inicio.toString());
        entrada.put("fim", fim.toString());
        stub.putStringState(chavePeriodo(idProjeto, idRelatorio), entrada.toString());

        ObjectNode controle = mapper.createObjectNode();
        controle.put("totalRelatorios", novoTotal);
        stub.putStringState(chaveControlePeriodos(idProjeto), controle.toString());
    }

    /**
     * Texto obrigatorio, sem espacos nas pontas, nao vazio, ate {@code max}
     * code points e sem caractere de controle/formatacao/separador (inclui
     * U+0000 e U+10FFFF, reservados em chaves). Devolve o texto sem espacos
     * nas pontas.
     */
    private static String exigirTextoSimples(String valor, String campo, int max) {
        if (valor == null || valor.strip().isEmpty()) {
            throw new RedContractException(campo + " e obrigatorio e nao pode ser vazio.");
        }
        String limpo = valor.strip();
        if (limpo.codePointCount(0, limpo.length()) > max) {
            throw new RedContractException(campo + " excede o tamanho maximo de " + max + " caracteres.");
        }
        for (int i = 0; i < limpo.length(); ) {
            int cp = limpo.codePointAt(i);
            int tipo = Character.getType(cp);
            if (tipo == Character.CONTROL || tipo == Character.FORMAT
                    || tipo == Character.LINE_SEPARATOR || tipo == Character.PARAGRAPH_SEPARATOR
                    || tipo == Character.SURROGATE || cp == Character.MAX_CODE_POINT) {
                throw new RedContractException(campo + " contem caractere de controle ou reservado (U+"
                        + String.format("%04X", cp) + ").");
            }
            i += Character.charCount(cp);
        }
        return limpo;
    }

    /**
     * Beneficiario como digest (INV-08/INV-14): {@code sha256:} + 64 hex
     * minusculos, sem normalizacao (espaco, maiuscula, prefixo ausente ou
     * tamanho diferente sao recusados). A mensagem de erro nao repete o valor
     * recebido.
     */
    private static String exigirBeneficiarioDigest(String valor, String campo) {
        if (valor == null || valor.isEmpty() || valor.strip().isEmpty()) {
            throw new RedContractException(campo + " e obrigatorio e nao pode ser vazio.");
        }
        boolean valido = valor.length() == BENEFICIARIO_TAMANHO && valor.startsWith(BENEFICIARIO_PREFIXO);
        for (int i = BENEFICIARIO_PREFIXO.length(); valido && i < valor.length(); i++) {
            char c = valor.charAt(i);
            valido = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
        }
        if (!valido) {
            throw new RedContractException(campo + " fora do formato de digest (INV-08/INV-14): esperado "
                    + BENEFICIARIO_FORMATO_DESCRICAO + "; calcule o hash fora do ledger e envie somente o digest.");
        }
        return valor;
    }

    /**
     * Recusa padroes de identificador direto em texto livre (motivo): e-mail,
     * CPF/CNPJ/telefone pontuados e palavra feita so de digitos com 8+. Datas
     * como {@code 2025-01-15} e anos passam.
     */
    private static void exigirSemIdentificadorDiretoNoTexto(String texto, String campo) {
        boolean suspeito = texto.indexOf('@') >= 0
                || PADRAO_CPF_PONTUADO.matcher(texto).find()
                || PADRAO_CNPJ_PONTUADO.matcher(texto).find()
                || PADRAO_TELEFONE_PONTUADO.matcher(texto).find();
        if (!suspeito) {
            for (String palavra : texto.split("\\s+")) {
                if (palavra.length() >= MIN_DIGITOS_IDENTIFICADOR_DIRETO && soDigitosAscii(palavra)) {
                    suspeito = true;
                    break;
                }
            }
        }
        if (suspeito) {
            throw new RedContractException(campo + " contem padrao de identificador pessoal direto "
                    + "(e-mail, CPF, CNPJ, telefone ou numero longo; INV-08).");
        }
    }

    private static boolean soDigitosAscii(CharSequence s) {
        if (s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean temCaractereReservado(String s) {
        return s != null && (s.indexOf('\u0000') >= 0 || s.indexOf(Character.MAX_CODE_POINT) >= 0);
    }

    private JsonNode lerJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler entrada de periodo do ledger.", e);
        }
    }

    private ProjetoREDD lerProjetoOuFalhar(ChaincodeStub stub, String idProjeto) {
        String json = stub.getStringState(chaveProjeto(idProjeto));
        if (isBlank(json)) {
            throw new RedContractException("Projeto com id '" + idProjeto + "' nao encontrado.");
        }
        return parse(json, ProjetoREDD.class);
    }

    private RelatorioMRV lerRelatorioOuFalhar(ChaincodeStub stub, String idRelatorio) {
        String json = stub.getStringState(chaveRelatorio(idRelatorio));
        if (isBlank(json)) {
            throw new RedContractException("Relatorio MRV com id '" + idRelatorio + "' nao encontrado.");
        }
        return parse(json, RelatorioMRV.class);
    }

    private CreditoCarbono lerCreditoOuFalhar(ChaincodeStub stub, String idCredito) {
        String json = stub.getStringState(chaveCredito(idCredito));
        if (isBlank(json)) {
            throw new RedContractException("Credito com id '" + idCredito + "' nao encontrado.");
        }
        return parse(json, CreditoCarbono.class);
    }

    private void putState(ChaincodeStub stub, String key, Object asset) {
        stub.putStringState(key, toJson(asset));
    }

    private void emitirEvento(ChaincodeStub stub, String nomeDoEvento, Object payload) {
        stub.setEvent(nomeDoEvento, toJson(payload).getBytes(StandardCharsets.UTF_8));
    }

    private String toJson(Object asset) {
        try {
            return mapper.writeValueAsString(asset);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao serializar asset para JSON.", e);
        }
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao desserializar asset do ledger (" + type.getSimpleName() + ").", e);
        }
    }

    private Instant parseInstant(String iso, String nomeCampo) {
        if (isBlank(iso)) {
            throw new RedContractException(nomeCampo + " e obrigatorio (formato ISO-8601, ex.: 2024-01-01T00:00:00Z).");
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            throw new RedContractException(nomeCampo + " invalido, esperado ISO-8601 (ex.: 2024-01-01T00:00:00Z): " + iso);
        }
    }

    /**
     * Referencia opaca e deterministica do ator (INV-08): o id bruto de
     * {@code ClientIdentity#getId()} e base64 do DN do certificado e pode
     * conter CN/nome, entao NUNCA vai ao estado publico nem ao evento. Grava-se
     * {@code "sha256:"} + hex minusculo de SHA-256 sobre
     * {@value #SAL_REFERENCIA_AUTOR} + {@code |} + MSP + {@code |} + id. O mesmo
     * ator gera sempre o mesmo valor (rastreabilidade). O sal e fixo e publico
     * (domain separation): nao protege contra dicionario sobre DN adivinhavel.
     */
    static String referenciaOpacaDoAutor(String mspId, String idBruto) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((SAL_REFERENCIA_AUTOR + "|" + mspId + "|" + idBruto).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(BENEFICIARIO_PREFIXO);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RedContractException("SHA-256 indisponivel na JVM do chaincode.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

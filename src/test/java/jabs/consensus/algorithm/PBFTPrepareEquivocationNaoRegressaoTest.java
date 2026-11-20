package jabs.consensus.algorithm;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Nao regressao (criterio 7) da tarefa PBFT-prepare-equivocation (ADR-0015 B-35 / Q28; declarar no ADR-0006).
 *
 * <p>O que e comparado: {@link PBFTSignatureMain#run} = hash encadeado dos {@code BlockFinalizationEvent} do PBFT
 * (um por no que finaliza cada bloco), com {@code nodeID}, tempo de simulacao (bits do double), C_B_total e
 * C_B_consenso (bytes RECEBIDOS por aquele no ate a finalizacao local, ADR-0006: granularidade POR NO, nao agregada),
 * mais o numero de eventos. Rede {@code PBFTLocalLANNetwork} (LAN 100M), duracao 60 s, {@code PBFTConsensusConfig}
 * padrao, sementes 1 e 42, n = 4, 7, 10, 13; ataque aplicado aos nos {1,2} (n &lt; 10) ou {1,4,7} (n &gt;= 10) com
 * {@code ByzantineConfig(n, ids, ataque, semente)}.
 *
 * <p>Versao do simulador: referencia ANTES = commit 423ffbe (HEAD no inicio da tarefa; Java 25, pom mira 17).
 * Os valores abaixo foram medidos nele (arvore limpa, arquivo gerado por {@code PBFTSignatureMain}) e DEPOIS nesta arvore.
 * <ul>
 *   <li>{@link #INALTERADOS}: SEM ataque (e com SILENT/WITHHOLD/DOUBLE_SIGN, que nao tocam o PREPARE) a saida tem de
 *       ser IDENTICA a de 423ffbe. Sao 34 celulas, valores iguais antes e depois.</li>
 *   <li>{@link #EQUIVOCATION_DEPOIS}: SOB EQUIVOCATION a saida MUDOU DE PROPOSITO (autorizado pelo humano; o PREPARE do
 *       bizantino passa a equivocar). 6 celulas (n = 7, 10, 13 x sementes 1, 42). Em n = 4 o ataque nao produz
 *       finalizacao antes nem depois (f = 2 &gt; 1): 0 eventos, igual.</li>
 *   <li>{@link #EQUIVOCATION_ANTES}: os valores de 423ffbe dessas 6 celulas; o teste exige que sejam DIFERENTES (se
 *       voltarem a coincidir, o PREPARE deixou de equivocar).</li>
 * </ul>
 * Evidencia completa (CSV, comandos, sha) em {@code src/test/resources/nao-regressao/pbft-prepare-equivocation/}.
 *
 * <p><b>T6 (PBFT-T6-primario-por-altura, ADR-0007 opcao 2; 21/11/2026): a saida do PBFT MUDOU DE PROPOSITO em TODAS as celulas</b>
 * (view por altura, primario unico por altura, sem a proposta periodica do genesis; antes ~5 a 10 blocos decididos por altura).
 * Os valores de 423ffbe ficam em {@link #INALTERADOS} e {@link #EQUIVOCATION_ANTES} so como REFERENCIA do "antes" (nome historico:
 * eles nao sao mais o esperado); o esperado agora e {@link #DEPOIS_T6} (40 celulas, medidas nesta arvore).
 */
class PBFTPrepareEquivocationNaoRegressaoTest {

    /** {ataque, semente, n, eventos, hash}; "none" = sem ataque. Iguais em 423ffbe e aqui. */
    private static final String[][] INALTERADOS = {
        {"none", "1", "4", "1365", "-1680314063298140823"},
        {"EQUIVOCATION", "1", "4", "0", "7"},
        {"DOUBLE_SIGN", "1", "4", "1412", "7252980088654890574"},
        {"WITHHOLD", "1", "4", "0", "7"},
        {"SILENT", "1", "4", "0", "7"},
        {"none", "1", "7", "1974", "6763055172190156042"},
        {"DOUBLE_SIGN", "1", "7", "2110", "-879688356436845031"},
        {"WITHHOLD", "1", "7", "259", "-8097504719536415919"},
        {"SILENT", "1", "7", "259", "-8097504719536415919"},
        {"none", "1", "10", "2690", "6948573843589533572"},
        {"DOUBLE_SIGN", "1", "10", "2341", "5423963037808198069"},
        {"WITHHOLD", "1", "10", "370", "-1342799853513430911"},
        {"SILENT", "1", "10", "370", "-1342799853513430911"},
        {"none", "1", "13", "2938", "1002962415440789565"},
        {"DOUBLE_SIGN", "1", "13", "3003", "2046481691048884695"},
        {"WITHHOLD", "1", "13", "598", "-3536280609503569253"},
        {"SILENT", "1", "13", "598", "-3536280609503569253"},
        {"none", "42", "4", "1368", "-7727448726791677214"},
        {"EQUIVOCATION", "42", "4", "0", "7"},
        {"DOUBLE_SIGN", "42", "4", "1384", "5169275244586140585"},
        {"WITHHOLD", "42", "4", "0", "7"},
        {"SILENT", "42", "4", "0", "7"},
        {"none", "42", "7", "2142", "4738943886593476800"},
        {"DOUBLE_SIGN", "42", "7", "2046", "6922826804974077422"},
        {"WITHHOLD", "42", "7", "259", "2005184027465433309"},
        {"SILENT", "42", "7", "259", "2005184027465433309"},
        {"none", "42", "10", "2620", "6074591704567445583"},
        {"DOUBLE_SIGN", "42", "10", "2500", "-8228883766344263435"},
        {"WITHHOLD", "42", "10", "370", "-6990464203400315281"},
        {"SILENT", "42", "10", "370", "-6990464203400315281"},
        {"none", "42", "13", "3021", "6695705754294308910"},
        {"DOUBLE_SIGN", "42", "13", "2808", "2110715574228720307"},
        {"WITHHOLD", "42", "13", "598", "4114401754973484497"},
        {"SILENT", "42", "13", "598", "4114401754973484497"}
    };

    /** Saida de 423ffbe sob EQUIVOCATION (ANTES). */
    private static final String[][] EQUIVOCATION_ANTES = {
        {"EQUIVOCATION", "1", "7", "259", "-3750069128457248591"},
        {"EQUIVOCATION", "1", "10", "370", "1002388922402510273"},
        {"EQUIVOCATION", "1", "13", "598", "6126928195921355541"},
        {"EQUIVOCATION", "42", "7", "259", "6065762483344427826"},
        {"EQUIVOCATION", "42", "10", "370", "-4741951480703185585"},
        {"EQUIVOCATION", "42", "13", "598", "-6076953029857019385"}
    };

    /** Saida do T6 (view por altura, primario unico por altura), nesta arvore: as mesmas 40 celulas. */
    private static final String[][] DEPOIS_T6 = {
        {"none", "1", "4", "483", "-6979975084774882534"},
        {"EQUIVOCATION", "1", "4", "0", "7"},
        {"DOUBLE_SIGN", "1", "4", "516", "3862672239455229395"},
        {"WITHHOLD", "1", "4", "0", "7"},
        {"SILENT", "1", "4", "0", "7"},
        {"none", "1", "7", "574", "4553853776974575833"},
        {"DOUBLE_SIGN", "1", "7", "532", "356647852987865026"},
        {"WITHHOLD", "1", "7", "7", "-4895855572928848985"},
        {"SILENT", "1", "7", "7", "-4895855572928848985"},
        {"none", "1", "10", "709", "1844323745250813051"},
        {"DOUBLE_SIGN", "1", "10", "630", "-8652874994860755232"},
        {"WITHHOLD", "1", "10", "10", "-8198908766144905032"},
        {"SILENT", "1", "10", "10", "-8198908766144905032"},
        {"none", "1", "13", "689", "7043832599338870787"},
        {"DOUBLE_SIGN", "1", "13", "741", "8427538398947130453"},
        {"WITHHOLD", "1", "13", "13", "-8101020521692806848"},
        {"SILENT", "1", "13", "13", "-8101020521692806848"},
        {"none", "42", "4", "472", "-9169811343307147889"},
        {"EQUIVOCATION", "42", "4", "0", "7"},
        {"DOUBLE_SIGN", "42", "4", "468", "-3023481831346855093"},
        {"WITHHOLD", "42", "4", "0", "7"},
        {"SILENT", "42", "4", "0", "7"},
        {"none", "42", "7", "567", "-1988701359253455089"},
        {"DOUBLE_SIGN", "42", "7", "602", "1235552252215960715"},
        {"WITHHOLD", "42", "7", "7", "-672597471343754717"},
        {"SILENT", "42", "7", "7", "-672597471343754717"},
        {"none", "42", "10", "640", "-3260234439367956400"},
        {"DOUBLE_SIGN", "42", "10", "628", "6267588922322708018"},
        {"WITHHOLD", "42", "10", "10", "-6046626066238932973"},
        {"SILENT", "42", "10", "10", "-6046626066238932973"},
        {"none", "42", "13", "663", "-4070111622171187996"},
        {"DOUBLE_SIGN", "42", "13", "687", "-4268102888640257175"},
        {"WITHHOLD", "42", "13", "13", "-4315336517445603543"},
        {"SILENT", "42", "13", "13", "-4315336517445603543"},
        {"EQUIVOCATION", "1", "7", "7", "4073071908501444073"},
        {"EQUIVOCATION", "1", "10", "10", "5974516971354047943"},
        {"EQUIVOCATION", "1", "13", "13", "7586821690127881787"},
        {"EQUIVOCATION", "42", "7", "7", "286023906771858791"},
        {"EQUIVOCATION", "42", "10", "10", "8504822016748610695"},
        {"EQUIVOCATION", "42", "13", "13", "-4688367413858436600"}
    };

    private static String rodar(String[] c) {
        long semente = Long.parseLong(c[1]);
        int n = Integer.parseInt(c[2]);
        String ataque = c[0].equals("none") ? null : c[0];
        Set<Integer> bizantinos = n >= 10 ? Set.of(1, 4, 7) : Set.of(1, 2);
        return PBFTSignatureMain.run(semente, n, ataque, bizantinos);
    }

    private static String esperado(String[] c) {
        return "events=" + c[3] + " hash=" + c[4];
    }

    @Test
    void t6SaidaEstavelEIgualAoPinado() {
        for (String[] c : DEPOIS_T6) {
            String rotulo = c[0] + " seed=" + c[1] + " n=" + c[2];
            assertEquals(esperado(c), rodar(c), rotulo + ": T6");
            assertEquals(rodar(c), rodar(c), rotulo + ": mesma semente, mesma saida");
        }
    }

    /** Sem ataque, o T6 muda a saida de TODA celula em relacao a 423ffbe (se voltar a coincidir, o T6 foi desfeito). */
    @Test
    void semAtaqueOT6MudouASaidaEmRelacaoA423ffbe() {
        int comparadas = 0;
        for (String[] c : INALTERADOS) {
            if (c[0].equals("none")) {
                assertNotEquals(esperado(c), rodar(c), "none seed=" + c[1] + " n=" + c[2] + ": igual a 423ffbe, o T6 nao mudou nada");
                comparadas++;
            }
        }
        assertEquals(8, comparadas);
    }
}

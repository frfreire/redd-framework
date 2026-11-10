package jabs.network.networks.hotstuff;

import jabs.consensus.algorithm.hotstuff.DiscardReason;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQcMessage;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.HotStuffQcNetworkMessage;
import jabs.network.message.Packet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a-2a-2c-3: troca de visao por TIMEOUT com falha de lider por CRASH (injetada pelo teste: {@code Node#crash()}; nao
 * e ataque do estudo, o ataque e EQUIVOCATION na tarefa 2d). Rede completa (lider + replicas) sobre o simulador.
 * Valores de timeout e duracao sao de TESTE, nao de pesquisa. Cada teste imprime uma linha {@code [liveness]} com o
 * que observou, para o relatorio.
 */
class HotStuffViewChangeTest {
    /** Timeout de visao de TESTE: bem maior que uma visao honesta (~0,45 s), para o timer so disparar com falha. */
    private static final double TIMEOUT_S = 2.0;
    private static final double DURACAO_S = 25.0;

    private static int lider(long view, int n) {
        return (int) (view % n);
    }

    // ---------- (i) 1 lider crash na primeira visao ----------

    private void umCrashNaPrimeiraVisao(int n) {
        int crash = lider(1, n);
        HotStuffCrashRun r = new HotStuffCrashRun(1, n, DURACAO_S, TIMEOUT_S, Set.of(crash));
        System.out.println(r.resumo("(i) crash do lider da visao 1"));
        r.verificaSeguranca("(i) n=" + n);
        assertTrue(r.cadeia(crash).isEmpty(), "o no em crash nunca finaliza");
        assertEquals(0, r.hs(crash).getCurrentView() - 1, "o no em crash nunca saiu da visao inicial");
        int honestos = n - 1;
        int f = (n - 1) / 3;
        assertTrue(honestos >= n - f, "n-f honestos bastam");
        assertTrue(r.maiorCadeia() >= 10, "progresso: " + r.maiorCadeia());
        for (int id = 0; id < n; id++) {
            if (id == crash) {
                continue;
            }
            assertTrue(r.cadeia(id).size() >= r.maiorCadeia() - 1, "no honesto " + id + " acompanha a cadeia");
            assertTrue(r.hs(id).getTimeoutAdvances() >= 1, "no honesto " + id + " trocou de visao por timeout");
        }
        // levou UMA visao perdida: o bloco 1 vem da visao 2 (criado por leaderOf(2)), depois de UM timeout
        HotStuffBlock b1 = r.cadeia(lider(2, n)).get(0);
        assertEquals(2L, r.viewOf.get(b1), "primeiro bloco na visao 2: uma visao perdida");
        assertEquals(lider(2, n), b1.getCreator().getNodeID());
        assertTrue(r.tempos.get(0) >= TIMEOUT_S, "a primeira finalizacao so ocorre depois do timeout");
        // nenhum bloco criado pelo no em crash
        for (HotStuffBlock b : r.cadeia(lider(2, n))) {
            assertTrue(b.getCreator().getNodeID() != crash);
        }
    }

    @Test
    void n4UmLiderCrashNaPrimeiraVisaoAsReplicasHonestasTrocamDeVisaoEFinalizamAMesmaCadeia() {
        umCrashNaPrimeiraVisao(4);
    }

    @Test
    void n7UmLiderCrashNaPrimeiraVisaoAsReplicasHonestasTrocamDeVisaoEFinalizamAMesmaCadeia() {
        umCrashNaPrimeiraVisao(7);
    }

    @Test
    void cadaVoltaDoRodizioComOLiderCrashCustaUmTimeoutEHaUmBlocoAMenosPorVolta() {
        int n = 4;
        HotStuffCrashRun r = new HotStuffCrashRun(1, n, DURACAO_S, TIMEOUT_S, Set.of(1));
        r.verificaSeguranca("rodizio");
        // visoes de blocos finalizados: nunca uma visao com lider 1 (crash)
        for (HotStuffBlock b : r.cadeia(0)) {
            assertTrue(r.viewOf.get(b) % n != 1, "visao " + r.viewOf.get(b) + " e do lider em crash");
        }
        // um avanco por timeout por volta (a cada n visoes uma e do crash)
        long avancos = r.hs(0).getTimeoutAdvances();
        long visoes = r.hs(0).getCurrentView() - 1;
        assertTrue(avancos >= visoes / n - 1 && avancos <= visoes / n + 1,
                "avancos por timeout=" + avancos + " visoes=" + visoes);
        System.out.println("[liveness] n=4 crash 1: visoes=" + visoes + " avancosPorTimeout(no0)=" + avancos
                + " blocos(no0)=" + r.cadeia(0).size());
    }

    // ---------- (ii) crash numa visao posterior ----------

    @Test
    void crashNumaVisaoPosteriorDepoisDeVariosBlocosARedeRetomaPorTimeout() {
        for (int n : new int[]{4, 7}) {
            int vitima = n - 1;
            double tCrash = 6.0;
            HotStuffCrashRun r = new HotStuffCrashRun(1, n, DURACAO_S, TIMEOUT_S, Set.of(), Map.of(vitima, tCrash),
                    tCrash, tCrash + 1.0);
            System.out.println(r.resumo("(ii) crash do no " + vitima + " em t=" + tCrash));
            r.verificaSeguranca("(ii) n=" + n);
            int antes = r.finalizadasAte.get(tCrash);
            assertTrue(antes > 0, "ja havia blocos finalizados antes do crash: " + antes);
            // o no 0 (honesto) continua finalizando muito depois do crash
            int ate = 0;
            for (int i = 0; i < r.finalizacoes.size(); i++) {
                if (r.finalizacoes.get(i).getNode().getNodeID() == 0 && r.tempos.get(i) > tCrash + TIMEOUT_S + 3.0) {
                    ate++;
                }
            }
            assertTrue(ate >= 5, "no 0 retomou apos o crash: " + ate + " finalizacoes (n=" + n + ")");
            assertTrue(r.hs(0).getTimeoutAdvances() >= 1, "o no 0 trocou de visao por timeout");
            // blocos criados pela vitima so existem de antes do crash
            for (int i = 0; i < r.finalizacoes.size(); i++) {
                HotStuffBlock b = (HotStuffBlock) r.finalizacoes.get(i).getBlock();
                if (b.getCreator().getNodeID() == vitima) {
                    assertTrue(b.getCreationTime() < tCrash, "bloco da vitima criado depois do crash");
                }
            }
        }
    }

    @Test
    void crashEmQualquerInstanteDeUmaVisaoNaoQuebraASegurancaNemParaARede() {
        // varre instantes (inclusive meio de visao, com QC ja formado) e vitimas; n=4 e n=7
        long ancestrais = 0;
        int comJustify = 0;
        for (int n : new int[]{4, 7}) {
            for (int vitima = 0; vitima < n; vitima++) {
                for (double t : new double[]{0.31, 0.62, 0.9, 1.37, 2.05, 3.3}) {
                    HotStuffCrashRun r = new HotStuffCrashRun(3, n, 14.0, TIMEOUT_S, Set.of(), Map.of(vitima, t));
                    String ctx = "n=" + n + " vitima=" + vitima + " t=" + t;
                    r.verificaSeguranca(ctx);
                    int min = Integer.MAX_VALUE;
                    for (int id = 0; id < n; id++) {
                        if (id != vitima) {
                            min = Math.min(min, r.cadeia(id).size());
                        }
                    }
                    assertTrue(min >= 5, ctx + ": todo honesto finaliza (min=" + min + ")");
                    comJustify += verificaPrepareEstendeOHighQc(r, ctx);
                    for (int id = 0; id < n; id++) {
                        ancestrais += r.hs(id).getFinalizedAncestors();
                        assertEquals(0, r.hs(id).getDiscardCount(DiscardReason.UNKNOWN_PARENT), ctx);
                        assertEquals(0, r.hs(id).getDiscardCount(DiscardReason.UNKNOWN_BLOCK), ctx);
                        assertEquals(0, r.hs(id).getDiscardCount(DiscardReason.INCOHERENT_QC), ctx);
                        assertEquals(0, r.hs(id).getDiscardCount(DiscardReason.SAFE_NODE_FAILED), ctx);
                    }
                }
            }
        }
        System.out.println("[liveness] varredura de crash: PREPAREs com highQC verificados=" + comJustify
                + " blocos finalizados como ancestrais de um DECIDE=" + ancestrais);
        assertTrue(comJustify > 0, "a varredura exercita PREPARE com highQC");
    }

    /**
     * Item 3 da tarefa: todo PREPARE entregue com justify (highQC) propoe bloco que ESTENDE o bloco do QC (filho direto,
     * pois o lider cria o filho do highQC.node), e o QC e de visao menor que a da proposta.
     */
    private static int verificaPrepareEstendeOHighQc(HotStuffCrashRun r, String ctx) {
        int comQc = 0;
        for (Packet p : r.entregues) {
            if (p.getMessage() instanceof HotStuffProposalMessage pm && pm.getPhase() == HotStuffProposalMessage.Phase.PREPARE
                    && pm.getQc().isPresent()) {
                comQc++;
                assertSame(pm.getQc().get().getBlock(), pm.getBlock().getFixedParent(), ctx + ": PREPARE estende o highQC");
                assertTrue(pm.getQc().get().getView() < pm.getView(), ctx + ": highQC de visao anterior");
            }
        }
        return comQc;
    }

    // ---------- (iii) f crashes consecutivos; f+1 nao progride ----------

    @Test
    void n7FCrashesConsecutivosNoRodizioHaProgressoDepoisDeTimeoutsSucessivos() {
        int n = 7;
        // com ROTATING leaderOf(v) = v mod 7: os ids 1 e 2 sao lideres das visoes 1 e 2, consecutivas
        HotStuffCrashRun r = new HotStuffCrashRun(1, n, 40.0, TIMEOUT_S, Set.of(1, 2));
        System.out.println(r.resumo("(iii) f=2 crashes consecutivos (1,2)"));
        r.verificaSeguranca("(iii) f");
        HotStuffBlock b1 = r.cadeia(3).get(0);
        assertEquals(3L, r.viewOf.get(b1), "duas visoes perdidas: o primeiro bloco e da visao 3");
        assertTrue(r.maiorCadeia() >= 10);
        assertTrue(r.hs(0).getTimeoutAdvances() >= 2, "timeouts sucessivos");
        // as visoes 1 e 2 nao tiveram bloco; a cada volta (7 visoes) mais duas
        for (HotStuffBlock b : r.cadeia(3)) {
            long v = r.viewOf.get(b);
            assertTrue(v % n != 1 && v % n != 2, "visao " + v + " e de lider em crash");
        }
    }

    @Test
    void fMaisUmCrashesARedeNaoProgrideMasContinuaSegura() {
        // OBSERVACAO, sem promessa de vivacidade: com f+1 em crash restam menos de n-f honestos e nenhum lider reune quorum
        for (int n : new int[]{4, 7}) {
            int f = (n - 1) / 3;
            Set<Integer> crash = new java.util.TreeSet<>();
            for (int i = 1; i <= f + 1; i++) {
                crash.add(i);
            }
            HotStuffCrashRun r = new HotStuffCrashRun(1, n, 30.0, TIMEOUT_S, crash);
            System.out.println(r.resumo("(iii) f+1=" + (f + 1) + " crashes " + crash));
            r.verificaSeguranca("(iii) f+1 n=" + n);
            assertEquals(0, r.finalizacoes.size(), "sem quorum n-f nao ha QC nem bloco (n=" + n + ")");
            for (int id = 0; id < n; id++) {
                if (!crash.contains(id)) {
                    assertTrue(r.hs(id).getTimeoutAdvances() >= 5, "os honestos seguem trocando de visao: sem QC nem DECIDE");
                }
            }
        }
    }

    // ---------- (iv) timeout obsoleto filtrado e contado; replica nao regride ----------

    @Test
    void semFalhaOsTimersDeVisoesJaDeixadasSaoFiltradosEContadosEOsNosNaoRegridem() {
        int n = 4;
        HotStuffCrashRun r = new HotStuffCrashRun(1, n, DURACAO_S, TIMEOUT_S, Set.of()); // verifica nao-regressao a cada evento
        System.out.println(r.resumo("(iv) sem crash, timers obsoletos"));
        r.verificaSeguranca("(iv)");
        for (int id = 0; id < n; id++) {
            assertEquals(0, r.hs(id).getTimeoutAdvances(), "honesto: nenhum timer da visao corrente expira");
            long obsoletos = r.hs(id).getDiscardCount(DiscardReason.STALE_VIEW_TIMEOUT);
            assertTrue(obsoletos > 5, "no " + id + ": timers obsoletos filtrados e contados: " + obsoletos);
            assertTrue(obsoletos <= r.hs(id).getCurrentView(), "no maximo um por visao");
        }
    }

    // ---------- OBSERVACAO: timeout menor que a duracao de uma visao (sem falha) ----------

    /**
     * ACHADO (sem promessa de vivacidade; so a SEGURANCA e afirmada): numa rede 100% honesta, um timeout CONSTANTE
     * menor que a duracao real de uma visao (~0,45 s em n=4 e ~0,7 s em n=7 nesta LAN com bloco de 1 MiB) faz as replicas
     * deixarem a visao antes do DECIDE. Sem backoff (o timeout nunca cresce) e sem catch-up (nada alinha as visoes das
     * replicas; o bloco de uma visao ja deixada nao chega a quem a deixou), a rede perde vivacidade, as vezes por
     * completo. Isto e consequencia direta do que a 2c-3 deixa de fora (backoff / Delta: ADR-0015 Q12, A-24;
     * sincronizacao de visao), e NAO deve ser lido como resultado de pesquisa.
     */
    @Test
    void timeoutMenorQueAVisaoSemFalhaNaoQuebraASegurancaMesmoPerdendoVivacidade() {
        for (int n : new int[]{4, 7}) {
            for (double timeout : new double[]{0.2, 0.4, 0.6}) {
                HotStuffCrashRun r = new HotStuffCrashRun(1, n, 30.0, timeout, Set.of());
                r.verificaSeguranca("timeout curto n=" + n + " t=" + timeout);
                long unknownParent = 0;
                for (int id = 0; id < n; id++) {
                    unknownParent += r.hs(id).getDiscardCount(DiscardReason.UNKNOWN_PARENT);
                }
                System.out.println("[liveness] SEM FALHA, timeout de teste=" + timeout + "s: n=" + n + " maiorCadeia="
                        + r.maiorCadeia() + " visao(no0)=" + r.hs(0).getCurrentView() + " avancosPorTimeout(no0)="
                        + r.hs(0).getTimeoutAdvances() + " UNKNOWN_PARENT(total)=" + unknownParent);
            }
        }
    }

    // ---------- (v)/(vii) determinismo e traducao ----------

    @Test
    void mesmaSementeMesmaSequenciaComCrash() {
        for (int n : new int[]{4, 7}) {
            List<String> a = new HotStuffCrashRun(42, n, 15.0, TIMEOUT_S, Set.of(1)).assinatura();
            List<String> b = new HotStuffCrashRun(42, n, 15.0, TIMEOUT_S, Set.of(1)).assinatura();
            assertFalse(a.isEmpty());
            assertEquals(a, b, "n=" + n);
        }
    }

    @Test
    void mesmaSementeMesmaSequenciaComCrashNoMeioDaExecucao() {
        List<String> a = new HotStuffCrashRun(42, 4, 15.0, TIMEOUT_S, Set.of(), Map.of(2, 4.1)).assinatura();
        List<String> b = new HotStuffCrashRun(42, 4, 15.0, TIMEOUT_S, Set.of(), Map.of(2, 4.1)).assinatura();
        assertEquals(a, b);
    }

    // ---------- trafego dos NEW_VIEW de troca de visao ----------

    @Test
    void newViewDeTrocaDeVisaoSaoPacotesMedidosEPacoteDoCrashNaoExiste() {
        int n = 4;
        int crash = 1;
        HotStuffCrashRun r = new HotStuffCrashRun(1, n, 12.0, TIMEOUT_S, Set.of(crash));
        long[] semQcBytes = new long[n];
        int newViewDeTimeoutSemQc = 0;
        for (Packet p : r.entregues) {
            assertTrue(p.getFrom().getNodeID() != crash, "pacote do no em crash nao existe");
            if (p.getMessage() instanceof HotStuffQcNetworkMessage m
                    && m.getQcMessage().getType() == HotStuffQcMessage.Type.NEW_VIEW) {
                HotStuffQcMessage q = m.getQcMessage();
                assertEquals(lider(q.getView() + 1, n), p.getTo().getNodeID(), "NEW_VIEW vai ao lider de view+1");
                assertTrue(p.getFrom() != p.getTo());
                if (!q.hasQc() && p.getTo().getNodeID() != crash) {
                    semQcBytes[p.getTo().getNodeID()] += p.getSize();
                    if (q.getView() == 1) {
                        newViewDeTimeoutSemQc++;
                    }
                }
            }
        }
        // visao 1 expirou: os 3 honestos mandaram NEW_VIEW(view=1) sem QC (prepareQC = bottom) ao lider da visao 2;
        // o proprio lider (no 2) se autoentrega (sem pacote), logo 2 pacotes chegam pela rede
        assertEquals(2, newViewDeTimeoutSemQc, "n-f-1 pacotes de NEW_VIEW sem QC da troca de visao 1->2");
        for (int id = 0; id < n; id++) {
            if (id != crash) {
                assertEquals(semQcBytes[id], r.hs(id).getUnattributedTraffic(),
                        "contador a parte do no " + id + " = soma dos NEW_VIEW sem QC que a rede lhe entregou");
            }
        }
    }
}

package jabs.network.networks.hotstuff;

import jabs.consensus.algorithm.HotStuff;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffTx;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.Packet;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.event.PacketDeliveryEvent;
import jabs.simulator.randengine.RandomnessEngine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execucao completa (rede real, lider e replicas) com NOS EM CRASH injetados pelo TESTE: {@code crashedFromStart} sao
 * postos em crash antes de iniciar e nao sao iniciados ({@code Node#crash()}: a interface cai, nada sai nem chega);
 * {@code crashAt} derruba um no em um instante de simulacao. E falha por crash injetada, NAO o ataque do estudo (o
 * ataque e EQUIVOCATION, tarefa 2d). Valores de teste, nao de pesquisa.
 *
 * <p>Registra, em ordem de execucao: finalizacoes por no, pacotes entregues, a visao de cada bloco proposto (lida da
 * proposta PREPARE entregue) e confere, a cada evento, que nenhuma replica regride de visao.
 */
final class HotStuffCrashRun {
    final HotStuffLocalLANNetwork net;
    final HotStuffConsensusConfig cfg;
    final int n;
    final Simulator sim = new Simulator();
    final Set<Integer> crashedFromStart;
    final List<BlockFinalizationEvent> finalizacoes = new ArrayList<>();
    final List<Double> tempos = new ArrayList<>();
    final List<Packet> entregues = new ArrayList<>();
    final Map<HotStuffBlock, Long> viewOf = new IdentityHashMap<>();
    /** Total de finalizacoes (todos os nos) ate cada instante pedido em {@code marcas}. */
    final Map<Double, Integer> finalizadasAte = new TreeMap<>();

    HotStuffCrashRun(long seed, int n, double duracao, double timeoutVisao, Set<Integer> crashedFromStart,
                     Map<Integer, Double> crashAt, double... marcas) {
        this.n = n;
        this.cfg = HotStuffTestConfig.config(timeoutVisao);
        this.crashedFromStart = crashedFromStart;
        this.net = new HotStuffLocalLANNetwork(new RandomnessEngine(seed));
        net.populateNetwork(sim, n, cfg);
        net.startConsensusRound(sim, duracao, crashedFromStart);
        for (Map.Entry<Integer, Double> c : crashAt.entrySet()) {
            HotStuffNode alvo = net.getNode(c.getKey());
            sim.putEvent(alvo::crash, c.getValue());
        }
        long[] vistas = new long[n];
        int proxMarca = 0;
        double[] ms = marcas.clone();
        java.util.Arrays.sort(ms);
        while (sim.isThereMoreEvents() && sim.peekEventTime() <= duracao) {
            while (proxMarca < ms.length && sim.peekEventTime() > ms[proxMarca]) {
                finalizadasAte.put(ms[proxMarca], finalizacoes.size());
                proxMarca++;
            }
            Event e = sim.peekEvent();
            if (e instanceof BlockFinalizationEvent fe) {
                finalizacoes.add(fe);
                tempos.add(sim.peekEventTime());
            } else if (e instanceof PacketDeliveryEvent pe) {
                entregues.add(pe.packet);
                if (pe.packet.getMessage() instanceof HotStuffProposalMessage pm
                        && pm.getPhase() == HotStuffProposalMessage.Phase.PREPARE) {
                    viewOf.putIfAbsent(pm.getBlock(), pm.getView());
                }
            }
            sim.executeNextEvent();
            for (int id = 0; id < n; id++) {
                long v = hs(id).getCurrentView();
                assertTrue(v >= vistas[id], "a visao do no " + id + " regrediu: " + vistas[id] + " -> " + v);
                vistas[id] = v;
            }
        }
        for (int i = proxMarca; i < ms.length; i++) {
            finalizadasAte.put(ms[i], finalizacoes.size());
        }
    }

    HotStuffCrashRun(long seed, int n, double duracao, double timeoutVisao, Set<Integer> crashedFromStart) {
        this(seed, n, duracao, timeoutVisao, crashedFromStart, Map.of());
    }

    @SuppressWarnings("unchecked")
    HotStuff<HotStuffBlock, HotStuffTx> hs(int id) {
        return (HotStuff<HotStuffBlock, HotStuffTx>) net.getNode(id).getConsensusAlgorithm();
    }

    /** Blocos finalizados pelo no, em ordem de finalizacao (altura 1, 2, 3...). */
    List<HotStuffBlock> cadeia(int id) {
        List<HotStuffBlock> c = new ArrayList<>();
        for (BlockFinalizationEvent e : finalizacoes) {
            if (e.getNode() == net.getNode(id)) {
                c.add((HotStuffBlock) e.getBlock());
            }
        }
        return c;
    }

    /** Menor altura finalizada entre os nos dados / maior; para falar de "progresso". */
    int maiorCadeia() {
        int m = 0;
        for (int id = 0; id < n; id++) {
            m = Math.max(m, cadeia(id).size());
        }
        return m;
    }

    /**
     * Seguranca: em CADA no a cadeia finalizada tem alturas consecutivas desde 1, cada bloco filho direto do anterior
     * (genesis o primeiro); e entre QUAISQUER dois nos uma cadeia e prefixo da outra (mesmas referencias), logo nenhuma
     * replica finaliza bloco diferente na mesma altura.
     */
    void verificaSeguranca(String contexto) {
        for (int id = 0; id < n; id++) {
            List<HotStuffBlock> c = cadeia(id);
            for (int h = 0; h < c.size(); h++) {
                assertEquals(h + 1, c.get(h).getHeight(), contexto + ": alturas consecutivas no no " + id);
                assertSame(h == 0 ? HotStuffNode.HOTSTUFF_GENESIS_BLOCK : c.get(h - 1), c.get(h).getFixedParent(),
                        contexto + ": pai do bloco " + (h + 1) + " no no " + id);
            }
        }
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                List<HotStuffBlock> ca = cadeia(a);
                List<HotStuffBlock> cb = cadeia(b);
                int comum = Math.min(ca.size(), cb.size());
                for (int h = 0; h < comum; h++) {
                    assertSame(ca.get(h), cb.get(h), contexto + ": nos " + a + " e " + b + " divergem na altura " + (h + 1));
                }
            }
        }
    }

    /** Assinatura textual da execucao, para comparar determinismo (sem hash de identidade). */
    List<String> assinatura() {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < finalizacoes.size(); i++) {
            BlockFinalizationEvent e = finalizacoes.get(i);
            s.add(tempos.get(i) + "|" + e.getNode().getNodeID() + "|" + e.getBlock().getHeight() + "|"
                    + ((HotStuffBlock) e.getBlock()).getCreator().getNodeID() + "|" + viewOf.get(e.getBlock()) + "|"
                    + e.getTrafficUntilFinalization() + "|" + e.getConsensusMessageTraffic());
        }
        for (int id = 0; id < n; id++) {
            s.add("no" + id + "|view=" + hs(id).getCurrentView() + "|tavancos=" + hs(id).getTimeoutAdvances()
                    + "|descartes=" + hs(id).getTotalDiscards() + "|naoAtribuido=" + hs(id).getUnattributedTraffic());
        }
        return s;
    }

    /** Resumo legivel para o relatorio de vivacidade. */
    String resumo(String titulo) {
        StringBuilder sb = new StringBuilder("[liveness] ").append(titulo).append(": n=").append(n)
                .append(" crashInicial=").append(crashedFromStart).append(" finalizacoes=").append(finalizacoes.size());
        if (!finalizacoes.isEmpty()) {
            BlockFinalizationEvent p = finalizacoes.get(0);
            sb.append(" primeiraFinalizacao@t=").append(String.format("%.3f", tempos.get(0)))
                    .append(" bloco1.visao=").append(viewOf.get(p.getBlock()))
                    .append(" bloco1.criador=").append(((HotStuffBlock) p.getBlock()).getCreator().getNodeID());
        }
        sb.append(" maiorCadeia=").append(maiorCadeia());
        for (int id = 0; id < n; id++) {
            sb.append(" | no").append(id).append(":v=").append(hs(id).getCurrentView()).append(",tAv=")
                    .append(hs(id).getTimeoutAdvances()).append(",ch=").append(cadeia(id).size());
        }
        return sb.toString();
    }
}

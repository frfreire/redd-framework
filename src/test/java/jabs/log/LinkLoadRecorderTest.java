package jabs.log;

import jabs.log.EnlaceRig.Cena;
import jabs.network.message.Packet;
import jabs.network.node.LinkLoadCounters;
import jabs.scenario.ArqReddVotingScenario;
import jabs.simulator.event.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0012, tarefa M1a-1 (RM-01, RM-03, RM-06, RM-08, RM-09): VT-01, VT-02, VT-04, VT-05, VT-06 e VT-07 (parcial).
 *
 * <p>Todos os valores esperados foram calculados A MAO a partir do modelo do enlace do simulador e estao escritos
 * nos testes (nunca lidos do codigo testado). Modelo: transmissao de um pacote = {@code 8*size/banda} s; o k-esimo
 * pacote de uma rajada sai da fila de envio em {@code t + T1 + soma_{j<k} Tj}; latencia constante; recepcao tem a
 * mesma regra na fila de entrada. Banda usada: 8000 bit/s, entao um pacote de 1000 B leva 1 s.
 */
class LinkLoadRecorderTest {
    private static final double LAT = 0.5;
    private static final long BW = 8000;

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ utilitarios

    private LinkLoadRecorder.Result rodar(Cena c, double t0) throws IOException {
        return rodar(c, t0, "r");
    }

    private LinkLoadRecorder.Result rodar(Cena c, double t0, String nome) throws IOException {
        LinkLoadRecorder rec = new LinkLoadRecorder(tmp.resolve(nome).toString(), "RIG", 1L, t0);
        c.AddNewLogger(rec);
        c.run();
        return rec.getResult();
    }

    /** Le um CSV de saida: ignora linhas '#'; devolve as linhas de dados como mapas cabecalho -> valor. */
    private static List<Map<String, String>> ler(Path arquivo) throws IOException {
        List<String> linhas = new ArrayList<>();
        for (String l : Files.readAllLines(arquivo)) {
            if (!l.isBlank() && !l.startsWith("#")) linhas.add(l);
        }
        String[] cab = linhas.get(0).split(",");
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = 1; i < linhas.size(); i++) {
            String[] v = linhas.get(i).split(",");
            assertEquals(cab.length, v.length, "colunas da linha " + i);
            Map<String, String> m = new LinkedHashMap<>();
            for (int j = 0; j < cab.length; j++) m.put(cab[j], v[j]);
            out.add(m);
        }
        return out;
    }

    private static void uEmZeroUm(LinkLoadRecorder.Result r) {
        for (int i = 0; i < r.n; i++) {
            assertTrue(r.u[i] >= 0.0 && r.u[i] <= 1.0, "U[" + i + "]=" + r.u[i] + " fora de [0,1]");
        }
    }

    // ------------------------------------------------------------------ VT-05 (valor fechado, T1 - Tn)

    @Test
    void vt05_rajadaDeTresPacotesIguais_busyIgualASomaDosTempos() throws Exception {
        // no 0 envia 3 x 1000 B ao no 1 em t=0; T = 8*1000/8000 = 1 s; saem em 1, 2, 3; fila esvazia em 3.
        // busy = T1 + T1 + T2 = 3 = k*T (T1 = Tn). Recepcao: entrega em 2.5, 3.5, 4.5 => t1 = 4.5.
        Cena c = new Cena(2, BW, BW, LAT, 100).rajada(0.0, 0, 1, 1000, 1000, 1000);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(3000, r.sent[0]);
        assertEquals(0, r.sent[1]);
        assertEquals(3.0, r.busy[0], 1e-12);
        assertEquals(0.0, r.busy[1], 0.0);
        assertEquals(4.5, r.t1, 1e-12);
        assertEquals(3.0 / 4.5, r.u[0], 1e-12);
        assertEquals(0.0, r.u[1], 0.0);
        assertEquals(3000, r.recv[1]);
    }

    @Test
    void vt05_pacotesNoMesmoInstante_filaSaiEmOrdemDeHeapNaoFifo_documentado() throws Exception {
        // Comportamento HERDADO (nao corrigido): a fila e uma PriorityQueue chaveada so pelo tempo de entrada; com
        // empate de tempo a ordem de saida e a do heap. Tres pacotes [2000, 1000, 500] no mesmo instante:
        // add a,b,c => heap [a,b,c]; poll a => c vai a raiz, sem troca com b (empate) => saem a, c, b.
        // T = 2, 0.5, 1. Saidas em 2, 4 (2+Ta), 4.5 (4+Tc). busy = 4.5 = soma Tj (3.5) + (Ta - Tb = 2 - 1).
        Cena c = new Cena(2, BW, BW, LAT, 100).rajadaNoMesmoInstante(0.0, 0, 1, 2000, 1000, 500);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(3500, r.sent[0]);
        assertEquals(4.5, r.busy[0], 1e-12);
        // ordem de chegada no destino (mesma da saida): 2000, 500, 1000
        assertEquals(2000, c.nos[1].recebidos.get(0).getSize());
        assertEquals(500, c.nos[1].recebidos.get(1).getSize());
        assertEquals(1000, c.nos[1].recebidos.get(2).getSize());
    }

    @Test
    void vt05_tamanhosDiferentes_busyDifereDeSomaPorT1MenosTn() throws Exception {
        // 2000, 1000, 500 B (FIFO, entradas espacadas de 1 us) => T = 2, 1, 0.5 s. Saem em 2, 4 (2+T1), 5 (4+T2).
        // busy = 5. soma Tj = 3.5; T1 - Tn = 1.5; 3.5 + 1.5 = 5. O simulador mede 5, NAO 3.5 (comportamento
        // herdado do modelo do enlace, ADR-0012 Contexto item 5; nao e "corrigido" aqui).
        Cena c = new Cena(2, BW, BW, LAT, 100).rajada(0.0, 0, 1, 2000, 1000, 500);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(3500, r.sent[0]);
        assertEquals(5.0, r.busy[0], 1e-12);
        assertNotEquals(3.5, r.busy[0], 1e-9, "busy nao e a soma dos tempos de transmissao");
        // recepcao: chegadas em 2.5, 4.5, 5.5 e T_rx = 2, 1, 0.5 => ultimo evento em 6.0
        assertEquals(6.0, r.t1, 1e-12);
        assertEquals(5.0 / 6.0, r.u[0], 1e-12);
    }

    @Test
    void vt05_ordemInversa_diferencaT1MenosTnNegativa() throws Exception {
        // 500, 1000, 2000 B => T = 0.5, 1, 2 s. Saem em 0.5, 1.0 (0.5+0.5), 2.0 (1.0+1.0). busy = 2.0.
        // soma Tj = 3.5; T1 - Tn = -1.5; 3.5 - 1.5 = 2.0.
        Cena c = new Cena(2, BW, BW, LAT, 100).rajada(0.0, 0, 1, 500, 1000, 2000);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(2.0, r.busy[0], 1e-12);
        assertEquals(3500, r.sent[0]);
    }

    @Test
    void vt05_duasRajadasSeparadas_busySomaOsIntervalosEIgnoraOOcioso() throws Exception {
        // rajada A em t=0: 1 pacote de 1000 B (busy 1 s, sai em 1). rajada B em t=10: 2 x 1000 B
        // (T1 + T1 = 2 s, saem em 11 e 12). busy total = 3; o ocioso entre 1 e 10 nao conta.
        Cena c = new Cena(2, BW, BW, LAT, 100).rajada(0.0, 0, 1, 1000).rajada(10.0, 0, 1, 1000, 1000);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(3.0, r.busy[0], 1e-12);
        assertEquals(3000, r.sent[0]);
    }

    // ------------------------------------------------------------------ VT-01 (U em [0,1])

    @Test
    void vt01_enlaceEstrangulado_uChegaA1ENaoPassa() throws Exception {
        // no 0: 800 bit/s; 60 pacotes de 100 B (T = 1 s) em t=0 => fila ocupada ate t=60. A corrida para em t~20:
        // a fila nunca esvazia; o intervalo aberto e cortado em t1; U = (t1 - 0)/(t1 - 0) = 1.
        Cena c = new Cena(3, new long[]{800, BW, BW}, BW, LAT, 20);
        int[] pacotes = new int[60];
        java.util.Arrays.fill(pacotes, 100);
        c.rajada(0.0, 0, 1, pacotes);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(1.0, r.u[0], 1e-9, "U de enlace saturado deve ser 1");
        assertTrue(r.u[0] <= 1.0);
        uEmZeroUm(r);
        assertEquals(0, r.uRunArgmaxNodeId);
        assertEquals(r.u[0], r.uRunMax, 0.0);
        assertEquals(r.t1, r.busy[0], 1e-9, "busy = toda a janela");
    }

    @Test
    void vt01_enlaceEstrangulado_comT0DentroDoIntervaloAberto_uAindaE1() throws Exception {
        // mesmo cenario, janela [5, t1]: o intervalo ja esta aberto em t0 (aberto em 0); busy0 = 5 (corte em t0).
        Cena c = new Cena(3, new long[]{800, BW, BW}, BW, LAT, 20);
        int[] pacotes = new int[60];
        java.util.Arrays.fill(pacotes, 100);
        c.rajada(0.0, 0, 1, pacotes);
        LinkLoadRecorder.Result r = rodar(c, 5.0);
        assertEquals(1.0, r.u[0], 1e-9);
        uEmZeroUm(r);
        assertEquals(r.t1 - 5.0, r.busy[0], 1e-9);
    }

    @Test
    void vt01_noCaidoDesdeOInicio_uZeroEDescartesComColunaPropria() throws Exception {
        // no 2 caido desde o inicio. no 0 envia 3 x 1000 B ao no 2 (chegam e sao descartados no destino);
        // no 2 tenta enviar 500 B em t=1 (descartado no remetente: nunca entra em SentTotal).
        Cena c = new Cena(3, BW, BW, LAT, 100);
        c.caidoNoInicio[2] = true;
        c.rajada(0.0, 0, 2, 1000, 1000, 1000).rajada(1.0, 2, 0, 500);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        uEmZeroUm(r);
        assertEquals(0, r.sent[2]);
        assertEquals(0.0, r.u[2], 0.0, "no caido que nao enviou nada: U=0 (medido e nulo)");
        assertEquals(3000, r.sentTotal);
        assertEquals(0, r.recvTotal);
        assertEquals(3000, r.droppedAtDest);
        assertEquals(500, r.droppedSenderDown);
        assertEquals(0, r.residual);
    }

    @Test
    void vt01_noQueCaiComFilaCheia_continuaEsvaziandoEUPermaneceEmZeroUm() throws Exception {
        // no 0 envia 10 x 1000 B em t=0 e cai em t=2.5: o processo de envio continua drenando a fila (o simulador so
        // bloqueia NOVOS pacotes); uma segunda rajada em t=3 (no caido) e descartada no remetente (2 x 500 B).
        Cena c = new Cena(2, BW, BW, LAT, 100);
        int[] dez = new int[10];
        java.util.Arrays.fill(dez, 1000);
        c.rajada(0.0, 0, 1, dez).rajada(3.0, 0, 1, 500, 500);
        c.quedas.add(new double[]{2.5, 0});
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        uEmZeroUm(r);
        assertEquals(10_000, r.sent[0]);
        assertEquals(10.0, r.busy[0], 1e-12);
        assertEquals(1000, r.droppedSenderDown);
        assertEquals(10_000, r.sentTotal);
    }

    // ------------------------------------------------------------------ VT-02 (enviado = recebido + descartes + residuo)

    @Test
    void vt02_semCrashRodadoAteEsvaziar_igualdadeExata() throws Exception {
        // dois remetentes, tres destinos, tamanhos variados; roda ate nao haver mais eventos
        Cena c = new Cena(3, BW, BW, LAT, 1000)
                .rajada(0.0, 0, 1, 1000, 250, 500)
                .rajada(0.5, 1, 2, 125, 125)
                .rajada(2.0, 2, 0, 1000);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        long esperado = 1000 + 250 + 500 + 125 + 125 + 1000; // soma a mao = 3000
        assertEquals(3000, esperado);
        assertEquals(esperado, r.sentTotal);
        assertEquals(esperado, r.recvTotal, "SentTotal == RecvTotal exato quando nada pende e ninguem cai");
        assertEquals(0, r.droppedAtDest);
        assertEquals(0, r.residual);
        assertEquals(0, r.droppedSenderDown);
        assertEquals(0, r.inFlightAtT0);
    }

    @Test
    void vt02_comCrashDoDestino_descartaNoDestinoEFechaAIdentidade() throws Exception {
        // no 0 -> no 1: 4 x 1000 B; saem em 1,2,3,4; chegam em 1.5,2.5,3.5,4.5. no 1 cai em t=3.0.
        // p1 (entrega 2.5) e p2 (chegou 2.5, entrega 3.5, ja estava na fila de recepcao) sao entregues;
        // p3 e p4 chegam com a interface caida: descartados no destino. 4000 = 2000 + 2000 + 0.
        Cena c = new Cena(2, BW, BW, LAT, 100).rajada(0.0, 0, 1, 1000, 1000, 1000, 1000);
        c.quedas.add(new double[]{3.0, 1});
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(4000, r.sentTotal);
        assertEquals(2000, r.recvTotal);
        assertEquals(2000, r.droppedAtDest);
        assertEquals(0, r.residual);
        assertEquals(r.sentTotal, r.recvTotal + r.droppedAtDest + r.residual);
    }

    @Test
    void vt02_corteEmT2ponto2_umPacoteEmTransito() throws Exception {
        // 4 x 1000 B de 0 para 1, para ao passar de t=2.2. Execucao: p1 sai em 1, chega em 1.5, e entregue em 2.5;
        // p2 sai em 2 (entrega agendada para 2.5). O ultimo evento e a entrega de p1 em 2.5 (numero de insercao
        // menor que a chegada de p2). Fim: Sent = 2000 (p1, p2); Recv = 1000 (p1); em transito = p2 = 1000.
        Cena c = new Cena(2, BW, BW, LAT, 2.2).rajada(0.0, 0, 1, 1000, 1000, 1000, 1000);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(2.5, r.t1, 1e-12);
        assertEquals(2000, r.sentTotal);
        assertEquals(1000, r.recvTotal);
        assertEquals(0, r.droppedAtDest);
        assertEquals(1000, r.residual);
    }

    @Test
    void vt02_corteEmT1ponto7_pacoteNaFilaDeRecepcaoEOutroEmTransito() throws Exception {
        // para ao passar de t=1.7: ultimo evento = saida de p2 em t=2. Sent = 2000. p1 chegou em 1.5 e espera na
        // fila de recepcao (entrega em 2.5); p2 esta em transito. Recv = 0; residual = 1000 + 1000.
        Cena c = new Cena(2, BW, BW, LAT, 1.7).rajada(0.0, 0, 1, 1000, 1000, 1000, 1000);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(2.0, r.t1, 1e-12);
        assertEquals(2000, r.sentTotal);
        assertEquals(0, r.recvTotal);
        assertEquals(2000, r.residual);
    }

    // ------------------------------------------------------------------ VT-04 (entrega direta nao conta)

    @Test
    void vt04_entregaDiretaNaoMudaSentNemRecv() throws Exception {
        // processIncomingPacket chamado direto (como CasperFFG:360-369 e DAGsper:353 fazem consigo mesmos) nao
        // passa por nenhuma fila: nem Sent do no 0 nem Recv do no 0 mudam. O pacote enfileirado de verdade
        // (1000 B, 0 -> 1) conta uma vez em cada ponta.
        Cena c = new Cena(2, BW, BW, LAT, 100).rajada(0.0, 0, 1, 1000);
        c.diretas.add(new double[]{1.0, 0, 777});
        c.diretas.add(new double[]{1.2, 1, 333});
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(1000, r.sent[0]);
        assertEquals(0, r.sent[1]);
        assertEquals(0, r.recv[0], "a entrega direta de 777 B nao e Recv");
        assertEquals(1000, r.recv[1], "a entrega direta de 333 B nao soma ao Recv do no 1");
        assertEquals(1000, r.sentTotal);
        assertEquals(1000, r.recvTotal);
        // o no recebeu os pacotes diretos (o protocolo os processou): so nao contam como trafego
        assertEquals(1, c.nos[0].recebidos.size());
        assertEquals(777, c.nos[0].recebidos.get(0).getSize());
    }

    @Test
    void vt04_pacoteParaSiMesmoQuePassaPelaFilaConta() throws Exception {
        // RM-01: pacote com destino ao proprio no que PASSA pela fila (como o broadcast do PBFTP2P) ocupa o
        // enlace, conta em Sent e em Recv.
        Cena c = new Cena(1, BW, BW, LAT, 100).rajada(0.0, 0, 0, 1000);
        LinkLoadRecorder.Result r = rodar(c, 0.0);
        assertEquals(1000, r.sent[0]);
        assertEquals(1000, r.recv[0]);
        assertEquals(1.0, r.busy[0], 1e-12);
    }

    // ------------------------------------------------------------------ VT-06 (janela)

    @Test
    void vt06_t0Diferentes_resultadoEIgualADiferencaDosInstantaneos() throws Exception {
        // 4 x 1000 B de 0 para 1; saem em 1,2,3,4; busy total = 4. Entregas no no 1 em 2.5,3.5,4.5,5.5 => t1 = 5.5.
        // Janela A: t0 = 0. Janela B: t0 = 2.5; o instantaneo e tomado ANTES do primeiro evento com t >= 2.5, entao
        // Sent0 = 2000 (p1, p2 ja sairam), busy0 = 2.5 (fila aberta desde 0) e Recv0 = 0 (a entrega de p1 e um evento
        // de t = 2.5 e fica dentro da janela).
        Cena a = new Cena(2, BW, BW, LAT, 100).rajada(0.0, 0, 1, 1000, 1000, 1000, 1000);
        Cena b = new Cena(2, BW, BW, LAT, 100).rajada(0.0, 0, 1, 1000, 1000, 1000, 1000);
        LinkLoadRecorder.Result ra = rodar(a, 0.0, "a");
        LinkLoadRecorder.Result rb = rodar(b, 2.5, "b");
        assertEquals(5.5, ra.t1, 1e-12);
        assertEquals(5.5, rb.t1, 1e-12);
        assertEquals(4000, ra.sent[0]);
        assertEquals(2000, rb.sent[0], "B = A - Sent0(=2000)");
        assertEquals(4.0, ra.busy[0], 1e-12);
        assertEquals(1.5, rb.busy[0], 1e-12, "B = A - busy0(=2.5)");
        assertEquals(4000, ra.recv[1]);
        assertEquals(4000, rb.recv[1], "Recv0 = 0: nenhuma entrega antes de t=2.5");
        assertEquals(4.0 / 5.5, ra.u[0], 1e-12);
        assertEquals(1.5 / 3.0, rb.u[0], 1e-12);
        assertEquals(2.5, rb.t0, 0.0);
    }

    @Test
    void vt06_t0DepoisDeAlgumasEntregas_conservacaoUsaOQueEstavaEmTransitoEmT0() throws Exception {
        // t0 = 3.2. Antes do primeiro evento com t >= 3.2 (em 3.5) ja ocorreram: saidas p1,p2,p3 (em 1,2,3) => Sent0 =
        // 3000; entrega de p1 (2.5) => Recv0 = 1000. Em t0 ha 2000 B pendentes: p2 na fila de recepcao (chegou em
        // 2.5, entrega em 3.5) e p3 em transito (saiu em 3, chega em 3.5) => InFlightAtT0 = 2000.
        // Na janela: Sent = 1000 (p4), Recv = 3000 (p2, p3, p4), Residual = 0.
        // Identidade: 1000 = 3000 + 0 + 0 - 2000.
        Cena c = new Cena(2, BW, BW, LAT, 100).rajada(0.0, 0, 1, 1000, 1000, 1000, 1000);
        LinkLoadRecorder.Result r = rodar(c, 3.2);
        assertEquals(2000, r.inFlightAtT0);
        assertEquals(1000, r.sentTotal);
        assertEquals(3000, r.recvTotal);
        assertEquals(0, r.residual);
        assertEquals(r.sentTotal, r.recvTotal + r.droppedAtDest + r.residual - r.inFlightAtT0);
    }

    @Test
    void vt06_semT0_falhaAlto() {
        // linha de comando: registro ligado sem t0 => erro; t0 sem registro ligado => erro; valores invalidos => erro
        assertThrows(IllegalArgumentException.class, () -> LinkLoadRecorder.parseWindowStart("true", null));
        assertThrows(IllegalArgumentException.class, () -> LinkLoadRecorder.parseWindowStart(null, "5"));
        assertThrows(IllegalArgumentException.class, () -> LinkLoadRecorder.parseWindowStart("false", "5"));
        assertThrows(IllegalArgumentException.class, () -> LinkLoadRecorder.parseWindowStart("yes", "5"));
        assertThrows(IllegalArgumentException.class, () -> LinkLoadRecorder.parseWindowStart("true", "abc"));
        assertThrows(IllegalArgumentException.class, () -> LinkLoadRecorder.parseWindowStart("true", "-1"));
        assertThrows(IllegalArgumentException.class, () -> LinkLoadRecorder.parseWindowStart("true", "NaN"));
        assertThrows(IllegalArgumentException.class, () -> LinkLoadRecorder.parseWindowStart("true", "Infinity"));
        // desligado (padrao): nada
        assertNull(LinkLoadRecorder.parseWindowStart(null, null));
        assertNull(LinkLoadRecorder.parseWindowStart("false", null));
        // ligado com t0 explicito
        assertEquals(30.0, LinkLoadRecorder.parseWindowStart("true", "30"), 0.0);
        assertEquals(0.0, LinkLoadRecorder.parseWindowStart("true", "0"), 0.0);
        // o construtor tambem nao aceita janela implicita
        assertThrows(IllegalArgumentException.class, () -> new LinkLoadRecorder("x", "P", 1L, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new LinkLoadRecorder("x", "P", 1L, -0.5));
    }

    @Test
    void vt06_t0DepoisDoFimDaCorrida_falhaAltoSemArquivos() throws Exception {
        Cena c = new Cena(2, BW, BW, LAT, 100).rajada(0.0, 0, 1, 1000);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rodar(c, 50.0, "fora"));
        assertTrue(e.getMessage().contains("not inside the run"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("fora-nos.csv")));
        assertFalse(Files.exists(tmp.resolve("fora-corrida.csv")));
    }

    // ------------------------------------------------------------------ VT-07 parcial (sentinela) e formato

    @Test
    void vt07_sentinelaNaoMedidoDiferenteDeZero_eArquivosNovos() throws Exception {
        // no 2 nunca envia: SentBytes = 0 e U_i = 0.0 (medido e nulo). Colunas nao medidas nesta versao: -1.
        Cena c = new Cena(3, BW, BW, LAT, 100).rajada(0.0, 0, 1, 1000, 1000);
        rodar(c, 0.0);
        List<Map<String, String>> nos = ler(tmp.resolve("r-nos.csv"));
        assertEquals(3, nos.size());
        Map<String, String> n2 = nos.get(2);
        assertEquals("2", n2.get("NodeID"));
        assertEquals("0", n2.get("SentBytes"));
        assertEquals("0", n2.get("RecvBytes"));
        assertEquals(0.0, Double.parseDouble(n2.get("BusyTime_s")), 0.0);
        assertEquals(0.0, Double.parseDouble(n2.get("U_i")), 0.0);
        assertNotEquals("-1", n2.get("SentBytes"));
        assertEquals("2000", nos.get(0).get("SentBytes"));
        assertEquals("2000", nos.get(1).get("RecvBytes"));
        // nenhuma coluna de nos usa -1: tudo la foi medido
        for (Map<String, String> linha : nos) {
            for (String v : linha.values()) assertFalse(v.startsWith("-"), "sentinela em coluna medida: " + v);
        }

        List<Map<String, String>> corrida = ler(tmp.resolve("r-corrida.csv"));
        assertEquals(1, corrida.size());
        Map<String, String> m = corrida.get(0);
        assertEquals(List.of("Seed", "SimulatorVersion", "Protocol", "n", "WindowStart_s", "WindowEnd_s",
                "ConfRate_tx_per_s", "Theta_tx_per_s", "ThetaStatus", "U_run_max", "U_run_argmaxNodeID",
                "U_block_max", "SentTotal_B", "RecvTotal_B", "DroppedAtDest_B", "DroppedSenderDown_B",
                "ResidualBytes_B", "UnattributedSent_B", "InFlightAtT0_B"), new ArrayList<>(m.keySet()));
        assertEquals("-1", m.get("ConfRate_tx_per_s"));
        assertEquals("-1", m.get("Theta_tx_per_s"));
        assertEquals("NOT_MEASURED", m.get("ThetaStatus"));
        assertEquals("-1", m.get("U_block_max"));
        assertEquals("-1", m.get("UnattributedSent_B"));
        assertEquals("1", m.get("Seed"));
        assertEquals(LinkLoadRecorder.SIMULATOR_VERSION, m.get("SimulatorVersion"));
        assertEquals("RIG", m.get("Protocol"));
        assertEquals("3", m.get("n"));
        assertEquals(0.0, Double.parseDouble(m.get("WindowStart_s")), 0.0);
        assertEquals("2000", m.get("SentTotal_B"));
        assertEquals("2000", m.get("RecvTotal_B"));
        assertEquals("0", m.get("DroppedAtDest_B"));
        assertEquals("0", m.get("ResidualBytes_B"));
        // medidos: nunca -1
        assertEquals("0", m.get("U_run_argmaxNodeID"));
        assertTrue(Double.parseDouble(m.get("U_run_max")) > 0.0);

        // semente, versao e granularidade no comentario inicial dos dois arquivos
        for (String f : new String[]{"r-nos.csv", "r-corrida.csv"}) {
            String texto = Files.readString(tmp.resolve(f));
            assertTrue(texto.startsWith("# Seed=1; SimulatorVersion=" + LinkLoadRecorder.SIMULATOR_VERSION), f);
            assertTrue(texto.contains("Granularity:"), f);
        }
    }

    // ------------------------------------------------------------------ RM-08 (desligado por padrao, sem efeito)

    /** Conta os eventos executados (nao cria evento). */
    private static final class Contador extends AbstractLogger {
        long eventos;
        @Override public void initialLog() { }
        @Override public void logBeforeEachEvent(Event event) { eventos++; }
        @Override public void logAfterEachEvent(Event event) { }
        @Override public void finalLog() { }
    }

    private static Cena cenaDeReferencia() {
        return new Cena(3, BW, BW, LAT, 100)
                .rajada(0.0, 0, 1, 1000, 250, 500).rajada(0.5, 1, 2, 125, 125).rajada(2.0, 2, 0, 1000);
    }

    @Test
    void rm08_desligadoPorPadraoNaoHaContadoresNemArquivos() throws Exception {
        Cena c = cenaDeReferencia();
        c.run();
        for (EnlaceRig.No no : c.nos) {
            assertNull(no.getNodeNetworkInterface().getLoadCounters(), "sem registrador, sem contadores");
        }
        try (var s = Files.list(tmp)) {
            assertEquals(0, s.count(), "nenhum arquivo novo");
        }
    }

    @Test
    void rm08_ligadoNaoMudaOsEventosNemOsTemposDeEntrega() throws Exception {
        Cena sem = cenaDeReferencia();
        Contador contSem = new Contador();
        sem.AddNewLogger(contSem);
        sem.run();

        Cena com = cenaDeReferencia();
        Contador contCom = new Contador();
        com.AddNewLogger(contCom);
        rodar(com, 0.0);

        assertEquals(contSem.eventos, contCom.eventos, "o registro nao cria nem remove evento");
        assertEquals(sem.getSimulator().getSimulationTime(), com.getSimulator().getSimulationTime(), 0.0);
        for (int i = 0; i < 3; i++) {
            assertEquals(sem.nos[i].tempos, com.nos[i].tempos, "tempos de entrega do no " + i);
            assertEquals(sem.nos[i].recebidos.size(), com.nos[i].recebidos.size());
        }
    }

    @Test
    void rm08_fontesDaInstrumentacaoNaoUsamHashNemAleatorioNemEventoNovo() throws IOException {
        // Varredura estatica (sem comentarios) dos dois arquivos novos e dos trechos tocados.
        String[] proibidos = {"hashCode", "identityHashCode", "HashMap", "HashSet", "RandomnessEngine", "Random",
                "putEvent", "Math.random", "new Event"};
        for (String arq : new String[]{"src/main/java/jabs/network/node/LinkLoadCounters.java",
                "src/main/java/jabs/log/LinkLoadRecorder.java"}) {
            String codigo = semComentarios(Files.readString(Path.of(arq)));
            for (String p : proibidos) {
                assertFalse(codigo.contains(p), arq + " usa '" + p + "' (RM-08)");
            }
        }
        // Os pontos de instrumentacao existentes nao podem agendar evento novo nem chamar hash:
        for (String arq : new String[]{"src/main/java/jabs/network/node/NetworkInterface.java"}) {
            String codigo = semComentarios(Files.readString(Path.of(arq)));
            for (String p : new String[]{"hashCode", "identityHashCode", "HashMap", "HashSet", "Math.random"}) {
                assertFalse(codigo.contains(p), arq + " usa '" + p + "'");
            }
        }
    }

    private static String semComentarios(String fonte) {
        return fonte.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    // ------------------------------------------------------------------ fumaca ponta a ponta (protocolo real)

    @Test
    void arqReal_n7_registroLigado_uEmZeroUmEConservacao() throws Exception {
        // PBFT real (ARQ), 7 nos, 20 s, t0 = 5 s. Nao ha valor esperado "exato" a mao aqui (o trafego do PBFT e
        // medido, nao calculado); o que se confere: faixa de U, nenhum descarte (sem crash) e a identidade de
        // bytes (que o registrador tambem exige internamente, falhando alto se violada).
        ArqReddVotingScenario cenario = new ArqReddVotingScenario("ARQ-teste", 1L, 7, 20.0);
        LinkLoadRecorder rec = new LinkLoadRecorder(tmp.resolve("arq").toString(), "ARQ", 1L, 5.0);
        cenario.AddNewLogger(rec);
        cenario.run();
        LinkLoadRecorder.Result r = rec.getResult();
        assertEquals(7, r.n);
        uEmZeroUm(r);
        assertTrue(r.sentTotal > 0, "o PBFT enviou pacotes pela rede simulada na janela");
        assertEquals(0, r.droppedAtDest);
        assertEquals(0, r.droppedSenderDown);
        assertEquals(r.sentTotal, r.recvTotal + r.residual - r.inFlightAtT0);
        assertTrue(r.uRunMax > 0.0 && r.uRunMax <= 1.0);
        assertEquals(r.uRunMax, r.u[r.uRunArgmaxNodeId], 0.0);
        assertEquals(5.0, r.t0, 0.0);
        assertTrue(Files.exists(tmp.resolve("arq-nos.csv")));
        assertTrue(Files.exists(tmp.resolve("arq-corrida.csv")));
    }

    // ------------------------------------------------------------------ contadores isolados

    @Test
    void contadoresIsolados_intervaloAbertoECortadoNoFim() {
        LinkLoadCounters c = new LinkLoadCounters();
        assertEquals(0.0, c.busyTimeAt(10.0), 0.0);
        c.upLinkBecameBusy(2.0);
        assertTrue(c.isBusyOpen());
        assertEquals(3.0, c.busyTimeAt(5.0), 0.0);
        c.packetSent(100, false, 3.0);
        assertTrue(c.isBusyOpen());
        c.packetSent(50, true, 4.0);
        assertFalse(c.isBusyOpen());
        assertEquals(2.0, c.busyTimeAt(99.0), 0.0);
        assertEquals(150, c.getSentBytes());
        // fechar sem abrir e abrir duas vezes sao erros de instrumentacao: falham alto
        assertThrows(IllegalStateException.class, () -> c.packetSent(1, true, 5.0));
        c.upLinkBecameBusy(6.0);
        assertThrows(IllegalStateException.class, () -> c.upLinkBecameBusy(7.0));
        assertThrows(IllegalArgumentException.class, () -> c.busyTimeAt(5.0));
    }
}

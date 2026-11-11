package jabs.metrics;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Acumulador POR ALTURA dos bytes que UM no recebeu da rede simulada (HS-CB-fase2, PARTE A). Alimenta a medida
 * "SOMA DA REDE por bloco": somada sobre todos os nos receptores (feito por {@link CbRedeMedida}), e a soma, para
 * cada altura, dos bytes de TODOS os pacotes entregues pela rede simulada que nomeiam aquela altura.
 *
 * <p><b>Separado dos contadores do protocolo.</b> Os mapas {@code blockTraffic}/{@code consensusTraffic} do HotStuff e
 * do Tendermint (chave: bloco, corte na finalizacao local, zerados por {@code takeTraffic}) NAO sao lidos nem
 * alterados aqui: a coluna C_B do primeiro no a finalizar fica byte a byte como era. Este acumulador e alimentado
 * no MESMO ponto em que o hook ja conta o pacote ({@code addBlockTraffic}/{@code addConsensusMessageTraffic}), mas
 * continua acumulando depois de o no ter finalizado a altura (sem corte local) e nunca e zerado.
 *
 * <p><b>Nunca autoentrega.</b> Quem alimenta so e chamado depois de o hook de {@code PeerBlockchainNode} descartar a
 * entrega local ({@code packet.getFrom() == this}); um byte aqui e sempre um pacote que atravessou a rede simulada.
 *
 * <p><b>Convencoes PROVISORIAS (a definir com o humano; ADR-0015 D1), medidas a parte para se saber quanto pesam:</b>
 * <ul>
 *   <li>(i) {@link Cell#getPostFinalBytes()}: bytes de pacotes que chegaram depois de o DESTINO ja ter finalizado a
 *       altura ({@link #markFinalized}). Estao INCLUIDOS em {@link Cell#getTotalBytes()} (a soma principal e "sem
 *       corte local");</li>
 *   <li>(ii) {@link #getUnattributedBytes()}: trafego sem bloco/altura (NEW_VIEW sem QC no HotStuff, voto nil no
 *       Tendermint). NAO entra em nenhuma altura e NAO e descartado: fica neste contador, na corrida inteira.</li>
 * </ul>
 * Cobertura: so as mensagens que o hook do protocolo ja contabiliza (proposta, voto, QC/NEW_VIEW, bloco por
 * DataMessage). Qualquer outro tipo de mensagem que o hook nao conta (transacao, pedido de dado) nao esta aqui.
 *
 * <p><b>Determinismo.</b> Chave por altura ({@code Long}, hash por valor) em {@link TreeMap}; so get/put por chave e
 * leitura ordenada; nada de {@code identityHashCode}, colecao chaveada por objeto nem aleatoriedade. Crescimento:
 * uma {@link Cell} por altura vista por no (sem poda), proporcional ao numero de alturas da corrida.
 */
public final class NetworkHeightLedger {
    /** Bytes e pacotes de uma altura neste no. */
    public static final class Cell {
        private long totalBytes;
        private long consensusBytes;
        private long packets;
        private long postFinalBytes;
        private long postFinalPackets;
        private boolean finalized;

        /** C_B_total recebido na altura, sem corte local (inclui carga do bloco e o que chegou apos a finalizacao local). */
        public long getTotalBytes() { return totalBytes; }
        /** C_B_consenso recebido na altura, sem corte local (sem a carga do bloco). */
        public long getConsensusBytes() { return consensusBytes; }
        /** Pacotes que nomeiam a altura (cada entrega conta uma vez; duplicata tambem). */
        public long getPackets() { return packets; }
        /** (i) PROVISORIA: parte de {@link #getTotalBytes()} que chegou depois de o destino finalizar a altura. */
        public long getPostFinalBytes() { return postFinalBytes; }
        /** (i) PROVISORIA: pacotes correspondentes a {@link #getPostFinalBytes()}. */
        public long getPostFinalPackets() { return postFinalPackets; }
        /** Este no ja finalizou a altura. */
        public boolean isFinalized() { return finalized; }
    }

    private final TreeMap<Long, Cell> byHeight = new TreeMap<>();
    private long unattributedBytes = 0L;
    private long unattributedPackets = 0L;

    private Cell cell(long height) {
        Long key = height;
        Cell c = byHeight.get(key);
        if (c == null) {
            c = new Cell();
            byHeight.put(key, c);
        }
        return c;
    }

    /**
     * Um pacote de {@code bytes} bytes, entregue pela rede simulada a este no, que nomeia {@code height}. Chamado
     * uma vez por pacote (no ponto do {@code addBlockTraffic}). Se este no ja finalizou a altura, tambem entra em (i).
     */
    public void addBlockPacket(long height, long bytes) {
        require(height, bytes);
        Cell c = cell(height);
        c.totalBytes += bytes;
        c.packets++;
        if (c.finalized) {
            c.postFinalBytes += bytes;
            c.postFinalPackets++;
        }
    }

    /** Parte "so consenso" do mesmo pacote (no ponto do {@code addConsensusMessageTraffic}). */
    public void addConsensusBytes(long height, long bytes) {
        require(height, bytes);
        cell(height).consensusBytes += bytes;
    }

    /** (ii) PROVISORIA: pacote entregue que nao nomeia altura. Nunca descartado em silencio. */
    public void addUnattributed(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes deve ser >= 0: " + bytes);
        }
        unattributedBytes += bytes;
        unattributedPackets++;
    }

    /** Este no finalizou {@code height}: o que chegar depois entra tambem em (i). Idempotente; nao zera nada. */
    public void markFinalized(long height) {
        require(height, 0L);
        cell(height).finalized = true;
    }

    /** Alturas vistas por este no, em ordem crescente (visao so de leitura). */
    public SortedMap<Long, Cell> heights() {
        return Collections.unmodifiableSortedMap(byHeight);
    }

    /** (ii) PROVISORIA: bytes sem altura recebidos por este no na corrida inteira (nunca zerado). */
    public long getUnattributedBytes() { return unattributedBytes; }

    /** (ii) PROVISORIA: pacotes sem altura recebidos por este no na corrida inteira. */
    public long getUnattributedPackets() { return unattributedPackets; }

    private static void require(long height, long bytes) {
        if (height < 0) {
            throw new IllegalArgumentException("height deve ser >= 0: " + height);
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes deve ser >= 0: " + bytes);
        }
    }
}

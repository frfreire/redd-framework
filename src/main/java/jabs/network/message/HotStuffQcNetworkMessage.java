package jabs.network.message;

import jabs.ledgerdata.hotstuff.HotStuffQcMessage;

/**
 * Embrulho de rede de uma {@link HotStuffQcMessage} (NEW_VIEW ou DECIDE): o que viaja no pacote e o dado de
 * dominio, e o tamanho no fio e o da propria {@link HotStuffQcMessage#getSize()} (o do QC carregado, ou so o
 * overhead no NEW_VIEW sem prepareQC). Nao acrescenta bytes: o enquadramento de transporte e do pacote, nao desta
 * camada.
 *
 * <p>Invariante: a mensagem embrulhada nao pode ser nula ({@link IllegalArgumentException}); as demais
 * invariantes (fase do QC, visao, remetente) ja foram impostas pelas fabricas de {@link HotStuffQcMessage}.
 */
public final class HotStuffQcNetworkMessage extends Message {
    private final HotStuffQcMessage qcMessage;

    public HotStuffQcNetworkMessage(HotStuffQcMessage qcMessage) {
        super(sizeOf(qcMessage));
        this.qcMessage = qcMessage;
    }

    private static int sizeOf(HotStuffQcMessage qcMessage) {
        if (qcMessage == null) {
            throw new IllegalArgumentException("qcMessage obrigatoria");
        }
        return qcMessage.getSize();
    }

    public HotStuffQcMessage getQcMessage() {
        return qcMessage;
    }
}

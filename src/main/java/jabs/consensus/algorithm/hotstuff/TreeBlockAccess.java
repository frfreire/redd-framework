package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.blockchain.LocalBlockTree;
import jabs.ledgerdata.hotstuff.HotStuffBlock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/**
 * {@link BlockAccess} sobre a {@link LocalBlockTree} do no. Resolve "quem e o pai de X" SEM depender de
 * {@code Block#getParents()}: o pai vem do campo imutavel {@link HotStuffBlock#getFixedParent()} (fixado no
 * construtor) e fica num indice proprio (bloco -> pai) que nada mais altera. Mutar a lista de pais, antes ou depois
 * da admissao, nao muda nenhuma decisao desta classe. LIMITE: {@code LocalBlockTree#add} (base) ainda le a lista
 * mutavel ao inserir o bloco na arvore; isso afeta a estrutura da arvore, nao as decisoes daqui.
 *
 * <p>Um bloco so e admitido se o pai estiver na arvore local, conectado ao genesis, e com altura menor. Nao existe
 * sincronizacao de blocos faltantes nesta parte (limite declarado).
 *
 * <p>O indice e um {@link HashMap} chaveado por identidade (Block nao define equals/hashCode) acessado so por
 * chave (get/put) e por {@code removeIf} na poda, cujo resultado nao depende da ordem de iteracao.
 */
public final class TreeBlockAccess implements BlockAccess {
    private final LocalBlockTree<HotStuffBlock> tree;
    // Pai congelado na primeira leitura valida. Acesso so por get/put/removeIf.
    private final Map<HotStuffBlock, HotStuffBlock> parentIndex = new HashMap<>();
    // Blocos ADMITIDOS por uma proposta PREPARE aceita (o genesis conta como admitido). Acesso so por contains/add/removeIf.
    private final Set<HotStuffBlock> admitted = new HashSet<>();

    public TreeBlockAccess(LocalBlockTree<HotStuffBlock> tree) {
        if (tree == null) {
            throw new IllegalArgumentException("tree obrigatoria");
        }
        this.tree = tree;
    }

    @Override
    public boolean parentKnown(HotStuffBlock candidate) {
        requireBlock(candidate);
        if (candidate == tree.getGenesisBlock()) {
            return false;
        }
        return admitted.contains(candidate) || resolve(candidate) != null;
    }

    @Override
    public ParentLookup lookupFor(HotStuffBlock candidate) {
        requireBlock(candidate);
        return block -> {
            if (block == null) {
                throw new IllegalArgumentException("block obrigatorio");
            }
            if (block == tree.getGenesisBlock()) {
                return Optional.empty();
            }
            HotStuffBlock indexed = parentIndex.get(block);
            if (indexed != null) {
                return Optional.of(indexed);
            }
            boolean inTree = tree.contains(block);
            if (block != candidate && !inTree) {
                return Optional.empty(); // fora da arvore e nao e o candidato: pai desconhecido
            }
            HotStuffBlock parent = resolve(block);
            if (parent != null && inTree) {
                parentIndex.put(block, parent); // membro da arvore: congela o pai na primeira leitura
            }
            return Optional.ofNullable(parent);
        };
    }

    @Override
    public void admit(HotStuffBlock block) {
        requireBlock(block);
        if (admitted.contains(block)) {
            return;
        }
        HotStuffBlock parent = block == tree.getGenesisBlock() ? null : resolve(block);
        if (parent == null) {
            throw new IllegalStateException("admit exige parentKnown(block): pai desconhecido ou bloco genesis");
        }
        if (!tree.contains(block)) {
            tree.add(block);
        }
        parentIndex.put(block, parent);
        admitted.add(block);
    }

    /** Remove da arvore e do indice os blocos de altura menor que o limiar (o genesis fica). */
    public void prune(int thresholdHeight) {
        tree.clearObsoleteData(thresholdHeight);
        parentIndex.keySet().removeIf(b -> b.getHeight() < thresholdHeight && b != tree.getGenesisBlock());
        admitted.removeIf(b -> b.getHeight() < thresholdHeight);
    }

    /** Admitido por proposta PREPARE aceita (ou genesis). Bloco que so chegou por DataMessage NAO conta. */
    @Override
    public boolean contains(HotStuffBlock block) {
        requireBlock(block);
        return block == tree.getGenesisBlock() || admitted.contains(block);
    }

    public int indexedBlocks() {
        return parentIndex.size();
    }

    /**
     * Pai de {@code block}: o do indice, ou o pai IMUTAVEL do bloco ({@link HotStuffBlock#getFixedParent()}, fixado
     * no construtor) SE esse pai esta na arvore, conectado ao genesis, e e mais baixo que o filho. Nunca le
     * {@code Block#getParents()}: mutar a lista de pais, antes ou depois da admissao, nao muda a decisao.
     * "Conectado" = o proprio genesis, ou um bloco ja ADMITIDO por esta classe (admissao exige pai conectado, logo
     * a cadeia ate o genesis e imutavel). A marca {@code isConnectedToGenesis} da {@code LocalBlockTree} NAO e usada:
     * a base a calcula a partir da lista mutavel. Consequencia (mudanca de comportamento declarada): bloco que so
     * esta na arvore por DataMessage, sem proposta PREPARE aceita, nao serve de pai (UNKNOWN_PARENT). Nulo =
     * desconhecido.
     */
    private HotStuffBlock resolve(HotStuffBlock block) {
        HotStuffBlock indexed = parentIndex.get(block);
        if (indexed != null) {
            return indexed;
        }
        HotStuffBlock parent = block.getFixedParent();
        if (parent == null || !tree.contains(parent)) {
            return null;
        }
        boolean connected = parent == tree.getGenesisBlock() || admitted.contains(parent);
        if (!connected || parent.getHeight() >= block.getHeight()) {
            return null;
        }
        return parent;
    }

    private static void requireBlock(HotStuffBlock block) {
        if (block == null) {
            throw new IllegalArgumentException("block obrigatorio");
        }
    }
}

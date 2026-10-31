package jabs.consensus.algorithm;

import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.GhostProtocolConfig;
import jabs.ledgerdata.SingleParentBlock;
import jabs.ledgerdata.Tx;

import java.util.HashMap;
import java.util.HashSet;

public class GhostProtocol<B extends SingleParentBlock<B>, T extends Tx<T>>
        extends AbstractChainBasedConsensus<B, T> {
    private final HashMap<B, Integer> totalWeights = new HashMap<>();
    public static int DEFAULT_GHOST_WEIGHT = 1;
    protected B originOfGhost;
    private final double averageBlockMiningInterval;

    public GhostProtocol(LocalBlockTree<B> localBlockTree, GhostProtocolConfig ghostProtocolConfig) {
        super(localBlockTree);
        this.originOfGhost = localBlockTree.getGenesisBlock();
        this.newIncomingBlock(localBlockTree.getGenesisBlock());
        this.averageBlockMiningInterval = ghostProtocolConfig.averageBlockMiningInterval();
    }

    @Override
    public void newIncomingBlock(B block) {
        totalWeights.put(block, DEFAULT_GHOST_WEIGHT);
        if (this.localBlockTree.getLocalBlock(block).isConnectedToGenesis) {
            for (B ancestor : this.localBlockTree.getAllAncestors(block)) {
                if (!totalWeights.containsKey(ancestor)) {
                    totalWeights.put(ancestor, DEFAULT_GHOST_WEIGHT);
                }
                totalWeights.put(ancestor, totalWeights.get(ancestor) + DEFAULT_GHOST_WEIGHT);
            }
        }
        B ghostMainChainHead = this.ghost();
        if (this.currentMainChainHead != ghostMainChainHead) {
            this.currentMainChainHead = ghostMainChainHead;
            updateChain();
        }
    }

    public B ghost() {
        B block = this.originOfGhost;

        while (true) {
            if (totalWeights == null || totalWeights.get(block) == null) {
                return block;
            }
            if (totalWeights.get(block) == 1) {
                return block;
            }

            int maxWeight = 0;
            Iterable<B> children = this.childrenInGhostOrder(block);
            for (B child : children) {
                if (localBlockTree.getLocalBlock(child).isConnectedToGenesis) {
                    if (totalWeights != null && totalWeights.get(child) != null && totalWeights.get(child) > maxWeight) {
                        maxWeight = totalWeights.get(child);
                        block = child;
                    }
                }
            }
        }
    }

    /**
     * Ordem em que {@link #ghost()} examina os filhos de um bloco; com empate de peso vence o primeiro. O padrao e
     * a iteracao do proprio HashSet da arvore local, EXATAMENTE como antes (Casper/Nakamoto/Parlia nao mudam de
     * saida). Subclasses que precisam de desempate independente do endereco do objeto (ADR-0006, tarefa 5:
     * DAGsper) sobrescrevem.
     */
    protected Iterable<B> childrenInGhostOrder(B block) {
        return this.localBlockTree.getChildren(block);
    }

    @Override
    protected void updateChain() {
        this.confirmedBlocks = this.localBlockTree.getAllAncestors(this.currentMainChainHead);
    }
}

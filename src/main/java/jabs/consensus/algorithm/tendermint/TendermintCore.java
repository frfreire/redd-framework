package jabs.consensus.algorithm.tendermint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/**
 * Nucleo de decisao de UM validador Tendermint honesto: Algoritmo 1 de Buchman, Kwon e Milosevic, "The latest gossip
 * on BFT consensus" (arXiv:1807.04938, 2018). Classe pura: sem rede, sem simulador, sem relogio. Cada entrada
 * ({@link #start}, {@link #onProposal}, {@link #onVote}, {@link #onTimeout}) devolve a lista ordenada de
 * {@link TendermintAction} que o dono deve executar. NAO e thread-safe.
 *
 * <h3>Avaliacao das regras "upon"</h3>
 * Depois de cada entrada, as regras sao reavaliadas ate nenhuma disparar ({@link #drain}), porque uma mudanca de
 * estado pode habilitar outra regra com mensagens ja guardadas (ex.: a polka chega antes da proposta). A ordem de
 * tentativa e fixa, para determinismo: 49 (decidir), 55 (saltar de round), 22/28 (prevote), 36 (lock/valid),
 * 44 (precommit nil), 34 (timeout de prevote), 47 (timeout de precommit). O artigo admite qualquer ordem.
 *
 * <h3>"Pela primeira vez" (linhas 34, 36, 47)</h3>
 * Essas tres regras disparam no maximo uma vez por round; as guardas ficam em {@code prevoteTimeoutArmed},
 * {@code polkaHandled} e {@code precommitTimeoutArmed}, zeradas em {@link #startRound}. As demais regras sao
 * auto-limitadas pela mudanca de {@code step}, de round ou de altura.
 *
 * <h3>Log de mensagens</h3>
 * Por altura e por round: as propostas distintas do proponente e, por remetente, os PREVOTEs e PRECOMMITs distintos.
 * Mensagem identica a uma ja guardada conta DUPLICATE. Mensagem que CONTRADIZ outra do mesmo remetente (equivocacao)
 * e guardada e conta para o valor que assina, como no artigo, que conta mensagens por valor; ver {@code RoundLog} e
 * {@link #equivocationsObserved()}. "Vale a primeira" parece mais seguro mas QUEBRA a liveness: um no que recebeu
 * primeiro o voto w de um bizantino nunca reconhece a decisao em v que os outros tomaram contando o voto v dele.
 * Os limiares "de qualquer valor" (linhas 34, 47, 55) contam remetentes distintos, nunca mensagens. Mensagens de
 * alturas FUTURAS sao guardadas e usadas quando o no chega la; de alturas passadas, contadas como OLD_HEIGHT. Ao
 * decidir, o log de alturas &lt;= h e apagado (linha 53).
 *
 * <h3>Custo</h3>
 * As contagens por valor sao mantidas de forma incremental ({@code RoundLog}), e a regra 49 so reexamina os rounds
 * que receberam proposta ou precommit desde a ultima avaliacao. Cada mensagem custa O(1) amortizado mais as
 * propostas do round, e nao O(n) por regra; com n = 400 isso evita custo cubico por altura.
 *
 * <h3>Gossip (suposicao do artigo que o adaptador precisa honrar)</h3>
 * A liveness do Algoritmo 1 supoe que toda mensagem recebida por um no correto acaba recebida por todos os corretos.
 * Broadcast direto basta enquanto todos os remetentes sao corretos e a rede nao perde pacotes; com bizantinos que
 * mandam mensagens diferentes a nos diferentes, sem repasse a rede pode parar ({@code TendermintSafetyTest} mostra
 * casos). Como modelar o repasse no JABS, e o trafego que ele soma, e decisao do adaptador.
 *
 * <h3>Autoentrega</h3>
 * O que o no difunde entra direto no proprio log (o artigo conta a propria mensagem nos 2f+1). O adaptador envia so
 * aos outros e nunca reentrega a este no; uma mensagem recebida com {@code sender == self} e erro do adaptador e
 * falha alto.
 *
 * <h3>Limites declarados</h3>
 * <ul>
 *   <li>Poder de voto igual para todos ({@link TendermintQuorum});</li>
 *   <li>Sem {@code timeoutCommit} (espera apos decidir, propria do CometBFT): ao decidir, o no comeca a altura
 *       seguinte imediatamente, como no artigo. Se for modelado, e no adaptador;</li>
 *   <li>A memoria nao tem teto (como a retencao do HotStuff): um bizantino pode mandar muitas alturas futuras ou
 *       muitos votos distintos por round. Ver {@link #peakFutureMessages()} e {@link #equivocationsObserved()}.</li>
 * </ul>
 *
 * @param <V> tipo do valor proposto; {@code equals}/{@code hashCode} sao o {@code id(v)} do artigo
 */
public final class TendermintCore<V> {

    /** Proponente de (h, r): deterministico e igual em todos os nos (linhas 14, 22, 28, 36, 49). */
    @FunctionalInterface
    public interface ProposerSelector {
        int proposer(long height, int round);
    }

    /** Round-robin {@code (h + r) mod n}: o proponente muda a cada round e a cada altura. */
    public static ProposerSelector roundRobin(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n deve ser >= 1: " + n);
        }
        return (h, r) -> (int) Math.floorMod(h + r, (long) n);
    }

    /** Votos de um tipo num (h, r): remetentes, e contagem incremental de remetentes por valor e de nil. */
    private static final class VoteSet<V> {
        /** Por remetente, os votos distintos (normalmente um; mais de um so se ele equivocou). */
        final Map<Integer, List<TendermintVote<V>>> bySender = new HashMap<>();
        /** Remetentes distintos por valor. Cada voto guardado e um par (remetente, valor) novo. */
        final Map<V, Integer> tally = new HashMap<>();
        int nil;
        int messages;

        /** @return false se for duplicata exata; true se guardou (contando equivocacao em {@code core}) */
        boolean add(TendermintVote<V> v, TendermintCore<V> core) {
            List<TendermintVote<V>> fromSender = bySender.computeIfAbsent(v.sender(), k -> new ArrayList<>(1));
            if (fromSender.contains(v)) {
                return false;
            }
            if (!fromSender.isEmpty()) {
                core.equivocations++;
            }
            fromSender.add(v);
            messages++;
            if (v.isNil()) {
                nil++;
            } else {
                tally.merge(v.value(), 1, Integer::sum);
            }
            return true;
        }

        int countFor(V value) {
            return tally.getOrDefault(value, 0);
        }

        int senders() {
            return bySender.size();
        }
    }

    /**
     * Log de um (altura, round). Guarda TODAS as mensagens distintas, inclusive as que equivocam: o Algoritmo 1
     * conta mensagens por valor ("2f+1 PRECOMMIT para id(v)"), entao um bizantino que assinou v para uns e w para
     * outros entra na contagem de v E na de w. Isso nao fere a safety (dois conjuntos de mais de 2/3 se cruzam em mais
     * de n/3 remetentes, logo num correto, que vota uma vez so) e e necessario para a liveness.
     */
    private static final class RoundLog<V> {
        /** Propostas distintas do proponente, em ordem de chegada (mais de uma so se ele for bizantino). */
        final List<TendermintProposal<V>> proposals = new ArrayList<>(1);
        final VoteSet<V> prevotes = new VoteSet<>();
        final VoteSet<V> precommits = new VoteSet<>();
        final Set<Integer> senders = new LinkedHashSet<>();

        VoteSet<V> votes(TendermintVote.Type type) {
            return type == TendermintVote.Type.PREVOTE ? prevotes : precommits;
        }

        int messageCount() {
            return proposals.size() + prevotes.messages + precommits.messages;
        }
    }

    private final int self;
    private final int n;
    private final TendermintQuorum quorum;
    private final ProposerSelector proposerSelector;
    private final LongFunction<V> getValue;
    private final Predicate<V> valid;

    // Estado do Algoritmo 1 (linhas 2-4).
    private boolean started;
    private long height;
    private int round;
    private TendermintStep step = TendermintStep.PROPOSE;
    private V lockedValue;
    private int lockedRound = -1;
    private V validValue;
    private int validRound = -1;
    private final NavigableMap<Long, V> decisions = new TreeMap<>();

    // Guardas de "pela primeira vez", por round.
    private boolean prevoteTimeoutArmed;
    private boolean polkaHandled;
    private boolean precommitTimeoutArmed;

    private final NavigableMap<Long, NavigableMap<Integer, RoundLog<V>>> logs = new TreeMap<>();
    /** Por altura, rounds com proposta ou precommit novos ainda nao examinados pela regra 49. */
    private final NavigableMap<Long, NavigableSet<Integer>> pendingDecisionCheck = new TreeMap<>();
    private final EnumMap<TendermintDiscardReason, Long> discards = new EnumMap<>(TendermintDiscardReason.class);
    private int futureMessages;
    private int peakFutureMessages;
    private long equivocations;

    /**
     * @param self id deste validador, em [0, n)
     * @param n numero de validadores (&gt;= 1)
     * @param proposerSelector proponente de (h, r)
     * @param getValue valor a propor na altura h quando nao ha {@code validValue} (linha 16); nunca devolve nulo
     * @param valid {@code valid(v)} do artigo; deve ser deterministico e igual em todos os nos corretos
     */
    public TendermintCore(int self, int n, ProposerSelector proposerSelector, LongFunction<V> getValue,
                          Predicate<V> valid) {
        this.quorum = new TendermintQuorum(n);
        if (self < 0 || self >= n) {
            throw new IllegalArgumentException("self fora de [0, n): " + self);
        }
        this.self = self;
        this.n = n;
        this.proposerSelector = Objects.requireNonNull(proposerSelector, "proposerSelector");
        this.getValue = Objects.requireNonNull(getValue, "getValue");
        this.valid = Objects.requireNonNull(valid, "valid");
    }

    // ------------------------------------------------------------------------------------------------------
    // Entradas
    // ------------------------------------------------------------------------------------------------------

    /** Linha 5: {@code upon start do StartRound(0)}. Mensagens recebidas antes ficam no log e sao usadas agora. */
    public List<TendermintAction<V>> start() {
        if (started) {
            throw new IllegalStateException("start chamado duas vezes");
        }
        started = true;
        List<TendermintAction<V>> out = new ArrayList<>();
        startRound(0, out);
        drain(out);
        return out;
    }

    public List<TendermintAction<V>> onProposal(TendermintProposal<V> p) {
        Objects.requireNonNull(p, "proposta");
        List<TendermintAction<V>> out = new ArrayList<>();
        if (!acceptSender(p.sender()) || !acceptHeight(p.height())) {
            return out;
        }
        if (proposerSelector.proposer(p.height(), p.round()) != p.sender()) {
            count(TendermintDiscardReason.WRONG_PROPOSER);
            return out;
        }
        RoundLog<V> rl = roundLog(p.height(), p.round());
        if (rl.proposals.contains(p)) {
            count(TendermintDiscardReason.DUPLICATE);
            return out;
        }
        if (!rl.proposals.isEmpty()) {
            equivocations++;
        }
        rl.proposals.add(p);
        rl.senders.add(p.sender());
        markForDecisionCheck(p.height(), p.round());
        afterStore(p.height(), out);
        return out;
    }

    public List<TendermintAction<V>> onVote(TendermintVote<V> v) {
        Objects.requireNonNull(v, "voto");
        List<TendermintAction<V>> out = new ArrayList<>();
        if (!acceptSender(v.sender()) || !acceptHeight(v.height())) {
            return out;
        }
        RoundLog<V> rl = roundLog(v.height(), v.round());
        if (!rl.votes(v.type()).add(v, this)) {
            count(TendermintDiscardReason.DUPLICATE);
            return out;
        }
        rl.senders.add(v.sender());
        if (v.type() == TendermintVote.Type.PRECOMMIT) {
            markForDecisionCheck(v.height(), v.round());
        }
        afterStore(v.height(), out);
        return out;
    }

    /**
     * Funcoes OnTimeoutPropose/Prevote/Precommit (linhas 57-65). Timeout de (h, r, passo) que o no ja deixou para
     * tras e contado como STALE_TIMEOUT e nao tem efeito: e assim que o adaptador dispensa cancelar eventos.
     */
    public List<TendermintAction<V>> onTimeout(TimeoutKind kind, long h, int r) {
        Objects.requireNonNull(kind, "kind");
        requireStarted();
        List<TendermintAction<V>> out = new ArrayList<>();
        boolean current = h == height && r == round;
        switch (kind) {
            case PROPOSE:
                if (current && step == TendermintStep.PROPOSE) {
                    broadcastVote(TendermintVote.Type.PREVOTE, null, out);
                    step = TendermintStep.PREVOTE;
                } else {
                    count(TendermintDiscardReason.STALE_TIMEOUT);
                }
                break;
            case PREVOTE:
                if (current && step == TendermintStep.PREVOTE) {
                    broadcastVote(TendermintVote.Type.PRECOMMIT, null, out);
                    step = TendermintStep.PRECOMMIT;
                } else {
                    count(TendermintDiscardReason.STALE_TIMEOUT);
                }
                break;
            case PRECOMMIT:
                if (current) {
                    startRound(round + 1, out);
                } else {
                    count(TendermintDiscardReason.STALE_TIMEOUT);
                }
                break;
            default:
                throw new IllegalStateException("tipo desconhecido: " + kind);
        }
        drain(out);
        return out;
    }

    // ------------------------------------------------------------------------------------------------------
    // Regras
    // ------------------------------------------------------------------------------------------------------

    private void drain(List<TendermintAction<V>> out) {
        if (!started) {
            return;
        }
        // Cada disparo muda estado de forma monotona (step, round, altura ou uma guarda): o laco termina.
        while (rule49Decide(out)
                || rule55RoundSkip(out)
                || rule22And28Prevote(out)
                || rule36Polka(out)
                || rule44PrecommitNil(out)
                || rule34PrevoteTimeout(out)
                || rule47PrecommitTimeout(out)) {
            // repete ate nenhuma regra disparar
        }
    }

    /**
     * Linhas 49-54: proposta de qualquer round r + 2f+1 PRECOMMIT em id(v) decide v. So examina os rounds marcados
     * (proposta ou precommit novos): nos demais nada mudou desde o ultimo exame, e {@code valid} e deterministico.
     */
    private boolean rule49Decide(List<TendermintAction<V>> out) {
        NavigableSet<Integer> pending = pendingDecisionCheck.get(height);
        if (pending == null || pending.isEmpty() || decisions.containsKey(height)) {
            return false;
        }
        NavigableMap<Integer, RoundLog<V>> rounds = logs.get(height);
        while (!pending.isEmpty()) {
            int r = pending.pollFirst(); // ordem crescente de round
            RoundLog<V> rl = rounds.get(r);
            for (TendermintProposal<V> p : rl.proposals) { // ordem de chegada
                V v = p.value();
                if (rl.precommits.countFor(v) >= quorum.strong() && valid.test(v)) {
                    decide(r, v, out);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Linhas 55-56: f+1 remetentes distintos com mensagem de um round maior: salta para ele. Havendo mais de um
     * round qualificado, vai direto ao MAIOR, para nao difundir propostas de rounds que seriam abandonados em
     * seguida (o artigo admite qualquer um; a escolha muda so o trafego).
     */
    private boolean rule55RoundSkip(List<TendermintAction<V>> out) {
        NavigableMap<Integer, RoundLog<V>> rounds = logs.get(height);
        if (rounds == null) {
            return false;
        }
        for (Map.Entry<Integer, RoundLog<V>> e : rounds.tailMap(round, false).descendingMap().entrySet()) {
            if (e.getValue().senders.size() >= quorum.weak()) {
                startRound(e.getKey(), out);
                return true;
            }
        }
        return false;
    }

    /**
     * Linhas 22-27 (vr = -1) e 28-33 (vr &gt;= 0 com polka de vr). Proposta com vr &gt;= 0 cuja polka ainda nao chegou
     * NAO dispara: o no espera, e se nada chegar o timeoutPropose leva ao prevote nil (comportamento do artigo).
     * Com proponente bizantino pode haver mais de uma proposta: vale a primeira, em ordem de chegada, que habilita a
     * regra (o no vota uma vez so; o step muda).
     */
    private boolean rule22And28Prevote(List<TendermintAction<V>> out) {
        if (step != TendermintStep.PROPOSE) {
            return false;
        }
        RoundLog<V> rl = currentRoundLog();
        if (rl == null) {
            return false;
        }
        for (TendermintProposal<V> p : rl.proposals) {
            V v = p.value();
            int vr = p.validRound();
            boolean accept;
            if (vr == -1) {
                accept = valid.test(v) && (lockedRound == -1 || v.equals(lockedValue));
            } else {
                RoundLog<V> polkaRound = roundLogOrNull(height, vr);
                if (polkaRound == null || polkaRound.prevotes.countFor(v) < quorum.strong()) {
                    continue;
                }
                accept = valid.test(v) && (lockedRound <= vr || v.equals(lockedValue));
            }
            broadcastVote(TendermintVote.Type.PREVOTE, accept ? v : null, out);
            step = TendermintStep.PREVOTE;
            return true;
        }
        return false;
    }

    /**
     * Linhas 36-43: polka no round corrente, com step &gt;= prevote, pela primeira vez. Duas propostas nao podem ter
     * polka no mesmo round (exigiria um correto votando duas vezes), entao a primeira encontrada e a unica.
     */
    private boolean rule36Polka(List<TendermintAction<V>> out) {
        if (polkaHandled || !step.atLeast(TendermintStep.PREVOTE)) {
            return false;
        }
        RoundLog<V> rl = currentRoundLog();
        if (rl == null) {
            return false;
        }
        V v = null;
        for (TendermintProposal<V> p : rl.proposals) {
            if (rl.prevotes.countFor(p.value()) >= quorum.strong() && valid.test(p.value())) {
                v = p.value();
                break;
            }
        }
        if (v == null) {
            return false;
        }
        polkaHandled = true;
        if (step == TendermintStep.PREVOTE) {
            lockedValue = v;
            lockedRound = round;
            broadcastVote(TendermintVote.Type.PRECOMMIT, v, out);
            step = TendermintStep.PRECOMMIT;
        }
        validValue = v;
        validRound = round;
        return true;
    }

    /** Linhas 44-46: 2f+1 PREVOTE nil no round corrente. */
    private boolean rule44PrecommitNil(List<TendermintAction<V>> out) {
        if (step != TendermintStep.PREVOTE) {
            return false;
        }
        RoundLog<V> rl = currentRoundLog();
        if (rl == null || rl.prevotes.nil < quorum.strong()) {
            return false;
        }
        broadcastVote(TendermintVote.Type.PRECOMMIT, null, out);
        step = TendermintStep.PRECOMMIT;
        return true;
    }

    /** Linhas 34-35: 2f+1 PREVOTE de qualquer valor (nil incluso), com step = prevote, pela primeira vez. */
    private boolean rule34PrevoteTimeout(List<TendermintAction<V>> out) {
        if (prevoteTimeoutArmed || step != TendermintStep.PREVOTE) {
            return false;
        }
        RoundLog<V> rl = currentRoundLog();
        if (rl == null || rl.prevotes.senders() < quorum.strong()) {
            return false;
        }
        prevoteTimeoutArmed = true;
        out.add(TendermintAction.timeout(TimeoutKind.PREVOTE, height, round));
        return true;
    }

    /** Linhas 47-48: 2f+1 PRECOMMIT de qualquer valor no round corrente, pela primeira vez (sem condicao de step). */
    private boolean rule47PrecommitTimeout(List<TendermintAction<V>> out) {
        if (precommitTimeoutArmed) {
            return false;
        }
        RoundLog<V> rl = currentRoundLog();
        if (rl == null || rl.precommits.senders() < quorum.strong()) {
            return false;
        }
        precommitTimeoutArmed = true;
        out.add(TendermintAction.timeout(TimeoutKind.PRECOMMIT, height, round));
        return true;
    }

    // ------------------------------------------------------------------------------------------------------
    // Funcoes do artigo
    // ------------------------------------------------------------------------------------------------------

    /** Linhas 11-21. */
    private void startRound(int r, List<TendermintAction<V>> out) {
        round = r;
        step = TendermintStep.PROPOSE;
        prevoteTimeoutArmed = false;
        polkaHandled = false;
        precommitTimeoutArmed = false;
        if (proposerSelector.proposer(height, round) == self) {
            V proposal = validValue != null ? validValue : getValue.apply(height);
            if (proposal == null) {
                throw new IllegalStateException("getValue devolveu nulo na altura " + height);
            }
            TendermintProposal<V> p = new TendermintProposal<>(height, round, proposal, validRound, self);
            RoundLog<V> rl = roundLog(height, round);
            rl.proposals.add(p);
            rl.senders.add(self);
            markForDecisionCheck(height, round);
            out.add(TendermintAction.proposal(p));
        } else {
            out.add(TendermintAction.timeout(TimeoutKind.PROPOSE, height, round));
        }
    }

    /** Linhas 50-54. */
    private void decide(int r, V v, List<TendermintAction<V>> out) {
        decisions.put(height, v);
        out.add(TendermintAction.decide(height, r, v));
        height++;
        lockedValue = null;
        lockedRound = -1;
        validValue = null;
        validRound = -1;
        // esvazia o log das alturas decididas (linha 53); o das futuras fica e deixa de ser "futuro" na nova altura
        logs.headMap(height, false).clear();
        pendingDecisionCheck.headMap(height, false).clear();
        futureMessages -= heightMessageCount(height);
        startRound(0, out);
    }

    private void broadcastVote(TendermintVote.Type type, V value, List<TendermintAction<V>> out) {
        TendermintVote<V> v = new TendermintVote<>(type, height, round, value, self);
        RoundLog<V> rl = roundLog(height, round);
        VoteSet<V> set = rl.votes(type);
        if (set.bySender.containsKey(self)) {
            // So aconteceria por erro deste nucleo: cada (h, r, tipo) e votado uma vez pela maquina de passos.
            throw new IllegalStateException("voto proprio repetido em " + type + " h=" + height + " r=" + round);
        }
        set.add(v, this);
        rl.senders.add(self);
        if (type == TendermintVote.Type.PRECOMMIT) {
            markForDecisionCheck(height, round);
        }
        out.add(TendermintAction.vote(v));
    }

    // ------------------------------------------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------------------------------------------

    private boolean acceptSender(int sender) {
        if (sender < 0 || sender >= n) {
            count(TendermintDiscardReason.SENDER_OUT_OF_RANGE);
            return false;
        }
        if (sender == self) {
            throw new IllegalStateException("mensagem do proprio no " + self
                    + " entregue de volta: o adaptador nao deve reentregar o que o no difunde");
        }
        return true;
    }

    private boolean acceptHeight(long h) {
        if (h < height) {
            count(TendermintDiscardReason.OLD_HEIGHT);
            return false;
        }
        return true;
    }

    private void afterStore(long h, List<TendermintAction<V>> out) {
        if (h > height) {
            futureMessages++;
            peakFutureMessages = Math.max(peakFutureMessages, futureMessages);
            return; // altura futura: nenhuma regra olha para ela agora
        }
        drain(out);
    }

    private void markForDecisionCheck(long h, int r) {
        pendingDecisionCheck.computeIfAbsent(h, k -> new TreeSet<>()).add(r);
    }

    private int heightMessageCount(long h) {
        NavigableMap<Integer, RoundLog<V>> rounds = logs.get(h);
        if (rounds == null) {
            return 0;
        }
        int c = 0;
        for (RoundLog<V> rl : rounds.values()) {
            c += rl.messageCount();
        }
        return c;
    }

    private RoundLog<V> roundLog(long h, int r) {
        return logs.computeIfAbsent(h, k -> new TreeMap<>()).computeIfAbsent(r, k -> new RoundLog<>());
    }

    private RoundLog<V> roundLogOrNull(long h, int r) {
        NavigableMap<Integer, RoundLog<V>> rounds = logs.get(h);
        return rounds == null ? null : rounds.get(r);
    }

    private RoundLog<V> currentRoundLog() {
        return roundLogOrNull(height, round);
    }

    private void count(TendermintDiscardReason reason) {
        discards.merge(reason, 1L, Long::sum);
    }

    private void requireStarted() {
        if (!started) {
            throw new IllegalStateException("nucleo nao iniciado: chame start()");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------------------------------------------

    public int self() { return self; }
    public int n() { return n; }
    public TendermintQuorum quorum() { return quorum; }
    public boolean isStarted() { return started; }
    public long height() { return height; }
    public int round() { return round; }
    public TendermintStep step() { return step; }
    public V lockedValue() { return lockedValue; }
    public int lockedRound() { return lockedRound; }
    public V validValue() { return validValue; }
    public int validRound() { return validRound; }

    /** Valor decidido na altura h, ou nulo. */
    public V decision(long h) { return decisions.get(h); }

    /** Todas as decisoes, por altura (somente leitura). */
    public NavigableMap<Long, V> decisions() { return Collections.unmodifiableNavigableMap(decisions); }

    public long discardCount(TendermintDiscardReason reason) { return discards.getOrDefault(reason, 0L); }

    public long totalDiscards() {
        long t = 0;
        for (long v : discards.values()) {
            t += v;
        }
        return t;
    }

    /** Mensagens guardadas agora para alturas futuras. */
    public int futureMessages() { return futureMessages; }

    /** Pico de {@link #futureMessages()} na vida deste nucleo (para o cenario relatar). */
    public int peakFutureMessages() { return peakFutureMessages; }

    /**
     * Mensagens recebidas que contradizem outra do mesmo remetente no mesmo (h, r, tipo). Sao GUARDADAS e contam
     * para o valor que assinam (ver {@code RoundLog}); este contador e evidencia de comportamento bizantino.
     */
    public long equivocationsObserved() { return equivocations; }
}

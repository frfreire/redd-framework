#!/usr/bin/env python3
r"""Evidencia de nao regressao (criterio 7) para protocolos NAO tocados pela T1 (ADR-0006).

Compara, com semente fixa, o codigo ATUAL contra uma BASELINE (copia do codigo original) e
mede a variabilidade da propria baseline (rodando-a duas vezes).

Para cada caso PROTOCOLO:N:DURACAO roda 4 execucoes independentes (processos JVM em paralelo,
cada uma com timeout proprio e saida isolada):
    base-a, base-b  (codigo original, mesma semente)
    atual-a, atual-b (codigo atual,   mesma semente)
e compara os CSV (colunas com 'hash' e linhas '#' ignoradas, como em checar-determinismo.py):
    base-a  x base-b   -> a baseline e deterministica? (se nao, divergencia e PRE-EXISTENTE)
    atual-a x atual-b  -> o atual e deterministico?
    base-a  x atual-a  -> regressao introduzida pela T1?

Por que o checar-determinismo.py estourou 120 s: ele roda com n pequeno (padrao --validadores 7).
Com n pequeno o setup da rede NAO TERMINA (nao e lentidao): AbstractBlockchainP2PConnections.
connectToNetwork repete ate achar numOutboundConnections vizinhos distintos e nao ha vizinhos
suficientes. Medido: Casper/MCO2 e Parlia/AMBIFY n=7 e Nakamoto/OFFSET n<=20 travam (rc=124 em 40 s);
OFFSET n=30 termina em 1 s. Parlia/Casper exigem n >= 10 (AGENTS.md); OFFSET exige n >= ~30.
Por isso os casos abaixo usam n e duracao (parametros --casos) que terminam em < 1 s.

Como reconstruir a baseline (nao ha commit): arvore isolada fora do repositorio com o src/main/java
atual, trocando os 4 arquivos tocados pela T1 pela versao ORIGINAL guardada em
src/test/resources/nao-regressao/baseline-original/*.txt (sha256 conferido contra /tmp/opencode/arq,
copia feita antes da T1). Compilar ambas com javac --release 17 (mvn clean compile no repo tambem
serve, mas aqui compilamos fora para nao tocar target/) e passar --base/--atual como raiz que
contem target/classes e target/cp.txt (classpath das dependencias).

Uso:
  nao_regressao.py --base /tmp/opencode/nr-build/base --atual /tmp/opencode/nr-build/atual \
      --casos MCO2:10:60,MCO2:50:600,AMBIFY:10:60,OFFSET:40:20000 --semente 1 --timeout 600
Saida: 0 = nenhuma regressao; 1 = divergencia baseline x atual; 2 = erro/timeout (INCONCLUSIVO).
(Excecao antiga, mantida: ausencia de target/classes ou de classpath sai com 1 via sys.exit(mensagem).)
A divergencia baseline-a x baseline-b (nao determinismo pre-existente) e IMPRESSA, mas nao muda o
codigo de saida: ela e a prova de que a diferenca nao vem da T1.

Flags extras por protocolo: `--extra-flag-protocolo PROTO:--chave=valor` (repetivel). A flag e repassada
ao jabs.example.MVPComparison SO nos casos de PROTO e nas QUATRO execucoes do caso (base-a, base-b,
atual-a, atual-b: mesmas flags dos dois lados, senao a comparacao nao vale). Casos de outros protocolos
nao recebem nada, entao --casos pode misturar protocolos sem poluir a comparacao (a baseline so AVISA
sobre flag desconhecida, mas assim nem isso). Cada item deve ser `--chave=valor`; nao pode repetir chave
para o mesmo protocolo nem redefinir flags que o script monta (protocols, validators, trials, duration,
seed, output); PROTO precisa aparecer em --casos (senao a flag seria ignorada em silencio). Sem
`--extra-flag-protocolo` o comando gerado e identico ao das versoes anteriores.

TENDERMINT e RECUSADO aqui (saida 2, antes de iniciar qualquer JVM), com ou sem flags: a baseline
(commit 5102fe2) nao tem Tendermint, entao nao existe comparacao base x atual para ele. Para exercitar o
Tendermint use `checar-determinismo.py` (atual x atual, mesma semente), que exige e valida as 11 flags
--tm-* (sem valor padrao; ADR-0010 PI-08/PI-16: a definir com o humano).
Exemplo (valor SOMENTE DE EXEMPLO, NAO e valor de pesquisa; --foo-exemplo nao existe, so ilustra a forma):
  nao_regressao.py --base B --atual A --casos MCO2:10:60,AMBIFY:10:60 \
      --extra-flag-protocolo MCO2:--foo-exemplo=1
HOTSTUFF (D3a-2a-2c-4) tambem e RECUSADO aqui por padrao (saida 2, antes de qualquer JVM): a baseline 45eb3e7 nao tem
`case HOTSTUFF` no MVPComparison (o harness engoliria "Unknown protocol" por trial e o caso viraria SEM_DADOS ou
falso verde). O que este script prova para HOTSTUFF e o contrario: que os OUTROS protocolos nao mudaram com a
chegada do HOTSTUFF; para exercitar o proprio HotStuff use `checar-determinismo.py` com as 11 flags --hs-*.
`--baseline-tem TENDERMINT` libera o TENDERMINT quando a baseline passada em --base JA o tem (ex.: 45eb3e7, que
tem `case TENDERMINT` no MVPComparison). Sem a liberacao a recusa acima continua. Se a baseline NAO tiver o caso,
o MVPComparison dela engole "Unknown protocol" e o caso sai SEM_DADOS (saida 2): nunca um falso IDENTICO.
As flags --tm-* entram por `--extra-flag-protocolo TENDERMINT:--tm-...=valor`, iguais nos quatro lados.

`--baseline-tem HOTSTUFF` (D3a-2a-2d-2) libera o HOTSTUFF do mesmo modo, para baseline que o tenha (40ac8ab tem `case
HOTSTUFF` desde 6bc9fad). As 11 flags --hs-* entram por `--extra-flag-protocolo HOTSTUFF:--hs-...=valor`, iguais nos
quatro lados; sao FIXTURE, nunca calibracao. Caso HOTSTUFF cujo CSV so tenha cabecalho sai SEM_DADOS (saida 2).

Opcoes da JVM: `--jvm-flag=-XX:...` (repetivel) vai antes de `-cp` nas QUATRO execucoes. Serve para provar
Casper/Parlia/Nakamoto, cuja saida depende da sequencia de System.identityHashCode (ADR-0012/0013, N1): use
`--jvm-flag=-XX:+UnlockExperimentalVMOptions --jvm-flag=-XX:hashCode=2` nos dois lados. Sem `--jvm-flag` o comando
e identico ao das versoes anteriores.
EXCECAO (ADR-0012, M1a-1 rodada 3): se algum caso liga o registro de carga (`--extra-flag-protocolo
PROTO:--load-record=true`), `--jvm-flag=-XX:+UnlockExperimentalVMOptions` e `--jvm-flag=-XX:hashCode=2` passam a ser
OBRIGATORIOS e o script recusa (saida 2, antes de iniciar qualquer JVM) sem eles; a garantia "ligar o registro nao altera
a saida" so vale com o hash de identidade fixado e o proprio MVPComparison tambem falha alto sem a flag.
Autoteste sem JVM: `nao_regressao.py --selftest`.
"""
import argparse
import csv
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def classpath(raiz: Path) -> str:
    cp = raiz / "target" / "cp.txt"
    if not cp.exists():
        r = subprocess.run(["mvn", "-q", "-o", "dependency:build-classpath", f"-Dmdep.outputFile={cp}"],
                           capture_output=True, text=True, cwd=str(raiz))
        if r.returncode != 0 or not cp.exists():
            sys.exit(f"ERRO: classpath de {raiz}: {r.stderr[-300:]}")
    return f"{raiz / 'target' / 'classes'}:{cp.read_text().strip()}"


# Flags que montar_cmd ja define: o chamador nao pode redefini-las por --extra-flag-protocolo.
FLAGS_RESERVADAS = ("--protocols", "--validators", "--trials", "--duration", "--seed", "--output")


def montar_cmd(cp, proto, n, dur, semente, saida, extras=(), jvm=()):
    """Linha de comando do MVPComparison (funcao pura). Sem extras e sem jvm = comando das versoes anteriores.
    jvm: opcoes da JVM (antes de -cp), as MESMAS nos quatro lados (ex.: hash de identidade fixo, ADR-0012)."""
    return ["java", *jvm, "-cp", cp, "jabs.example.MVPComparison", f"--protocols={proto}",
            f"--validators={n}", "--trials=1", f"--duration={dur}", f"--seed={semente}", f"--output={saida}",
            *extras]


def erro_registro_sem_hashcode(extras_por_proto, jvm):
    """Mensagem de erro (ou None). Se algum caso liga o registro de carga (--load-record=true), as flags de JVM que
    fixam o hash de identidade sao obrigatorias (a garantia de nao regressao so vale com elas). Funcao pura."""
    liga = sorted(p for p, fl in extras_por_proto.items() if "--load-record=true" in fl)
    if not liga:
        return None
    faltam = [f for f in ("-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2") if f not in jvm]
    if not faltam:
        return None
    return (f"GARANTIA INVALIDA: o(s) caso(s) {', '.join(liga)} ligam o registro de carga (--load-record=true), cuja "
            f"garantia de nao regressao so vale com o hash de identidade fixado. Faltam: "
            f"{' '.join('--jvm-flag=' + f for f in faltam)} (passe --jvm-flag=-XX:+UnlockExperimentalVMOptions "
            f"--jvm-flag=-XX:hashCode=2). Nenhuma JVM foi iniciada.")


def parse_extras_protocolo(itens, protos_dos_casos, liberados=()):
    """Converte ['PROTO:--chave=valor', ...] em {PROTO: ['--chave=valor', ...]} (PROTO em maiuscula).
    ValueError se: formato ruim, item sem '--', chave repetida ou reservada, TENDERMINT (recusado: a baseline
    nao tem Tendermint; use checar-determinismo.py) ou PROTO fora de --casos."""
    por_proto, chaves = {}, set()
    casos = {x.upper() for x in protos_dos_casos}
    for item in itens:
        proto, sep, flag = item.partition(":")
        proto = proto.strip().upper()
        if not sep or not proto or not flag.startswith("--") or "=" not in flag or flag.startswith("--="):
            raise ValueError(f"--extra-flag-protocolo invalido {item!r}: use PROTO:--chave=valor")
        chave = flag.split("=", 1)[0]
        if proto == "TENDERMINT" and proto not in {x.upper() for x in liberados}:
            raise ValueError(recusa_tendermint())
        if proto == "HOTSTUFF" and proto not in {x.upper() for x in liberados}:
            raise ValueError(recusa_hotstuff())
        if chave in FLAGS_RESERVADAS:
            raise ValueError(f"--extra-flag-protocolo {item!r} redefine {chave}, que este script ja monta")
        if (proto, chave) in chaves:
            raise ValueError(f"--extra-flag-protocolo repete {chave} para {proto}")
        if proto not in casos:
            raise ValueError(f"--extra-flag-protocolo {item!r}: {proto} nao esta em --casos (a flag seria ignorada)")
        chaves.add((proto, chave))
        por_proto.setdefault(proto, []).append(flag)
    return por_proto


def recusa_tendermint():
    return ("TENDERMINT nao e suportado em nao_regressao.py: a baseline (commit 5102fe2) nao tem Tendermint, "
            "entao nao existe comparacao base x atual. Use scripts/jabs/checar-determinismo.py "
            "(atual x atual, mesma semente) com as 11 flags --tm-* via --extra-flag.")


def recusa_hotstuff():
    return ("HOTSTUFF nao e suportado em nao_regressao.py sem `--baseline-tem HOTSTUFF`: uma baseline anterior a 6bc9fad "
            "nao tem `case HOTSTUFF` no MVPComparison, entao nao existe comparacao base x atual. Use "
            "scripts/jabs/checar-determinismo.py (atual x atual, mesma semente) com as 11 flags --hs-* via --extra-flag, "
            "ou passe uma baseline que o tenha (ex.: 40ac8ab) com `--baseline-tem HOTSTUFF`.")


def executar(raiz: Path, proto, n, dur, semente, saida: Path, tmax, extras=(), jvm=()):
    saida.mkdir(parents=True, exist_ok=True)
    cmd = montar_cmd(classpath(raiz), proto, n, dur, semente, saida, extras, jvm)
    t0 = time.time()
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=tmax, cwd=str(raiz))
        (saida.parent / (saida.name + ".log")).write_text(r.stdout + r.stderr)
        return r.returncode, time.time() - t0
    except subprocess.TimeoutExpired:
        return "TIMEOUT", time.time() - t0


def ler(path: Path):
    linhas = [l for l in path.read_text(encoding="utf-8", errors="replace").splitlines()
              if l.strip() and not l.startswith("#")]
    if not linhas:
        return [], []
    delim = ";" if linhas[0].count(";") > linhas[0].count(",") else ","
    rows = list(csv.reader(linhas, delimiter=delim))
    manter = [i for i, c in enumerate(rows[0]) if "hash" not in c.lower()]
    return [rows[0][i] for i in manter], [[r[i] for i in manter if i < len(r)] for r in rows[1:]]


EXTRA_ATUAL = "TrafficConsensusUntilFinalization"  # coluna nova da T1 (ADR-0006); -1 = nao medido

# HS-CB-fase2 (B3): arquivos ADITIVOS CONHECIDOS. A Parte A do HS-CB-fase2 gera, so para HOTSTUFF e TENDERMINT, um
# segundo arquivo ao lado do mvp-results.csv; a baseline nao o tem. Ele sai da comparacao da LISTA de arquivos, de forma
# explicita: so os nomes desta tupla, so na raiz da pasta de saida (caminho relativo == nome), nunca por padrao/regex.
# Qualquer outro arquivo extra continua divergindo, e o conteudo do aditivo e verificado a parte (atual a x atual b,
# comparar_aditivo), porque nao existe base dele.
ARQUIVOS_ADITIVOS = ("mvp-results-cb.csv",)
# Protocolos em que o aditivo TEM de existir no atual (MVPComparison.hasCbRede); nos demais ele nao pode aparecer.
PROTOCOLOS_COM_ADITIVO = ("HOTSTUFF", "TENDERMINT")
# Maximo de JVMs simultaneas (B1/HS-CB-fase2): com ate 4 nao houve divergencia em 100 execucoes por protocolo; carga
# alta degrada o determinismo da escrita do CSV (AbstractCSVLogger.flushBuffer sem sincronizacao). Ver README do hs-cb-fase2.
MAX_PARALELO = 4


def listar_csv(raiz: Path, ignorar_aditivos=True):
    """CSV sob raiz (caminho relativo, ordenado). ignorar_aditivos=True tira so ARQUIVOS_ADITIVOS na raiz da pasta."""
    todos = sorted(p.relative_to(raiz) for p in raiz.rglob("*.csv"))
    if not ignorar_aditivos:
        return todos
    return [p for p in todos if not (len(p.parts) == 1 and p.name in ARQUIVOS_ADITIVOS)]


def comparar_aditivo(a: Path, b: Path, nome: str, esperado: bool):
    """Determinismo atual x atual do arquivo aditivo `nome` (nao existe base). Mesmo criterio do projeto ('#' e colunas
    'hash' ignoradas). esperado=True: o protocolo deveria te-lo gerado; ausencia nos dois lados e DIVERGENTE.
    esperado=False: o aditivo NAO pode aparecer (protocolo sem C_B de rede); se aparecer, DIVERGENTE.
    Retorna (veredito, detalhe, linhas). Veredito: IDENTICO, DIVERGENTE, SEM_DADOS ou N/A (nao esperado e ausente)."""
    pa, pb = a / nome, b / nome
    if not pa.exists() and not pb.exists():
        return ("DIVERGENTE", f"{nome} ausente nos dois lados (esperado)", 0) if esperado else ("N/A", "", 0)
    if not esperado:
        return "DIVERGENTE", f"{nome} apareceu em protocolo que nao deveria te-lo", 0
    if pa.exists() != pb.exists():
        return "DIVERGENTE", f"{nome} existe so em um dos lados", 0
    ca, ra = ler(pa)
    cb, rb = ler(pb)
    if not ra:
        return "SEM_DADOS", f"{nome} sem linhas de dados", 0
    if ca != cb:
        return "DIVERGENTE", f"{nome}: cabecalho {ca} vs {cb}", len(ra)
    if len(ra) != len(rb):
        return "DIVERGENTE", f"{nome}: {len(ra)} vs {len(rb)} linhas", len(ra)
    for i, (x, y) in enumerate(zip(ra, rb), 1):
        if x != y:
            d = [(ca[j], x[j], y[j]) for j in range(min(len(x), len(y))) if x[j] != y[j]][:3]
            return "DIVERGENTE", f"{nome} linha {i}: {d}", len(ra)
    return "IDENTICO", "", len(ra)


def comparar(a: Path, b: Path, extra_ok=False):
    """Retorna (veredito, detalhe, linhas_comparadas). a = referencia, b = candidato.
    extra_ok: aceita em b a coluna EXTRA_ATUAL ausente em a, desde que valha -1 em todas as linhas
    (esquema ampliado, protocolo que nao mede a decomposicao); a coluna sai antes de comparar.
    Arquivos de ARQUIVOS_ADITIVOS (raiz da pasta) ficam fora da lista de arquivos comparada (ver acima)."""
    fa = listar_csv(a)
    fb = listar_csv(b)
    if not fa:
        return "SEM_DADOS", "nenhum CSV gerado", 0
    if fa != fb:
        return "DIVERGENTE", f"arquivos diferem {fa} vs {fb}", 0
    total, notas, extras = 0, [], 0
    for rel in fa:
        ca, ra = ler(a / rel)
        cb, rb = ler(b / rel)
        if extra_ok and EXTRA_ATUAL in cb and EXTRA_ATUAL not in ca:
            k = cb.index(EXTRA_ATUAL)
            if any(len(r) > k and r[k] != "-1" for r in rb):
                notas.append(f"{rel}: {EXTRA_ATUAL} diferente de -1")
            extras += 1
            cb = cb[:k] + cb[k + 1:]
            rb = [r[:k] + r[k + 1:] for r in rb]
        total += len(ra)
        if ca != cb:
            notas.append(f"{rel}: cabecalho {ca} vs {cb}")
        elif len(ra) != len(rb):
            notas.append(f"{rel}: {len(ra)} vs {len(rb)} linhas")
        else:
            for i, (x, y) in enumerate(zip(ra, rb), 1):
                if x != y:
                    d = [(ca[j], x[j], y[j]) for j in range(min(len(x), len(y))) if x[j] != y[j]][:3]
                    notas.append(f"{rel} linha {i}: {d}")
                    break
    if total == 0:
        return "SEM_DADOS", "CSV sem linhas de dados", 0
    if extras and not notas:
        return "IDENTICO", f"(coluna nova {EXTRA_ATUAL}=-1 em todas as linhas, removida da comparacao)", total
    return ("IDENTICO" if not notas else "DIVERGENTE"), "; ".join(notas), total


def selftest():
    """Autoteste sem JVM. Nenhum valor de pesquisa aqui; 'foo' e flag ficticia so de exemplo."""
    antigo = ["java", "-cp", "CP", "jabs.example.MVPComparison", "--protocols=MCO2", "--validators=10",
              "--trials=1", "--duration=60", "--seed=1", "--output=/tmp/x"]
    assert montar_cmd("CP", "MCO2", "10", "60", 1, Path("/tmp/x")) == antigo
    assert montar_cmd("CP", "MCO2", "10", "60", 1, Path("/tmp/x"), []) == antigo
    assert montar_cmd("CP", "MCO2", "10", "60", 1, Path("/tmp/x"), (), ["-XX:hashCode=2"])[:3] == ["java", "-XX:hashCode=2", "-cp"]
    assert parse_extras_protocolo(["TENDERMINT:--tm-batch-size=1"], ["TENDERMINT"], ["TENDERMINT"]) == {"TENDERMINT": ["--tm-batch-size=1"]}
    assert parse_extras_protocolo(["HOTSTUFF:--hs-batch-size=1"], ["HOTSTUFF"], ["hotstuff"]) == {"HOTSTUFF": ["--hs-batch-size=1"]}
    assert parse_extras_protocolo([], ["MCO2"]) == {}
    ok = parse_extras_protocolo(["mco2:--foo=1", "MCO2:--bar=2"], ["MCO2", "AMBIFY"])
    assert ok == {"MCO2": ["--foo=1", "--bar=2"]}, ok
    assert montar_cmd("CP", "MCO2", "10", "60", 1, Path("/tmp/x"), ok["MCO2"])[-2:] == ["--foo=1", "--bar=2"]
    assert "AMBIFY" not in ok  # caso sem flag: comando inalterado
    for ruim in (["MCO2"], ["MCO2:foo=1"], ["MCO2:--semsinal"], ["MCO2:--seed=2"], ["MCO2:--foo=1", "MCO2:--foo=2"],
                 ["TENDERMINT:--tm-batch-size=1"], ["HOTSTUFF:--hs-batch-size=1"], ["OFFSET:--foo=1"], [":--foo=1"]):
        try:
            parse_extras_protocolo(ruim, ["MCO2"])
        except ValueError:
            continue
        raise AssertionError(f"deveria rejeitar {ruim!r}")
    # registro de carga ligado exige as flags de hash (ADR-0012, M1a-1 rodada 3)
    h2 = ["-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2"]
    lig = {"ARQ": ["--load-record=true", "--load-record-t0=10"]}
    assert erro_registro_sem_hashcode({}, []) is None  # sem registro: comando e exigencias inalterados
    assert erro_registro_sem_hashcode({"MCO2": ["--foo=1"]}, []) is None
    assert erro_registro_sem_hashcode({"ARQ": ["--load-record=false"]}, []) is None
    assert erro_registro_sem_hashcode(lig, h2) is None
    for ruim in ([], ["-XX:hashCode=2"], ["-XX:+UnlockExperimentalVMOptions"], ["-XX:hashCode=5", "-XX:+UnlockExperimentalVMOptions"]):
        m = erro_registro_sem_hashcode(lig, ruim)
        assert m and m.startswith("GARANTIA INVALIDA"), (ruim, m)
    assert "--jvm-flag=-XX:hashCode=2" in erro_registro_sem_hashcode(lig, [])
    assert "Faltam: --jvm-flag=-XX:hashCode=2 (" in erro_registro_sem_hashcode(lig, ["-XX:+UnlockExperimentalVMOptions"])
    selftest_aditivo()
    print("SELFTEST OK")
    return 0


def selftest_aditivo():
    """B3: arquivo aditivo ignorado; extra desconhecido ainda diverge; coluna alterada em mvp-results.csv ainda diverge;
    aditivo atual x atual (determinismo) e presenca coerente com o protocolo. Sem JVM, em diretorio temporario."""
    import tempfile
    cab = "project;n;trials;Tb_avg\n"
    cb = "# c\nprotocolo;n;CbRede_avg_B\nHOTSTUFF;4;100\n"

    def pasta(raiz, nome, arquivos):
        p = Path(raiz) / nome
        p.mkdir(parents=True)
        for f, c in arquivos.items():
            (p / f).write_text(c)
        return p

    with tempfile.TemporaryDirectory() as t:
        base = pasta(t, "base", {"mvp-results.csv": cab + "HOTSTUFF;4;1;1.0\n"})
        atual = pasta(t, "atual", {"mvp-results.csv": cab + "HOTSTUFF;4;1;1.0\n", "mvp-results-cb.csv": cb})
        # 1. aditivo conhecido ignorado na lista de arquivos
        assert comparar(base, atual)[0] == "IDENTICO", comparar(base, atual)
        # 2. arquivo extra DESCONHECIDO ainda diverge (nem por prefixo parecido, nem em subpasta)
        for extra in ("outro.csv", "mvp-results-cb2.csv", "x-mvp-results-cb.csv"):
            a2 = pasta(t, "a-" + extra, {"mvp-results.csv": cab + "HOTSTUFF;4;1;1.0\n", extra: cb})
            v = comparar(base, a2)
            assert v[0] == "DIVERGENTE" and "arquivos diferem" in v[1], (extra, v)
        sub = pasta(t, "a-sub", {"mvp-results.csv": cab + "HOTSTUFF;4;1;1.0\n"})
        (sub / "tmp").mkdir()
        (sub / "tmp" / "mvp-results-cb.csv").write_text(cb)  # mesmo nome, mas fora da raiz: NAO e o aditivo conhecido
        assert comparar(base, sub)[0] == "DIVERGENTE"
        # 3. coluna alterada em mvp-results.csv ainda diverge, mesmo com o aditivo presente
        alt = pasta(t, "a-alt", {"mvp-results.csv": cab + "HOTSTUFF;4;1;2.0\n", "mvp-results-cb.csv": cb})
        v = comparar(base, alt)
        assert v[0] == "DIVERGENTE" and "Tb_avg" in v[1], v
        # 4. o aditivo some do lado base tambem (baseline antiga que ja tivesse o arquivo): continua ignorado
        base2 = pasta(t, "base2", {"mvp-results.csv": cab + "HOTSTUFF;4;1;1.0\n", "mvp-results-cb.csv": cb})
        assert comparar(base2, atual)[0] == "IDENTICO"
        # 5. aditivo atual x atual: igual / conteudo diferente / so um lado / ausente esperado / presente nao esperado
        atual_b = pasta(t, "atual-b", {"mvp-results.csv": cab + "HOTSTUFF;4;1;1.0\n", "mvp-results-cb.csv": cb})
        n = "mvp-results-cb.csv"
        assert comparar_aditivo(atual, atual_b, n, True)[0] == "IDENTICO"
        assert comparar_aditivo(atual, atual_b, n, True)[2] == 1
        outra = pasta(t, "atual-c", {"mvp-results.csv": cab, n: cb.replace("100", "101")})
        v = comparar_aditivo(atual, outra, n, True)
        assert v[0] == "DIVERGENTE" and "CbRede_avg_B" in v[1], v
        so_um = pasta(t, "atual-d", {"mvp-results.csv": cab})
        assert comparar_aditivo(atual, so_um, n, True)[0] == "DIVERGENTE"
        assert comparar_aditivo(so_um, so_um, n, True)[0] == "DIVERGENTE"  # esperado e ausente nos dois
        assert comparar_aditivo(so_um, so_um, n, False)[0] == "N/A"
        assert comparar_aditivo(atual, atual_b, n, False)[0] == "DIVERGENTE"  # ARQ & cia nao podem ter aditivo
        so_hash = pasta(t, "atual-e", {"mvp-results.csv": cab, n: "# outro comentario\nprotocolo;n;CbRede_avg_B\nHOTSTUFF;4;100\n"})
        assert comparar_aditivo(atual, so_hash, n, True)[0] == "IDENTICO"  # '#' ignorado
    assert ARQUIVOS_ADITIVOS == ("mvp-results-cb.csv",)  # lista explicita, nao regex
    assert MAX_PARALELO == 4


def main():
    if "--selftest" in sys.argv[1:]:
        return selftest()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True)
    ap.add_argument("--atual", required=True)
    ap.add_argument("--casos", default="MCO2:10:60,AMBIFY:10:60,OFFSET:10:60")
    ap.add_argument("--semente", type=int, default=1)
    ap.add_argument("--timeout", type=int, default=600, help="segundos por execucao")
    ap.add_argument("--saida", default="/tmp/opencode/nr")
    ap.add_argument("--extra-flag-protocolo", dest="extra_proto", action="append", default=[],
                    metavar="PROTO:--CHAVE=VALOR",
                    help="flag repassada ao MVPComparison so nos casos de PROTO, nos dois lados (repetivel). "
                         "TENDERMINT e HOTSTUFF so passam com --baseline-tem (a baseline precisa te-los).")
    ap.add_argument("--jvm-flag", dest="jvm_flag", action="append", default=[], metavar="-XX:OPCAO",
                    help="opcao da JVM, repassada nas quatro execucoes (repetivel; use --jvm-flag=-XX:hashCode=2)")
    ap.add_argument("--baseline-tem", dest="baseline_tem", action="append", default=[], metavar="PROTO",
                    help="libera PROTO (TENDERMINT ou HOTSTUFF) quando a baseline de --base ja o tem; ver o cabecalho")
    ap.add_argument("--selftest", action="store_true", help="autoteste sem JVM e sai")
    ap.add_argument("--paralelo", type=int, default=MAX_PARALELO,
                    help=f"JVMs simultaneas (padrao e maximo {MAX_PARALELO}; B1/HS-CB-fase2: o determinismo da escrita de "
                         "CSV se degrada sob carga alta, ver AbstractCSVLogger)")
    a = ap.parse_args()
    if not 1 <= a.paralelo <= MAX_PARALELO:
        print(f"ERRO: --paralelo deve estar em 1..{MAX_PARALELO} (recebido {a.paralelo}): acima disso a escrita concorrente "
              "do CSV (AbstractCSVLogger) pode produzir falso DIVERGENTE")
        return 2
    for b in a.baseline_tem:
        if b.strip().upper() not in ("TENDERMINT", "HOTSTUFF"):
            print(f"ERRO: --baseline-tem so aceita TENDERMINT ou HOTSTUFF (recebido {b!r})")
            return 2
    for j in a.jvm_flag:
        if not j.startswith("-X"):
            print(f"ERRO: --jvm-flag invalido {j!r}: use opcao da JVM comecando com -X")
            return 2
    protos = [c.split(":")[0] for c in a.casos.split(",")]
    liberados = {b.strip().upper() for b in a.baseline_tem}
    if any(p.strip().upper() == "TENDERMINT" for p in protos) and "TENDERMINT" not in liberados:
        print(f"ERRO: {recusa_tendermint()}")
        return 2
    if any(p.strip().upper() == "HOTSTUFF" for p in protos) and "HOTSTUFF" not in liberados:
        print(f"ERRO: {recusa_hotstuff()}")
        return 2
    try:
        extras = parse_extras_protocolo(a.extra_proto, protos, a.baseline_tem)
    except ValueError as e:
        print(f"ERRO: {e}")
        return 2
    e_hash = erro_registro_sem_hashcode(extras, a.jvm_flag)
    if e_hash:
        print(f"ERRO: {e_hash}")
        return 2
    base, atual, out = Path(a.base).resolve(), Path(a.atual).resolve(), Path(a.saida)
    for r in (base, atual):
        if not (r / "target" / "classes").exists():
            sys.exit(f"ERRO: {r}/target/classes ausente (mvn -q -o clean compile)")
    jobs = []
    for caso in a.casos.split(","):
        proto, n, dur = caso.split(":")
        for lado, raiz in (("base", base), ("atual", atual)):
            for rep in ("a", "b"):
                jobs.append((proto, n, dur, lado, rep, raiz))
    print(f"semente={a.semente} timeout={a.timeout}s por execucao; {len(jobs)} execucoes, no maximo {min(a.paralelo, len(jobs))} JVM simultaneas")
    if a.jvm_flag:
        print("opcoes da JVM (nas quatro execucoes): " + " ".join(a.jvm_flag))
    if extras:
        print("flags extras (nos dois lados, so no protocolo indicado): " +
              "; ".join(f"{p}: {' '.join(f)}" for p, f in extras.items()))
    with ThreadPoolExecutor(max_workers=min(a.paralelo, len(jobs))) as ex:
        fut = {j: ex.submit(executar, j[5], j[0], j[1], j[2], a.semente,
                            out / f"{j[0]}-{j[1]}v-{j[2]}s-{j[3]}-{j[4]}", a.timeout,
                            extras.get(j[0].strip().upper(), ()), a.jvm_flag) for j in jobs}
        res = {j: f.result() for j, f in fut.items()}
    pior = 0
    for caso in a.casos.split(","):
        proto, n, dur = caso.split(":")
        d = lambda lado, rep: out / f"{proto}-{n}v-{dur}s-{lado}-{rep}"
        st = {(l, r): res[(proto, n, dur, l, r, base if l == "base" else atual)] for l in ("base", "atual") for r in "ab"}
        print(f"\n== {proto} n={n} duracao={dur}s semente={a.semente}")
        print("   execucoes (rc, segundos): " + ", ".join(f"{l}-{r}={st[(l, r)][0]},{st[(l, r)][1]:.0f}s" for (l, r) in st))
        if any(v[0] != 0 for v in st.values()):
            print("   INCONCLUSIVO: execucao com erro/timeout")
            pior = max(pior, 2)
            continue
        for rot, x, y in (("baseline a x b (pre-existente?)", d("base", "a"), d("base", "b")),
                          ("atual    a x b                ", d("atual", "a"), d("atual", "b")),
                          ("baseline a x atual a (REGRESSAO?)", d("base", "a"), d("atual", "a"))):
            v, det, lin = comparar(x, y, extra_ok=rot.startswith("baseline a x atual"))
            print(f"   {rot}: {v} ({lin} linhas) {det}")
            if v == "SEM_DADOS":
                pior = max(pior, 2)
            elif v == "DIVERGENTE" and rot.startswith("baseline a x atual"):
                pior = max(pior, 1)
        # B3: o aditivo nao tem base; so determinismo atual a x atual b (e presenca/ausencia coerente com o protocolo)
        esperado = proto.strip().upper() in PROTOCOLOS_COM_ADITIVO
        for nome in ARQUIVOS_ADITIVOS:
            v, det, lin = comparar_aditivo(d("atual", "a"), d("atual", "b"), nome, esperado)
            if v != "N/A":
                print(f"   aditivo {nome} atual a x b (sem base): {v} ({lin} linhas) {det}")
            if v == "DIVERGENTE":
                pior = max(pior, 1)
            elif v == "SEM_DADOS":
                pior = max(pior, 2)
    return pior


if __name__ == "__main__":
    sys.exit(main())

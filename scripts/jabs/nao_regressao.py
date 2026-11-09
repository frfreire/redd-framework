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


def montar_cmd(cp, proto, n, dur, semente, saida, extras=()):
    """Linha de comando do MVPComparison (funcao pura). Sem extras = comando das versoes anteriores."""
    return ["java", "-cp", cp, "jabs.example.MVPComparison", f"--protocols={proto}",
            f"--validators={n}", "--trials=1", f"--duration={dur}", f"--seed={semente}", f"--output={saida}",
            *extras]


def parse_extras_protocolo(itens, protos_dos_casos):
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
        if proto == "TENDERMINT":
            raise ValueError(recusa_tendermint())
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


def executar(raiz: Path, proto, n, dur, semente, saida: Path, tmax, extras=()):
    saida.mkdir(parents=True, exist_ok=True)
    cmd = montar_cmd(classpath(raiz), proto, n, dur, semente, saida, extras)
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


def comparar(a: Path, b: Path, extra_ok=False):
    """Retorna (veredito, detalhe, linhas_comparadas). a = referencia, b = candidato.
    extra_ok: aceita em b a coluna EXTRA_ATUAL ausente em a, desde que valha -1 em todas as linhas
    (esquema ampliado, protocolo que nao mede a decomposicao); a coluna sai antes de comparar."""
    fa = sorted(p.relative_to(a) for p in a.rglob("*.csv"))
    fb = sorted(p.relative_to(b) for p in b.rglob("*.csv"))
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
    assert parse_extras_protocolo([], ["MCO2"]) == {}
    ok = parse_extras_protocolo(["mco2:--foo=1", "MCO2:--bar=2"], ["MCO2", "AMBIFY"])
    assert ok == {"MCO2": ["--foo=1", "--bar=2"]}, ok
    assert montar_cmd("CP", "MCO2", "10", "60", 1, Path("/tmp/x"), ok["MCO2"])[-2:] == ["--foo=1", "--bar=2"]
    assert "AMBIFY" not in ok  # caso sem flag: comando inalterado
    for ruim in (["MCO2"], ["MCO2:foo=1"], ["MCO2:--semsinal"], ["MCO2:--seed=2"], ["MCO2:--foo=1", "MCO2:--foo=2"],
                 ["TENDERMINT:--tm-batch-size=1"], ["OFFSET:--foo=1"], [":--foo=1"]):
        try:
            parse_extras_protocolo(ruim, ["MCO2"])
        except ValueError:
            continue
        raise AssertionError(f"deveria rejeitar {ruim!r}")
    print("SELFTEST OK")
    return 0


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
                         "TENDERMINT e recusado (a baseline nao o tem; use checar-determinismo.py).")
    ap.add_argument("--selftest", action="store_true", help="autoteste sem JVM e sai")
    a = ap.parse_args()
    protos = [c.split(":")[0] for c in a.casos.split(",")]
    if any(p.strip().upper() == "TENDERMINT" for p in protos):
        print(f"ERRO: {recusa_tendermint()}")
        return 2
    try:
        extras = parse_extras_protocolo(a.extra_proto, protos)
    except ValueError as e:
        print(f"ERRO: {e}")
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
    print(f"semente={a.semente} timeout={a.timeout}s por execucao; {len(jobs)} execucoes em paralelo")
    if extras:
        print("flags extras (nos dois lados, so no protocolo indicado): " +
              "; ".join(f"{p}: {' '.join(f)}" for p, f in extras.items()))
    with ThreadPoolExecutor(max_workers=len(jobs)) as ex:
        fut = {j: ex.submit(executar, j[5], j[0], j[1], j[2], a.semente,
                            out / f"{j[0]}-{j[1]}v-{j[2]}s-{j[3]}-{j[4]}", a.timeout,
                            extras.get(j[0].strip().upper(), ())) for j in jobs}
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
    return pior


if __name__ == "__main__":
    sys.exit(main())

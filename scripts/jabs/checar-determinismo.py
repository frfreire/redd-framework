#!/usr/bin/env python3
r"""Confere determinismo por semente: roda o mesmo cenario 2 vezes e compara os CSV.

Colunas cujo nome contem 'hash' (hashCode de objeto varia por execucao) sao
ignoradas, assim como linhas de comentario (#).

Uso:
  checar-determinismo.py --jabs . --protocolo ARQ --validadores 7 \
      [--duracao 60] [--semente 1] [--trials 1] [--extra-flag=--chave=valor ...]
Saida: 0 identico; 1 divergente; 2 erro de execucao.

Flags extras de protocolo: `--extra-flag=--chave=valor` (repetivel) e repassada, tal e qual, ao
jabs.example.MVPComparison nas DUAS execucoes (mesmas flags nas duas, senao a comparacao nao vale).
Cada item deve comecar com `--` e ter a forma `--chave=valor` (MVPComparison ignora o resto); nao pode
repetir chave nem redefinir as flags que este script ja monta (protocols, validators, trials, duration,
seed, output). Sem `--extra-flag` o comando gerado e identico ao das versoes anteriores.

TENDERMINT exige 10 flags --tm-* sem valor padrao (MVPComparison.parseTendermintConfigIfRequested;
ADR-0010 PI-08/PI-16: valores de pesquisa a definir com o humano). Este script NAO embute valor nenhum:
vem sempre do chamador, e se faltar alguma o script falha ANTES de iniciar a JVM listando as faltantes.
Exemplo de uso (valores SOMENTE DE EXEMPLO, NAO sao valor de pesquisa):
  checar-determinismo.py --jabs . --protocolo TENDERMINT --validadores 4 --duracao 5 --semente 7 \
      --extra-flag=--tm-propose-timeout=1.0 --extra-flag=--tm-propose-delta=0.5 \
      --extra-flag=--tm-prevote-timeout=1.0 --extra-flag=--tm-prevote-delta=0.5 \
      --extra-flag=--tm-precommit-timeout=1.0 --extra-flag=--tm-precommit-delta=0.5 \
      --extra-flag=--tm-batch-size=10 --extra-flag=--tm-tx-size=512 \
      --extra-flag=--tm-signature-size=64 --extra-flag=--tm-message-overhead=10
Autoteste sem JVM: `checar-determinismo.py --selftest`.
"""
import argparse
import csv
import subprocess
import sys
import tempfile
from pathlib import Path


def classpath(jabs: Path) -> str:
    """target/classes + dependencias. `-cp target/classes` sozinho da ClassNotFoundException
    (commons-math3, fastcsv): o run-mvp.sh do JABS tem esse defeito."""
    saida = Path(tempfile.gettempdir()) / f"jabs-cp-{abs(hash(str(jabs)))}.txt"
    r = subprocess.run(["mvn", "-q", "-o", "dependency:build-classpath", f"-Dmdep.outputFile={saida}"],
                       capture_output=True, text=True, cwd=str(jabs))
    if r.returncode != 0 or not saida.exists():
        print("ERRO: nao consegui montar o classpath por Maven (offline).", r.stderr[-400:])
        sys.exit(2)
    return f"{jabs / 'target' / 'classes'}:{saida.read_text().strip()}"


# Flags obrigatorias do TENDERMINT, sem valor padrao: MVPComparison.parseTendermintConfigIfRequested
# (src/main/java/jabs/example/MVPComparison.java) lanca IllegalArgumentException se faltar alguma.
# Valores de pesquisa: ADR-0010 PI-08/PI-16 (a definir com o humano). Nenhum valor mora neste script.
TM_FLAGS_OBRIGATORIAS = (
    "--tm-propose-timeout", "--tm-propose-delta",
    "--tm-prevote-timeout", "--tm-prevote-delta",
    "--tm-precommit-timeout", "--tm-precommit-delta",
    "--tm-batch-size", "--tm-tx-size", "--tm-signature-size", "--tm-message-overhead",
)

# Flags que montar_cmd ja define: o chamador nao pode redefini-las por --extra-flag.
FLAGS_RESERVADAS = ("--protocols", "--validators", "--trials", "--duration", "--seed", "--output")


def validar_extras(extras):
    """Devolve {flag: valor}. Levanta ValueError se um item nao for `--chave=valor`, repetir chave
    ou redefinir flag reservada."""
    mapa = {}
    for item in extras:
        if not item.startswith("--") or "=" not in item or item.startswith("--="):
            raise ValueError(f"--extra-flag invalido {item!r}: use a forma --chave=valor (comeca com '--')")
        chave, valor = item.split("=", 1)
        if chave in FLAGS_RESERVADAS:
            raise ValueError(f"--extra-flag {item!r} redefine {chave}, que este script ja monta")
        if chave in mapa:
            raise ValueError(f"--extra-flag repete {chave}")
        mapa[chave] = valor
    return mapa


def tm_faltantes(proto, extras):
    """Flags --tm-* obrigatorias ausentes (ou com valor vazio). Lista vazia se o protocolo nao inclui
    TENDERMINT (--protocols aceita lista separada por virgula, como MVPComparison)."""
    if not any(p.strip().upper() == "TENDERMINT" for p in str(proto).split(",")):
        return []
    mapa = validar_extras(extras)
    return [f for f in TM_FLAGS_OBRIGATORIAS if not mapa.get(f, "").strip()]


def montar_cmd(cp, proto, n, dur, semente, trials, saida, extras=()):
    """Linha de comando do MVPComparison (funcao pura). Sem extras = comando das versoes anteriores."""
    return ["java", "-cp", cp, "jabs.example.MVPComparison",
            f"--protocols={proto}", f"--validators={n}", f"--trials={trials}",
            f"--duration={dur}", f"--seed={semente}", f"--output={saida}", *extras]


def rodar(jabs: Path, proto, n, dur, semente, trials, saida: Path, extras=()):
    cmd = montar_cmd(classpath(jabs), proto, n, dur, semente, trials, saida, extras)
    r = subprocess.run(cmd, capture_output=True, text=True, cwd=str(jabs))
    if r.returncode != 0:
        print("ERRO ao executar:", " ".join(cmd), "\n", r.stdout[-800:], r.stderr[-800:])
        sys.exit(2)
    return r


def ler_csv(path: Path):
    linhas = [l for l in path.read_text(encoding="utf-8", errors="replace").splitlines() if l.strip() and not l.startswith("#")]
    if not linhas:
        return [], []
    delim = ";" if linhas[0].count(";") > linhas[0].count(",") else ","
    rows = list(csv.reader(linhas, delimiter=delim))
    cab = rows[0]
    manter = [i for i, c in enumerate(cab) if "hash" not in c.lower()]
    return [cab[i] for i in manter], [[r[i] for i in manter if i < len(r)] for r in rows[1:]]


def selftest():
    """Autoteste sem JVM. Os valores --tm-* abaixo sao SOMENTE DE EXEMPLO, nao sao valor de pesquisa."""
    # 1) sem extras: identico ao comando das versoes anteriores (copia literal da construcao antiga)
    antigo = ["java", "-cp", "CP", "jabs.example.MVPComparison",
              "--protocols=ARQ", "--validators=7", "--trials=1",
              "--duration=60", "--seed=1", "--output=/tmp/x"]
    assert montar_cmd("CP", "ARQ", 7, 60, 1, 1, Path("/tmp/x")) == antigo
    assert montar_cmd("CP", "ARQ", 7, 60, 1, 1, Path("/tmp/x"), ()) == antigo
    # 2) nao TENDERMINT: nada exigido, mesmo com extras
    assert tm_faltantes("ARQ", []) == []
    assert tm_faltantes("ARQ", ["--outra=1"]) == []
    # 3) TENDERMINT sem flags: lista as 10; com 9: lista a que falta; com as 10: vazio
    assert tm_faltantes("TENDERMINT", []) == list(TM_FLAGS_OBRIGATORIAS)
    assert tm_faltantes("tendermint", []) == list(TM_FLAGS_OBRIGATORIAS)
    assert tm_faltantes("ARQ,TENDERMINT", []) == list(TM_FLAGS_OBRIGATORIAS)
    exemplo = [f"{f}=1" for f in TM_FLAGS_OBRIGATORIAS]
    assert tm_faltantes("TENDERMINT", exemplo[1:]) == [TM_FLAGS_OBRIGATORIAS[0]]
    assert tm_faltantes("TENDERMINT", exemplo[:-1] + [TM_FLAGS_OBRIGATORIAS[-1] + "="]) == [TM_FLAGS_OBRIGATORIAS[-1]]
    assert tm_faltantes("TENDERMINT", exemplo) == []
    # 4) extras entram ao fim do comando, na ordem dada
    assert montar_cmd("CP", "TENDERMINT", 4, 5, 7, 1, Path("/o"), exemplo)[-10:] == exemplo
    # 5) validacao
    for ruim in ("tm-batch-size=1", "--sem-valor", "--seed=2", "--tm-batch-size=1,--tm-batch-size=2"):
        try:
            validar_extras([ruim] if "," not in ruim else ruim.split(","))
        except ValueError:
            continue
        raise AssertionError(f"deveria rejeitar {ruim!r}")
    print("SELFTEST OK")
    return 0


def main():
    if "--selftest" in sys.argv[1:]:
        return selftest()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jabs", required=True)
    ap.add_argument("--protocolo", required=True)
    ap.add_argument("--validadores", type=int, default=7)
    ap.add_argument("--duracao", type=int, default=60)
    ap.add_argument("--semente", type=int, default=1)
    ap.add_argument("--trials", type=int, default=1)
    ap.add_argument("--extra-flag", dest="extra_flag", action="append", default=[], metavar="--CHAVE=VALOR",
                    help="flag repassada ao MVPComparison nas DUAS execucoes (repetivel; use a forma "
                         "--extra-flag=--chave=valor). Obrigatorio para as 10 flags --tm-* do TENDERMINT.")
    ap.add_argument("--selftest", action="store_true", help="autoteste sem JVM e sai")
    a = ap.parse_args()
    try:
        faltam = tm_faltantes(a.protocolo, a.extra_flag)
        validar_extras(a.extra_flag)
    except ValueError as e:
        print(f"ERRO: {e}")
        return 2
    if faltam:
        print(f"ERRO: protocolo TENDERMINT exige {len(faltam)} flag(s) --tm-* sem valor padrao "
              f"(MVPComparison.parseTendermintConfigIfRequested; ADR-0010 PI-08/PI-16, valores a definir com o humano). "
              f"Faltam: {', '.join(faltam)}. Passe cada uma como --extra-flag=--tm-...=valor. A JVM nao foi iniciada.")
        return 2
    jabs = Path(a.jabs).resolve()
    if not (jabs / "target" / "classes").exists():
        print(f"ERRO: {jabs}/target/classes ausente. Rode: (cd {jabs} && mvn -q clean compile)")
        return 2
    with tempfile.TemporaryDirectory() as tmp:
        saidas = []
        for k in (1, 2):
            d = Path(tmp) / f"r{k}"
            d.mkdir()
            rodar(jabs, a.protocolo, a.validadores, a.duracao, a.semente, a.trials, d, a.extra_flag)
            saidas.append(d)
        arqs1 = sorted(p.relative_to(saidas[0]) for p in saidas[0].rglob("*.csv"))
        arqs2 = sorted(p.relative_to(saidas[1]) for p in saidas[1].rglob("*.csv"))
        if not arqs1:
            print("ERRO: nenhum CSV gerado: nada foi comparado.")
            return 2
        if arqs1 != arqs2:
            print(f"DIVERGENTE: conjuntos de arquivos diferem: {arqs1} vs {arqs2}")
            return 1
        ok = True
        total = 0
        for rel in arqs1:
            c1, r1 = ler_csv(saidas[0] / rel)
            c2, r2 = ler_csv(saidas[1] / rel)
            total += len(r1)
            if c1 != c2:
                print(f"DIVERGENTE {rel}: cabecalhos diferem")
                ok = False
            elif len(r1) != len(r2):
                print(f"DIVERGENTE {rel}: {len(r1)} vs {len(r2)} linhas")
                ok = False
            else:
                for i, (x, y) in enumerate(zip(r1, r2), 1):
                    if x != y:
                        dif = [(c1[j], x[j], y[j]) for j in range(min(len(x), len(y))) if x[j] != y[j]][:3]
                        print(f"DIVERGENTE {rel} linha {i}: {dif}")
                        ok = False
                        break
        if ok and total == 0:
            print("INCONCLUSIVO: CSV sem linhas de dados (simulacao curta demais ou falha silenciosa do trial).")
            return 2
        print(("IDENTICO" if ok else "DIVERGENTE") + f": {len(arqs1)} arquivo(s), {total} linha(s) comparadas (colunas *hash* ignoradas)")
        return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

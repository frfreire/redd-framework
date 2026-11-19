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

TENDERMINT exige 11 flags --tm-* sem valor padrao (MVPComparison.parseTendermintConfigIfRequested;
ADR-0010 PI-08/PI-16: valores de pesquisa a definir com o humano). Este script NAO embute valor nenhum:
vem sempre do chamador, e se faltar alguma o script falha ANTES de iniciar a JVM listando as faltantes.
Exemplo de uso (valores SOMENTE DE EXEMPLO, NAO sao valor de pesquisa):
  checar-determinismo.py --jabs . --protocolo TENDERMINT --validadores 4 --duracao 5 --semente 7 \
      --extra-flag=--tm-propose-timeout=1.0 --extra-flag=--tm-propose-delta=0.5 \
      --extra-flag=--tm-prevote-timeout=1.0 --extra-flag=--tm-prevote-delta=0.5 \
      --extra-flag=--tm-precommit-timeout=1.0 --extra-flag=--tm-precommit-delta=0.5 \
      --extra-flag=--tm-batch-size=10 --extra-flag=--tm-tx-size=512 \
      --extra-flag=--tm-signature-size=64 --extra-flag=--tm-proposer-signature-size=80 \
      --extra-flag=--tm-message-overhead=10
HOTSTUFF (D3a-2a-2c-4) exige 11 flags --hs-* sem valor padrao, TODAS, inclusive politica de lider, modo de
assinatura e overhead de mensagem (que o HotStuffConsensusConfig ainda preenche em silencio: ADR-0015 A-08, A-09,
A-10; o MVPComparison exige a flag para que o padrao nao decida). Mesmo contrato do TENDERMINT: nenhum valor mora
neste script, a JVM nao inicia se faltar flag. Exemplo (valores SOMENTE DE EXEMPLO / fixture, NAO sao valor de pesquisa):
  checar-determinismo.py --jabs . --protocolo HOTSTUFF --validadores 4 --duracao 10 --semente 7 \
      --extra-flag=--hs-view-timeout=10.0 --extra-flag=--hs-max-future-view-horizon=1000 \
      --extra-flag=--hs-batch-size=20 --extra-flag=--hs-tx-size=512 \
      --extra-flag=--hs-individual-signature-size=64 --extra-flag=--hs-aggregated-signature-size=96 \
      --extra-flag=--hs-aggregated-share-size=96 --extra-flag=--hs-message-overhead=10 \
      --extra-flag=--hs-signature-mode=INDIVIDUAL --extra-flag=--hs-leader-policy=ROTATING \
      --extra-flag=--hs-voter-encoding=BITMAP

HOTSTUFF COM ATAQUE (D3a-2a-2d-2). O MVPComparison aceita `--attack=EQUIVOCATION` (unico ataque do HotStuff; SILENT,
WITHHOLD, DOUBLE_SIGN e desconhecidos falham alto) com `--byzantine-count=f` (inteiro >= 1, sem padrao; a regra de
fracao -> contagem e o universo do sorteio sao a definir com o humano: ADR-0015 B-33/Q34, B-37/Q30). Este script
repassa as duas por --extra-flag, nas DUAS execucoes, junto com as 11 flags --hs-* (TODAS obrigatorias: o script
recusa antes da JVM se faltar alguma). Se `--attack` ativo vier sem `--byzantine-count`, ou com ele invalido, o script
tambem recusa antes da JVM. Comando exato que funciona (fixture, NAO e calibracao; n=4 com f=1):
  python3 scripts/jabs/checar-determinismo.py --jabs . --protocolo HOTSTUFF --validadores 4 --duracao 30 --semente 1 \
      --extra-flag=--hs-view-timeout=10.0 --extra-flag=--hs-max-future-view-horizon=1000 \
      --extra-flag=--hs-batch-size=20 --extra-flag=--hs-tx-size=512 \
      --extra-flag=--hs-individual-signature-size=64 --extra-flag=--hs-aggregated-signature-size=96 \
      --extra-flag=--hs-aggregated-share-size=96 --extra-flag=--hs-message-overhead=10 \
      --extra-flag=--hs-signature-mode=INDIVIDUAL --extra-flag=--hs-leader-policy=ROTATING \
      --extra-flag=--hs-voter-encoding=BITMAP \
      --extra-flag=--attack=EQUIVOCATION --extra-flag=--byzantine-count=1
Para n=7 troque `--validadores 7` (e, p.ex., `--byzantine-count=2`). A comparacao cobre o CSV por bloco (colunas *hash*
ignoradas) E o CSV agregado mvp-results.csv (linhas '#' ignoradas).
Autoteste sem JVM: `checar-determinismo.py --selftest`.
"""
import argparse
import csv
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def mvn_exe():
    """Executavel do Maven: mvn.cmd no Windows (subprocess sem shell nao resolve `mvn`), mvn fora dele."""
    if os.name == "nt":
        return shutil.which("mvn.cmd") or "mvn.cmd"
    return "mvn"


def classpath(jabs: Path) -> str:
    """target/classes + dependencias. `-cp target/classes` sozinho da ClassNotFoundException
    (commons-math3, fastcsv): o run-mvp.sh do JABS tem esse defeito."""
    saida = Path(tempfile.gettempdir()) / f"jabs-cp-{abs(hash(str(jabs)))}.txt"
    r = subprocess.run([mvn_exe(), "-q", "-o", "dependency:build-classpath", f"-Dmdep.outputFile={saida}"],
                       capture_output=True, text=True, cwd=str(jabs))
    if r.returncode != 0 or not saida.exists():
        print("ERRO: nao consegui montar o classpath por Maven (offline).", r.stderr[-400:])
        sys.exit(2)
    return f"{jabs / 'target' / 'classes'}{os.pathsep}{saida.read_text(encoding='utf-8').strip()}"


# Flags obrigatorias do TENDERMINT, sem valor padrao: MVPComparison.parseTendermintConfigIfRequested
# (src/main/java/jabs/example/MVPComparison.java) lanca IllegalArgumentException se faltar alguma.
# Valores de pesquisa: ADR-0010 PI-08/PI-16 (a definir com o humano). Nenhum valor mora neste script.
TM_FLAGS_OBRIGATORIAS = (
    "--tm-propose-timeout", "--tm-propose-delta",
    "--tm-prevote-timeout", "--tm-prevote-delta",
    "--tm-precommit-timeout", "--tm-precommit-delta",
    "--tm-batch-size", "--tm-tx-size", "--tm-signature-size", "--tm-proposer-signature-size",
    "--tm-message-overhead",
)

# Flags obrigatorias do HOTSTUFF (MVPComparison.parseHotStuffConfigIfRequested). Mesma regra: nenhum valor aqui.
HS_FLAGS_OBRIGATORIAS = (
    "--hs-view-timeout", "--hs-max-future-view-horizon", "--hs-batch-size", "--hs-tx-size",
    "--hs-individual-signature-size", "--hs-aggregated-signature-size", "--hs-aggregated-share-size",
    "--hs-message-overhead", "--hs-signature-mode", "--hs-leader-policy", "--hs-voter-encoding",
)

MVP_COMPARISON = Path("src/main/java/jabs/example/MVPComparison.java")


def flags_tm_do_java(jabs: Path):
    """Flags --tm-* lidas por parseTendermintConfigIfRequested (requiredDouble/requiredInt(cli, "tm-...")), na
    ordem em que aparecem no codigo-fonte. Fonte da verdade: o Java; a constante acima precisa bater EXATAMENTE
    (mesmo conjunto). Levanta ValueError se o metodo nao for encontrado."""
    src = (jabs / MVP_COMPARISON).read_text(encoding="utf-8")
    ini = src.find("parseTendermintConfigIfRequested(java.util.List<String> protocols")
    if ini < 0:
        raise ValueError(f"parseTendermintConfigIfRequested nao encontrado em {jabs / MVP_COMPARISON}")
    corpo = src[ini:src.find("private static String requiredCliValue", ini)]
    return ["--" + m for m in re.findall(r'required(?:Double|Int)\(cli,\s*"(tm-[a-z-]+)"\)', corpo)]


def flags_hs_do_java(jabs: Path):
    """Flags --hs-* lidas por parseHotStuffConfigIfRequested (required*(cli, "hs-...", p)), na ordem do codigo."""
    src = (jabs / MVP_COMPARISON).read_text(encoding="utf-8")
    ini = src.find("parseHotStuffConfigIfRequested(java.util.List<String> protocols")
    if ini < 0:
        raise ValueError(f"parseHotStuffConfigIfRequested nao encontrado em {jabs / MVP_COMPARISON}")
    fim = src.find("\n    }\n", ini)  # fim do metodo (corpo termina em `.build();` + `}` na coluna 4)
    corpo = src[ini:fim if fim > 0 else len(src)]
    return ["--" + m for m in re.findall(r'required(?:Double|Int|Long|Enum)\(cli,\s*"(hs-[a-z-]+)"', corpo)]


def conferir_hs_com_java(jabs: Path):
    """Levanta ValueError se HS_FLAGS_OBRIGATORIAS divergir (conjunto) das flags requeridas no Java."""
    java = flags_hs_do_java(jabs)
    if sorted(java) != sorted(HS_FLAGS_OBRIGATORIAS) or len(set(java)) != len(java):
        raise ValueError("HS_FLAGS_OBRIGATORIAS diverge do Java: so no Java="
                         f"{sorted(set(java) - set(HS_FLAGS_OBRIGATORIAS))}, so no script="
                         f"{sorted(set(HS_FLAGS_OBRIGATORIAS) - set(java))}")


def conferir_com_java(jabs: Path):
    """Levanta ValueError se TM_FLAGS_OBRIGATORIAS divergir (conjunto) das flags requeridas no Java."""
    java = flags_tm_do_java(jabs)
    if sorted(java) != sorted(TM_FLAGS_OBRIGATORIAS) or len(set(java)) != len(java):
        raise ValueError("TM_FLAGS_OBRIGATORIAS diverge do Java: so no Java="
                         f"{sorted(set(java) - set(TM_FLAGS_OBRIGATORIAS))}, so no script="
                         f"{sorted(set(TM_FLAGS_OBRIGATORIAS) - set(java))}")


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


def hs_faltantes(proto, extras):
    """Flags --hs-* obrigatorias ausentes (ou vazias). Lista vazia se o protocolo nao inclui HOTSTUFF."""
    if not any(p.strip().upper() == "HOTSTUFF" for p in str(proto).split(",")):
        return []
    mapa = validar_extras(extras)
    return [f for f in HS_FLAGS_OBRIGATORIAS if not mapa.get(f, "").strip()]


def hs_ataque_invalido(proto, extras, validadores):
    """Mensagem de erro (ou None) para --attack/--byzantine-count passados por --extra-flag com HOTSTUFF. Espelha
    MVPComparison.resolveHotStuffAttack/resolveByzantineCount so para falhar ANTES da JVM; a verdade e o Java."""
    if not any(p.strip().upper() == "HOTSTUFF" for p in str(proto).split(",")):
        return None
    mapa = validar_extras(extras)
    ataque = mapa.get("--attack")
    contagem = mapa.get("--byzantine-count")
    if ataque is None or ataque.strip().lower() in ("none", "nenhum"):
        if contagem is not None and contagem.strip() != "0":
            return f"--byzantine-count={contagem} sem ataque ativo seria ignorado em silencio"
        return None
    if ataque.strip().upper() != "EQUIVOCATION":
        return f"HOTSTUFF so implementa EQUIVOCATION (recebido {ataque!r})"
    if contagem is None or not contagem.strip().lstrip("+").isdigit() or int(contagem) < 1:
        return "--attack=EQUIVOCATION exige --byzantine-count=f (inteiro >= 1, sem valor padrao)"
    if int(contagem) > validadores:
        return f"--byzantine-count={contagem} maior que n={validadores}"
    return None


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


SAIDA_X = Path(tempfile.gettempdir()) / "x"  # so para o autoteste (funcao pura, nada e criado)


def selftest():
    """Autoteste sem JVM. Os valores --tm-* abaixo sao SOMENTE DE EXEMPLO, nao sao valor de pesquisa."""
    # 1) sem extras: identico ao comando das versoes anteriores (copia literal da construcao antiga)
    antigo = ["java", "-cp", "CP", "jabs.example.MVPComparison",
              "--protocols=ARQ", "--validators=7", "--trials=1",
              "--duration=60", "--seed=1", f"--output={SAIDA_X}"]
    assert montar_cmd("CP", "ARQ", 7, 60, 1, 1, SAIDA_X) == antigo
    assert montar_cmd("CP", "ARQ", 7, 60, 1, 1, SAIDA_X, ()) == antigo
    # 2) nao TENDERMINT: nada exigido, mesmo com extras
    assert tm_faltantes("ARQ", []) == []
    assert tm_faltantes("ARQ", ["--outra=1"]) == []
    # 3) TENDERMINT sem flags: lista as 11; com 10: lista a que falta; com as 11: vazio
    assert tm_faltantes("TENDERMINT", []) == list(TM_FLAGS_OBRIGATORIAS)
    assert tm_faltantes("tendermint", []) == list(TM_FLAGS_OBRIGATORIAS)
    assert tm_faltantes("ARQ,TENDERMINT", []) == list(TM_FLAGS_OBRIGATORIAS)
    exemplo = [f"{f}=1" for f in TM_FLAGS_OBRIGATORIAS]
    assert tm_faltantes("TENDERMINT", exemplo[1:]) == [TM_FLAGS_OBRIGATORIAS[0]]
    assert tm_faltantes("TENDERMINT", exemplo[:-1] + [TM_FLAGS_OBRIGATORIAS[-1] + "="]) == [TM_FLAGS_OBRIGATORIAS[-1]]
    assert tm_faltantes("TENDERMINT", exemplo) == []
    # 4) extras entram ao fim do comando, na ordem dada
    assert montar_cmd("CP", "TENDERMINT", 4, 5, 7, 1, Path("/o"), exemplo)[-11:] == exemplo
    # 4b) contagem e fonte da verdade: 11 flags, batendo EXATAMENTE com o Java
    assert len(TM_FLAGS_OBRIGATORIAS) == 11 and "--tm-proposer-signature-size" in TM_FLAGS_OBRIGATORIAS
    raiz = Path(__file__).resolve().parents[2]
    conferir_com_java(raiz)
    assert sorted(flags_tm_do_java(raiz)) == sorted(TM_FLAGS_OBRIGATORIAS)
    # o conferidor falha de fato quando diverge (Java simulado com uma flag a menos / a mais)
    with tempfile.TemporaryDirectory() as t:
        alvo = Path(t) / MVP_COMPARISON
        alvo.parent.mkdir(parents=True)
        original = (raiz / MVP_COMPARISON).read_text(encoding="utf-8")
        for rotulo, mutado in (("a menos", original.replace('requiredInt(cli, "tm-proposer-signature-size")', '0')),
                               ("a mais", original.replace('requiredInt(cli, "tm-tx-size")',
                                                           'requiredInt(cli, "tm-tx-size") + requiredInt(cli, "tm-extra")'))):
            assert mutado != original, rotulo
            alvo.write_text(mutado, encoding="utf-8")
            try:
                conferir_com_java(Path(t))
            except ValueError:
                continue
            raise AssertionError(f"conferir_com_java deveria falhar com flag {rotulo}")
    # 4c) HOTSTUFF: mesmas garantias (11 flags, sem valor no script, bate com o Java)
    assert len(HS_FLAGS_OBRIGATORIAS) == 11
    assert hs_faltantes("ARQ", []) == [] and hs_faltantes("TENDERMINT", []) == []
    assert hs_faltantes("HOTSTUFF", []) == list(HS_FLAGS_OBRIGATORIAS)
    assert hs_faltantes("hotstuff", []) == list(HS_FLAGS_OBRIGATORIAS)
    ex_hs = [f"{f}=1" for f in HS_FLAGS_OBRIGATORIAS]
    assert hs_faltantes("HOTSTUFF", ex_hs) == []
    assert hs_faltantes("HOTSTUFF", ex_hs[1:]) == [HS_FLAGS_OBRIGATORIAS[0]]
    assert hs_faltantes("HOTSTUFF", ex_hs[:-1] + [HS_FLAGS_OBRIGATORIAS[-1] + "="]) == [HS_FLAGS_OBRIGATORIAS[-1]]
    for flag_pol in ("--hs-leader-policy", "--hs-signature-mode", "--hs-message-overhead"):  # A-09, A-08, A-10
        assert flag_pol in hs_faltantes("HOTSTUFF", [x for x in ex_hs if not x.startswith(flag_pol)])
    conferir_hs_com_java(raiz)
    assert sorted(flags_hs_do_java(raiz)) == sorted(HS_FLAGS_OBRIGATORIAS)
    with tempfile.TemporaryDirectory() as t:
        alvo = Path(t) / MVP_COMPARISON
        alvo.parent.mkdir(parents=True)
        original = (raiz / MVP_COMPARISON).read_text(encoding="utf-8")
        mutado = original.replace('requiredEnum(cli, "hs-leader-policy", p, HotStuffConsensusConfig.LeaderPolicy.class)',
                                  'HotStuffConsensusConfig.LeaderPolicy.ROTATING')
        assert mutado != original
        alvo.write_text(mutado, encoding="utf-8")
        try:
            conferir_hs_com_java(Path(t))
        except ValueError:
            pass
        else:
            raise AssertionError("conferir_hs_com_java deveria falhar sem a checagem de hs-leader-policy")
    # 4d) ataque HOTSTUFF: so EQUIVOCATION, contagem inteira >= 1 e <= n; fora do HOTSTUFF nada e exigido
    assert hs_ataque_invalido("ARQ", ["--attack=SILENT"], 4) is None
    assert hs_ataque_invalido("HOTSTUFF", [], 4) is None
    assert hs_ataque_invalido("HOTSTUFF", ["--attack=none"], 4) is None
    assert hs_ataque_invalido("HOTSTUFF", ["--attack=EQUIVOCATION", "--byzantine-count=1"], 4) is None
    assert hs_ataque_invalido("hotstuff", ["--attack=equivocation", "--byzantine-count=2"], 7) is None
    for ex in (["--attack=EQUIVOCATION"], ["--attack=EQUIVOCATION", "--byzantine-count=0"],
               ["--attack=EQUIVOCATION", "--byzantine-count=1.5"], ["--attack=EQUIVOCATION", "--byzantine-count=5"],
               ["--attack=SILENT", "--byzantine-count=1"], ["--attack=WITHHOLD", "--byzantine-count=1"],
               ["--attack=DOUBLE_SIGN", "--byzantine-count=1"], ["--attack=x", "--byzantine-count=1"],
               ["--byzantine-count=1"], ["--attack=none", "--byzantine-count=1"]):
        assert hs_ataque_invalido("HOTSTUFF", ex, 4), ex
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
                         "--extra-flag=--chave=valor). Obrigatorio para as 11 flags --tm-* do TENDERMINT e as 11 flags --hs-* do HOTSTUFF.")
    ap.add_argument("--selftest", action="store_true", help="autoteste sem JVM e sai")
    a = ap.parse_args()
    try:
        faltam = tm_faltantes(a.protocolo, a.extra_flag)
        faltam_hs = hs_faltantes(a.protocolo, a.extra_flag)
        validar_extras(a.extra_flag)
    except ValueError as e:
        print(f"ERRO: {e}")
        return 2
    if faltam:
        print(f"ERRO: protocolo TENDERMINT exige {len(faltam)} flag(s) --tm-* sem valor padrao "
              f"(MVPComparison.parseTendermintConfigIfRequested; ADR-0010 PI-08/PI-16, valores a definir com o humano). "
              f"Faltam: {', '.join(faltam)}. Passe cada uma como --extra-flag=--tm-...=valor. A JVM nao foi iniciada.")
        return 2
    if faltam_hs:
        print(f"ERRO: protocolo HOTSTUFF exige {len(faltam_hs)} flag(s) --hs-* sem valor padrao "
              f"(MVPComparison.parseHotStuffConfigIfRequested; ADR-0015, valores a definir com o humano). "
              f"Faltam: {', '.join(faltam_hs)}. Passe cada uma como --extra-flag=--hs-...=valor. A JVM nao foi iniciada.")
        return 2
    try:
        ruim = hs_ataque_invalido(a.protocolo, a.extra_flag, a.validadores)
    except ValueError as e:
        print(f"ERRO: {e}")
        return 2
    if ruim:
        print(f"ERRO: ataque HOTSTUFF invalido: {ruim}. A JVM nao foi iniciada.")
        return 2
    jabs = Path(a.jabs).resolve()
    if hs_faltantes(a.protocolo, []):  # protocolo inclui HOTSTUFF: a lista do script tem de bater com o Java
        try:
            conferir_hs_com_java(jabs)
        except (ValueError, OSError) as e:
            print(f"ERRO: {e}. A JVM nao foi iniciada.")
            return 2
    if tm_faltantes(a.protocolo, []):  # protocolo inclui TENDERMINT: a lista do script tem de bater com o Java
        try:
            conferir_com_java(jabs)
        except (ValueError, OSError) as e:
            print(f"ERRO: {e}. A JVM nao foi iniciada.")
            return 2
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

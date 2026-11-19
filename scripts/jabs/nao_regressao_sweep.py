#!/usr/bin/env python3
"""Nao regressao SOB ATAQUE via ByzantineSweep (D3a-2a-2d-2). Comparador MINIMO, complementar ao nao_regressao.py.

Por que existe: o nao_regressao.py so chama jabs.example.MVPComparison, que NAO le --attack para os protocolos
existentes. O laco de sorteio de ids bizantinos de ByzantineConfig (java.util.Random, INTACTO desde 40ac8ab) e usado
pelo construtor por PERCENTUAL, que so o ByzantineSweep alcanca (VOTING = ArqReddVotingScenario/HybridNetworkScenario e
POSA = ParliaBSCNetworkScenario, ambos via `new ByzantineConfig(n, pct, attack, seed)`).

Faz 4 execucoes sequenciais do ByzantineSweep (base-a, base-b, atual-a, atual-b; mesma semente, mesmos argumentos) e
compara, com a funcao comparar() do nao_regressao.py (colunas *hash* e linhas '#' ignoradas), as pastas inteiras:
o CSV agregado (`s.csv`, uma linha por f) E os CSV por trial (`tmp/<TIPO>/<n>n-<f>-<trial>.csv`).

D2 (ADR-0015): para VOTING, POS, POSA e POW (tipos que separam a autoentrega) a comparacao base x atual aceita as colunas
aditivas no FIM (avgSelfDelivery_MB, stdSelfDelivery_MB no s.csv; Cb_autoentrega_B nos CSV por trial) e a queda de
avgTraffic/stdTraffic SO se avgTraffic(base) ~ avgTraffic(atual) + avgSelfDelivery_MB (tol. 2,5e-6; -1 = nao medido: sem
conservacao, avgTraffic nao pode subir). DAGSPER e as comparacoes a x b: estritas. Ver reconciliar_autoentrega em nao_regressao.py.

LIMITES (declarados, nao escondidos):
  - sequencial (nao paralelo): o sweep escreve em subpastas distintas, mas cada caso leva alguns segundos;
  - cobre VOTING e POSA (D3a-2a-2d-2) e, desde BYZ-UNICA (ADR-0015), tambem POS, POW e DAGSPER, com o ataque pedido, 1 valor
    de n, f pequeno e 2 trials. Desde BYZ-UNICA o ByzantineSweep falha ALTO com f > floor((n-1)/3) (n = universo de
    validadores/votantes do tipo): escolha --max-byz dentro da faixa, ou passe --allow-beyond-bft-threshold=true por
    --extra-flag (repassado nos QUATRO lados; o HEAD o ignora com aviso e roda f fora da faixa);
  - POSA depende de System.identityHashCode (ADR-0012/0013): use --jvm-flag=-XX:+UnlockExperimentalVMOptions
    --jvm-flag=-XX:hashCode=2 nos QUATRO lados; sem ela a divergencia base x atual NAO e atribuivel ao codigo.
Uso:
  nao_regressao_sweep.py --base /tmp/opencode/base-40ac8ab --atual . --saida /tmp/opencode/nr-2d/sweep \
      --tipo VOTING --validators 10 --max-byz 3 --trials 2 --duration 60 --attack EQUIVOCATION --semente 1
Saida: 0 = identico (com dados); 1 = divergencia base x atual; 2 = erro/sem dados.
"""
import argparse
import importlib.util
import subprocess
import sys
import tempfile
import time
from pathlib import Path

AQUI = Path(__file__).resolve().parent


def carregar_nr():
    spec = importlib.util.spec_from_file_location("nao_regressao", AQUI / "nao_regressao.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def montar_cmd(cp, a, destino: Path, jvm=()):
    """Linha de comando do ByzantineSweep (funcao pura)."""
    return ["java", *jvm, "-cp", cp, "jabs.example.ByzantineSweep", f"--type={a.tipo}",
            f"--validators={a.validators}", f"--max-byz={a.max_byz}", f"--trials={a.trials}",
            f"--duration={a.duration}", f"--attack={a.attack}", f"--seed={a.semente}",
            f"--out={destino / 's.csv'}", *getattr(a, "extra_flag", [])]


def rodar(nr, raiz: Path, a, destino: Path, jvm):
    destino.mkdir(parents=True, exist_ok=True)
    cmd = montar_cmd(nr.classpath(raiz), a, destino, jvm)
    t0 = time.time()
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=a.timeout, cwd=str(raiz))
    except subprocess.TimeoutExpired:
        return "TIMEOUT", time.time() - t0, cmd
    (destino.parent / (destino.name + ".log")).write_text(r.stdout + r.stderr, encoding="utf-8")
    return r.returncode, time.time() - t0, cmd


def selftest(nr):
    class A:
        tipo, validators, max_byz, trials, duration, attack, semente = "VOTING", "10", "3", "2", "60", "EQUIVOCATION", 1
    saida_x = Path(tempfile.gettempdir()) / "x"  # so para o autoteste (funcao pura, nada e criado)
    cmd = montar_cmd("CP", A, saida_x, ["-XX:hashCode=2"])
    assert cmd[:4] == ["java", "-XX:hashCode=2", "-cp", "CP"], cmd
    assert "--attack=EQUIVOCATION" in cmd and f"--out={saida_x / 's.csv'}" in cmd, cmd
    assert set(nr.TIPOS_SWEEP_AUTOENTREGA) == {"VOTING", "POS", "POSA", "POW"} and "DAGSPER" not in nr.TIPOS_SWEEP_AUTOENTREGA
    print("SELFTEST OK")
    return 0


def main():
    nr = carregar_nr()
    if "--selftest" in sys.argv[1:]:
        return selftest(nr)
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True)
    ap.add_argument("--atual", required=True)
    ap.add_argument("--saida", required=True)
    ap.add_argument("--tipo", required=True, choices=["VOTING", "POSA", "POS", "POW", "DAGSPER"])
    ap.add_argument("--validators", required=True)
    ap.add_argument("--max-byz", dest="max_byz", required=True)
    ap.add_argument("--trials", default="2")
    ap.add_argument("--duration", default="60")
    ap.add_argument("--attack", required=True)
    ap.add_argument("--semente", type=int, default=1)
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--jvm-flag", dest="jvm_flag", action="append", default=[])
    ap.add_argument("--extra-flag", dest="extra_flag", action="append", default=[], metavar="--CHAVE=VALOR",
                    help="repassada ao ByzantineSweep nas QUATRO execucoes (ex.: --n-semantics=nodes)")
    a = ap.parse_args()
    base, atual, out = Path(a.base).resolve(), Path(a.atual).resolve(), Path(a.saida)
    for r in (base, atual):
        if not (r / "target" / "classes").exists():
            sys.exit(f"ERRO: {r}/target/classes ausente")
    st = {}
    # nome no padrao do arquivar-nao-regressao.py: <caso>-{base,atual}-{a,b}
    nome_caso = f"{a.tipo}-{a.validators}n-fmax{a.max_byz}-{a.trials}t-{a.duration}s"
    print(f"ByzantineSweep type={a.tipo} n={a.validators} max-byz={a.max_byz} trials={a.trials} duration={a.duration} "
          f"attack={a.attack} semente={a.semente} jvm={' '.join(a.jvm_flag) or '(nenhuma)'}")
    for lado, raiz in (("base", base), ("atual", atual)):
        for rep in ("a", "b"):
            rc, seg, cmd = rodar(nr, raiz, a, out / f"{nome_caso}-{lado}-{rep}", a.jvm_flag)
            st[(lado, rep)] = (rc, seg)
            sem_cp = [c for i, c in enumerate(cmd) if not (i > 0 and cmd[i - 1] == "-cp")]
            print(f"   {lado}-{rep}: rc={rc} {seg:.0f}s  cmd={' '.join(sem_cp)}  (classpath omitido: {raiz}/target/classes + target/cp.txt)")
    if any(v[0] != 0 for v in st.values()):
        print("   INCONCLUSIVO: execucao com erro/timeout")
        return 2
    pior = 0
    for rot, x, y in (("baseline a x b (pre-existente?)", "base-a", "base-b"),
                      ("atual    a x b                ", "atual-a", "atual-b"),
                      ("baseline a x atual a (REGRESSAO?)", "base-a", "atual-a")):
        # D2 (ADR-0015): so base x atual e so para os tipos que separam a autoentrega (VOTING, POS, POSA, POW) toleram a coluna
        # aditiva no fim e avgTraffic sob conservacao (nr.reconciliar_autoentrega); os demais tipos e a/b: comparacao estrita.
        ae = rot.startswith("baseline a x atual") and a.tipo in nr.TIPOS_SWEEP_AUTOENTREGA
        v, det, lin = nr.comparar(out / f"{nome_caso}-{x}", out / f"{nome_caso}-{y}", autoentrega=ae)
        print(f"   {rot}: {v} ({lin} linhas de dados, todos os CSV da pasta) {det}")
        if v == "SEM_DADOS":
            pior = max(pior, 2)
        elif v == "DIVERGENTE" and rot.startswith("baseline a x atual"):
            pior = max(pior, 1)
    return pior


if __name__ == "__main__":
    sys.exit(main())

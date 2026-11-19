#!/usr/bin/env python3
"""Roda os grupos de nao regressao do HS-CB-fase2 (B4) chamando nao_regressao.py com a fixture CANONICA.

Cada grupo = UM comando de nao_regressao.py (impresso com `$ ` antes de rodar) com no maximo 4 JVMs simultaneas
(nao_regressao.py impoe MAX_PARALELO=4; B1: acima disso a escrita do CSV perde determinismo). A saida integral de cada
grupo vai para <raiz>/saida-s<S>-<grupo>.txt (o arquivar-nao-regressao.py copia esses arquivos).

Grupos (casos e parametros do README de 2d-hotstuff-equivocation; lote/tx dos DOIS protocolos = fixture canonica):
  geral         ARQ:10:60, AMBIFY:10:60, MCO2:50:600, OFFSET:40:20000, EARTH:40:20000, DAGSPER:10:600,
                TENDERMINT:4:20, TENDERMINT:7:20   (SEM flag de hash; --baseline-tem TENDERMINT + 11 flags --tm-*)
  tree          TREE:50:6000 com -XX:+UnlockExperimentalVMOptions -XX:hashCode=2
  hotstuff      HOTSTUFF:4:30, HOTSTUFF:7:30 SEM ataque (--baseline-tem HOTSTUFF + 11 flags --hs-*)
  offset-hash2  OFFSET:40:20000 com -XX:hashCode=2
Os valores --tm-*/--hs-* sao FIXTURE DE TESTE (nunca calibracao); lote e tx-size vem de fixture_canonica.py.

Uso: nr_hscb_fase2.py --base /tmp/opencode/base-8c68084 --atual . --raiz /tmp/opencode/nr-hscb/final --semente 1 \
         [--grupos geral,tree,hotstuff,offset-hash2]
Saida: maior codigo de saida dos grupos (0 ok; 1 divergencia; 2 inconclusivo). Autoteste: --selftest.
"""
import argparse
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fixture_canonica  # noqa: E402

HASH2 = ["--jvm-flag=-XX:+UnlockExperimentalVMOptions", "--jvm-flag=-XX:hashCode=2"]


def flags_tm(fx):
    return [("--tm-propose-timeout", "1.0"), ("--tm-propose-delta", "0.5"), ("--tm-prevote-timeout", "1.0"),
            ("--tm-prevote-delta", "0.5"), ("--tm-precommit-timeout", "1.0"), ("--tm-precommit-delta", "0.5"),
            ("--tm-batch-size", str(fx["batch-size"])), ("--tm-tx-size", str(fx["tx-size"])),
            ("--tm-signature-size", "64"), ("--tm-proposer-signature-size", "80"), ("--tm-message-overhead", "10")]


def flags_hs(fx):
    return [("--hs-view-timeout", "10.0"), ("--hs-max-future-view-horizon", "1000"),
            ("--hs-batch-size", str(fx["batch-size"])), ("--hs-tx-size", str(fx["tx-size"])),
            ("--hs-individual-signature-size", "64"), ("--hs-aggregated-signature-size", "96"),
            ("--hs-aggregated-share-size", "96"), ("--hs-message-overhead", "10"),
            ("--hs-signature-mode", "INDIVIDUAL"), ("--hs-leader-policy", "ROTATING"), ("--hs-voter-encoding", "BITMAP")]


def extras(proto, pares):
    out = []
    for k, v in pares:
        out += ["--extra-flag-protocolo", f"{proto}:{k}={v}"]
    return out


def comando(grupo, base, atual, semente, saida, fx):
    comum = [sys.executable, "scripts/jabs/nao_regressao.py", "--base", base, "--atual", atual, "--semente", str(semente),
             "--timeout", "900", "--paralelo", "4", "--saida", str(saida / f"s{semente}" / grupo)]
    if grupo == "geral":
        return comum + ["--casos", "ARQ:10:60,AMBIFY:10:60,MCO2:50:600,OFFSET:40:20000,EARTH:40:20000,DAGSPER:10:600,"
                        "TENDERMINT:4:20,TENDERMINT:7:20", "--baseline-tem", "TENDERMINT"] + extras("TENDERMINT", flags_tm(fx))
    if grupo == "tree":
        return comum + ["--casos", "TREE:50:6000"] + HASH2
    if grupo == "hotstuff":
        return comum + ["--casos", "HOTSTUFF:4:30,HOTSTUFF:7:30", "--baseline-tem", "HOTSTUFF"] + extras("HOTSTUFF", flags_hs(fx))
    if grupo == "offset-hash2":
        return comum + ["--casos", "OFFSET:40:20000"] + HASH2
    raise ValueError(f"grupo desconhecido {grupo!r}")


GRUPOS = ("geral", "tree", "hotstuff", "offset-hash2")


def selftest():
    fx = fixture_canonica.carregar()
    for g in GRUPOS:
        c = comando(g, "B", "A", 1, Path("/tmp/x"), fx)
        assert "--paralelo" in c and c[c.index("--paralelo") + 1] == "4", g
        assert ("--tm-batch-size=%d" % fx["batch-size"] in " ".join(c)) == (g == "geral"), g
        assert ("--hs-batch-size=%d" % fx["batch-size"] in " ".join(c)) == (g == "hotstuff"), g
    assert "--jvm-flag=-XX:hashCode=2" in comando("tree", "B", "A", 1, Path("/tmp/x"), fx)
    assert "--jvm-flag=-XX:hashCode=2" not in comando("geral", "B", "A", 1, Path("/tmp/x"), fx)  # AMBIFY/MCO2 SEM flag de hash
    try:
        comando("zzz", "B", "A", 1, Path("/tmp/x"), fx)
    except ValueError:
        print("SELFTEST OK")
        return 0
    raise AssertionError("grupo desconhecido deveria falhar")


def main():
    if "--selftest" in sys.argv[1:]:
        return selftest()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True)
    ap.add_argument("--atual", required=True)
    ap.add_argument("--raiz", required=True)
    ap.add_argument("--semente", type=int, required=True)
    ap.add_argument("--grupos", default=",".join(GRUPOS))
    a = ap.parse_args()
    fx = fixture_canonica.carregar()
    raiz = Path(a.raiz)
    raiz.mkdir(parents=True, exist_ok=True)
    pior = 0
    for g in a.grupos.split(","):
        cmd = comando(g, a.base, a.atual, a.semente, raiz, fx)
        print("$ " + " ".join(cmd), flush=True)
        r = subprocess.run(cmd, capture_output=True, text=True)
        (raiz / f"saida-s{a.semente}-{g}.txt").write_text(r.stdout + r.stderr, encoding="utf-8")
        print(r.stdout + r.stderr, flush=True)
        print(f"rc={r.returncode} grupo={g} semente={a.semente}", flush=True)
        pior = max(pior, r.returncode)
        if r.returncode == 1:
            print("PARE: DIVERGENTE; relate antes de seguir (nao reexecutar para 'consertar').", flush=True)
            break
    return pior


if __name__ == "__main__":
    sys.exit(main())

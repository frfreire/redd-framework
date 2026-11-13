#!/usr/bin/env python3
"""Executa UMA celula do piloto de timeout do HotStuff (uma semente, duas JVMs em serie).

JVM 1: jabs.example.MVPComparison (caminho OFICIAL, gera o CSV por bloco bruto e o agregado).
JVM 2: harness/ObservadorHotStuff (mesmo cenario, mesma semente, mesmo config; fora de src/) que acrescenta o que o
       CSV oficial nao traz: finalizacao local de CADA no (duracao de visao por replica) e contadores de timeout.
A analise confere que o CSV por bloco das duas JVMs e identico (coluna BlockHashCode excluida).

Entrada: cfg_*.json (campos: id, n, semente, duracao_s, byzantinos, variante{...}). Saida em <saida>/<id>/:
comando-mvp.txt, comando-observador.txt, stdout/stderr de cada JVM, resultado.json,
hotstuff-<n>v-t0.csv.gz (BRUTO do MVPComparison, gzip sem perda), mvp-results*.csv, obs/observador-*.
Contrato de semente: MVPComparison usa runSeed = --seed + n*1000 + trial (trial 0). gerar-grid.py grava
semente = semente_base + n*1000 + replicacao; logo --seed = semente - n*1000 e o observador recebe --run-seed=semente.
Nenhum valor de pesquisa mora aqui: tudo vem do cfg (gerado por gerar-celulas.py a partir de fatores.json).
"""
import gzip
import hashlib
import json
import resource
import shutil
import subprocess
import sys
import time
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
DEST = Path("/tmp/opencode/piloto-hs")
# SEM -XX:hashCode=2: so e exigido por --load-record (que o piloto nao usa); com a flag o HashSet<HotStuffTx> do lote
# degenera (todo identityHashCode vale 1) e o L=500 fica ~10x mais lento (medido; ver RELATORIO.md). A equivalencia
# de saida (CSV por bloco sem a coluna BlockHashCode) com e sem a flag foi conferida (RELATORIO.md, secao de validacao).
JVM_FLAGS_PADRAO = ["-Xmx2g"]


def classpath(extra=None):
    cp = f"{DEST}/target/classes:" + (DEST / "cp.txt").read_text().strip()
    return (f"{DEST}/harness-classes:" + cp) if extra else cp


def flags_hs(cfg):
    v = cfg["variante"]
    f = [
        f"--hs-view-timeout={v['view_timeout_s']}",
        f"--hs-max-future-view-horizon={v['max_future_view_horizon']}",
        f"--hs-batch-size={v['L']}", f"--hs-tx-size={v['s_tx']}",
        f"--hs-individual-signature-size={v['sig_individual_B']}",
        f"--hs-aggregated-signature-size={v['sig_agregada_B']}",
        f"--hs-aggregated-share-size={v['share_agregada_B']}",
        f"--hs-message-overhead={v['overhead_msg_B']}",
        f"--hs-signature-mode={v['signature_mode']}",
        f"--hs-leader-policy={v['leader_policy']}",
        f"--hs-voter-encoding={v['voter_encoding']}",
    ]
    if v.get("attack"):
        f += [f"--attack={v['attack']}", f"--byzantine-count={cfg['byzantinos']}"]
    return f


def cmd_mvp(cfg, saida):
    n = cfg["n"]
    return ["java", *cfg.get("jvm_flags", JVM_FLAGS_PADRAO), "-cp", classpath(), "jabs.example.MVPComparison",
            "--protocols=HOTSTUFF", f"--validators={n}", "--trials=1", f"--duration={cfg['duracao_s']}",
            f"--seed={cfg['semente'] - n * 1000}", f"--output={saida}", f"--csv-out={saida}/mvp-results.csv",
            *flags_hs(cfg)]


def cmd_obs(cfg, saida):
    n = cfg["n"]
    return ["java", *cfg.get("jvm_flags", JVM_FLAGS_PADRAO), "-cp", classpath(extra=True), "ObservadorHotStuff",
            f"--run-seed={cfg['semente']}", f"--validators={n}", f"--duration={cfg['duracao_s']}",
            f"--out-dir={saida}/obs", "--csv=hotstuff-obs.csv", *flags_hs(cfg)]


def md5_sem_hashcode(path):
    """md5 do CSV sem linhas '#' e sem a coluna BlockHashCode (varia por execucao sem -XX:hashCode=2)."""
    h = hashlib.md5()
    idx = None
    linhas = 0
    with open(path, encoding="utf-8") as f:
        for l in f:
            if l.startswith("#"):
                continue
            c = l.rstrip("\n").split(",")
            if idx is None:
                idx = c.index("BlockHashCode")
            else:
                linhas += 1
            h.update((",".join(x for i, x in enumerate(c) if i != idx) + "\n").encode())
    return h.hexdigest(), linhas


def gz(path):
    with open(path, "rb") as fi, gzip.open(str(path) + ".gz", "wb", compresslevel=9) as fo:
        shutil.copyfileobj(fi, fo)
    Path(path).unlink()


def rodar(cmd, saida, nome):
    (saida / f"comando-{nome}.txt").write_text(" ".join(cmd) + "\n", encoding="utf-8")
    t0 = time.time()
    with open(saida / f"stdout-{nome}.log", "w") as so, open(saida / f"stderr-{nome}.log", "w") as se:
        rc = subprocess.run(cmd, stdout=so, stderr=se, cwd=DEST).returncode
    # o progresso do JABS usa \r e infla o stderr: mantem so o final
    p = saida / f"stderr-{nome}.log"
    txt = p.read_text(errors="replace").replace("\r", "\n").splitlines()
    p.write_text("\n".join([l for l in txt if "Simulation in progress" not in l][-40:]) + "\n")
    return rc, time.time() - t0


def main():
    cfg = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    base_saida = (Path(sys.argv[2]) if len(sys.argv) > 2 else BASE / "dados-brutos").resolve()  # ABSOLUTO: a JVM roda com cwd=DEST
    saida = base_saida / cfg["id"]
    saida.mkdir(parents=True, exist_ok=True)
    (saida / "obs").mkdir(exist_ok=True)
    (saida / "cfg.json").write_text(json.dumps(cfg, indent=1, ensure_ascii=False), encoding="utf-8")
    rc1, dt1 = rodar(cmd_mvp(cfg, str(saida)), saida, "mvp")
    rc2, dt2 = rodar(cmd_obs(cfg, str(saida)), saida, "observador")
    rss_mb = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss / 1024  # maior RSS entre os filhos
    res = {"id": cfg["id"], "rc_mvp": rc1, "rc_observador": rc2, "tempo_parede_mvp_s": round(dt1, 2),
           "tempo_parede_observador_s": round(dt2, 2), "max_rss_mb": round(rss_mb, 1)}
    for nome, pat, dest in (("mvp", "hotstuff-*v-t*.csv", saida), ("obs", "hotstuff-obs.csv", saida / "obs")):
        arq = sorted(dest.glob(pat))
        if arq:
            m, ln = md5_sem_hashcode(arq[0])
            res[f"csv_{nome}"] = arq[0].name + ".gz"
            res[f"md5_sem_hashcode_{nome}"] = m
            res[f"blocos_{nome}"] = ln
            gz(arq[0])
        else:
            res[f"csv_{nome}"] = None
            res[f"blocos_{nome}"] = 0
    (saida / "resultado.json").write_text(json.dumps(res, indent=1), encoding="utf-8")
    print(f"{cfg['id']} rc=({rc1},{rc2}) parede={dt1:.0f}+{dt2:.0f}s blocos={res['blocos_mvp']}/{res['blocos_obs']} "
          f"equiv={res.get('md5_sem_hashcode_mvp') == res.get('md5_sem_hashcode_obs')}")
    sys.exit(0 if rc1 == 0 and rc2 == 0 else 1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Executa UMA celula do V-ABC do Tendermint (RASCUNHO; so roda depois do pino e do commit do pre-registro).

V-A e V-C (cfg["bloco"] in va, vc): DUAS JVMs em serie, uma semente, mesma convencao do HotStuff.
  JVM 1: jabs.example.MVPComparison --protocols=TENDERMINT (oficial; 12 flags --tm-* obrigatorias, sem padrao; --seed = semente - n*1000).
  JVM 2: ObservadorTm (mesma semente/config) para contar timeouts DISPARADOS, pacotes por altura e por no, e conferir que o CSV por bloco e
         identico ao da JVM 1 (MD5 sem BlockHashCode).
V-B (bloco vb): SO a JVM 2. O MVPComparison NAO le --attack para TENDERMINT (so HOTSTUFF: MVPComparison.attackForMain) e o ByzantineSweep nao tem
         Tendermint; o EQUIVOCATION do Tendermint so e alcancavel pela API Java. LIMITE: em V-B nao ha segundo instrumento para conferir.
         resultado.json traz rc_mvp = null e blocos_mvp = null (campos do lote do piloto; o executar-lote-tm.py passa --rc-mvp-registrado).
MVPComparison ENGOLE falha de trial para o Tendermint (imprime "failed:" e termina com rc 0, sem rethrow como no HotStuff): por isso a
integridade exige blocos_mvp > 0 e MD5 igual entre as JVMs, nunca so rc == 0.
-XX:hashCode=2 OBRIGATORIO: aborta sem ele. Saida: <saida>/<id>/. Build: /tmp/opencode/val-tm (preparar-tm.py).
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
# DEST e o build PINADO. Em celula de portao NUNCA se redefine: TM_BUILD_DRYRUN so existe para o dry-run-comandos-tm.py (nao executa JVM).
DEST = Path(__import__("os").environ.get("TM_BUILD_DRYRUN") or "/tmp/opencode/val-tm")
FLAG_OBRIGATORIA = "-XX:hashCode=2"


def classpath(extra=False):
    cp = f"{DEST}/target/classes:" + (DEST / "cp.txt").read_text().strip()
    return (f"{DEST}/harness-classes:" + cp) if extra else cp


def flags_tm(cfg):
    v = cfg["variante"]
    return [f"--tm-propose-timeout={v['tm_propose']}", f"--tm-propose-delta={v['tm_propose_delta']}",
            f"--tm-prevote-timeout={v['tm_prevote']}", f"--tm-prevote-delta={v['tm_prevote_delta']}",
            f"--tm-precommit-timeout={v['tm_precommit']}", f"--tm-precommit-delta={v['tm_precommit_delta']}",
            f"--tm-batch-size={v['L']}", f"--tm-tx-size={v['s_tx']}", f"--tm-message-overhead={v['overhead_msg_B']}",
            f"--tm-signature-size={v['sig_voto_B']}", f"--tm-proposer-signature-size={v['sig_proponente_B']}",
            f"--tm-max-future-horizon={v['max_future_horizon']}"]


def cmd_mvp(cfg, saida):
    n = cfg["n"]
    return ["java", *cfg["jvm_flags"], "-cp", classpath(), "jabs.example.MVPComparison", "--protocols=TENDERMINT",
            f"--validators={n}", "--trials=1", f"--duration={cfg['duracao_s']}", f"--seed={cfg['semente'] - n * 1000}",
            f"--output={saida}", f"--csv-out={saida}/mvp-results.csv", *flags_tm(cfg)]


def cmd_obs(cfg, saida):
    n = cfg["n"]
    ataque = ([f"--attack={cfg['variante']['attack']}", f"--byzantine-count={cfg['byzantinos']}"]
              if cfg["variante"].get("attack") else [])
    return ["java", *cfg["jvm_flags"], "-cp", classpath(True), "ObservadorTm", f"--run-seed={cfg['semente']}", f"--validators={n}",
            f"--duration={cfg['duracao_s']}", f"--out-dir={saida}/obs", "--csv=tendermint-obs.csv", *flags_tm(cfg), *ataque,
            *cfg.get("flags_extras", [])]


def md5_sem_hashcode(path):
    h, idx, linhas = hashlib.md5(), None, 0
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
    p = saida / f"stderr-{nome}.log"
    txt = p.read_text(errors="replace").replace("\r", "\n").splitlines()
    p.write_text("\n".join([l for l in txt if "Simulation in progress" not in l][-40:]) + "\n")
    return rc, time.time() - t0


def main():
    cfg = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if FLAG_OBRIGATORIA not in cfg.get("jvm_flags", []):
        sys.exit(f"ERRO: {FLAG_OBRIGATORIA} obrigatorio (pre-registro); cfg sem a flag: {cfg['id']}")
    if not DEST.exists():
        sys.exit(f"ERRO: build {DEST} ausente; rode preparar-tm.py (so apos o pino)")
    base_saida = (Path(sys.argv[2]) if len(sys.argv) > 2 else BASE / "dados-brutos").resolve()
    saida = base_saida / cfg["id"]
    saida.mkdir(parents=True, exist_ok=True)
    (saida / "obs").mkdir(exist_ok=True)
    (saida / "cfg.json").write_text(json.dumps(cfg, indent=1, ensure_ascii=False), encoding="utf-8")
    so_obs = cfg["bloco"] == "vb"
    rc1, dt1 = (None, 0.0) if so_obs else rodar(cmd_mvp(cfg, str(saida)), saida, "mvp")
    rc2, dt2 = rodar(cmd_obs(cfg, str(saida)), saida, "observador")
    rss_mb = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss / 1024
    res = {"id": cfg["id"], "rc_mvp": rc1, "rc_observador": rc2, "tempo_parede_mvp_s": round(dt1, 2),
           "tempo_parede_observador_s": round(dt2, 2), "max_rss_mb": round(rss_mb, 1), "so_observador": so_obs}
    for nome, arq, dest in (("mvp", saida / f"tendermint-{cfg['n']}v-t0.csv", saida), ("obs", saida / "obs" / "tendermint-obs.csv", saida / "obs")):
        if nome == "mvp" and so_obs:
            res["csv_mvp"], res["blocos_mvp"] = None, None
            continue
        if not arq.exists():
            cand = sorted(dest.glob("tendermint-*v-t0.csv" if nome == "mvp" else "tendermint-obs.csv"))
            arq = cand[0] if cand else None
        if arq is not None and arq.exists():
            m, ln = md5_sem_hashcode(arq)
            res[f"csv_{nome}"] = arq.name + ".gz"
            res[f"md5_sem_hashcode_{nome}"] = m
            res[f"blocos_{nome}"] = ln
            gz(arq)
        else:
            res[f"csv_{nome}"] = None
            res[f"blocos_{nome}"] = 0
    (saida / "resultado.json").write_text(json.dumps(res, indent=1), encoding="utf-8")
    eq = None if so_obs else res.get("md5_sem_hashcode_mvp") == res.get("md5_sem_hashcode_obs")
    print(f"{cfg['id']} rc=({rc1},{rc2}) parede={dt1:.0f}+{dt2:.0f}s blocos={res['blocos_mvp']}/{res['blocos_obs']} equiv={eq}")
    sys.exit(0 if (rc1 in (0, None)) and rc2 == 0 else 1)


if __name__ == "__main__":
    main()

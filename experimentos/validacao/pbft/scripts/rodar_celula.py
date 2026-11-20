#!/usr/bin/env python3
"""Roda UMA celula do V-ABC do PBFT puro (bloco va): duas JVMs com a MESMA semente e -XX:hashCode=2:
  JVM 1: jabs.example.MVPComparison --protocols=PBFT (oficial; --seed = semente - n*1000)
  JVM 2: ObservadorPbft (pacotes e bytes por altura e por no; --run-seed = semente)
Grava em <saida>/<id>/: cfg.json, comando-*.txt, stdout/stderr-*.log, resultado.json, mvp-results*.csv, o CSV por bloco (.gz) e obs/.
Uso: rodar_celula.py <cfg.json> [dir_dados_brutos]
Saida 0 = as duas JVMs terminaram com rc 0. A equivalencia (MD5 do CSV por bloco sem BlockHashCode) vai no resultado.json.
"""
import gzip
import hashlib
import json
import os
try:
    import resource  # so Unix; no Windows o pico de RSS fica -1 (NAO MEDIDO, nunca zero)
except ImportError:
    resource = None
import shutil
import subprocess
import sys
import time
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
# DEST e o build PINADO. Em celula de portao NUNCA se redefine: PBFT_BUILD_DRYRUN so existe para dry-run (nao executa JVM de portao).
DEST = Path(os.environ.get("PBFT_BUILD_DRYRUN") or "/tmp/opencode/val-pbft")
FLAG_OBRIGATORIA = "-XX:hashCode=2"


def classpath(extra=False):
    cp = f"{DEST}/target/classes{os.pathsep}" + (DEST / "cp.txt").read_text().strip()
    return (f"{DEST}/harness-classes{os.pathsep}" + cp) if extra else cp


def flags_pbft(cfg):
    v = cfg["variante"]
    return [f"--pbft-max-future-horizon={v['max_future_horizon']}", f"--pbft-leader-policy={v['leader_policy']}"]


def cmd_mvp(cfg, saida):
    n = cfg["n"]
    return ["java", *cfg["jvm_flags"], "-cp", classpath(), "jabs.example.MVPComparison", "--protocols=PBFT",
            f"--validators={n}", "--trials=1", f"--duration={cfg['duracao_s']}", f"--seed={cfg['semente'] - n * 1000}",
            f"--output={saida}", f"--csv-out={saida}/mvp-results.csv", *flags_pbft(cfg)]


def cmd_obs(cfg, saida):
    n = cfg["n"]
    return ["java", *cfg["jvm_flags"], "-cp", classpath(True), "ObservadorPbft", f"--run-seed={cfg['semente']}", f"--validators={n}",
            f"--duration={cfg['duracao_s']}", f"--out-dir={saida}/obs", "--csv=pbft-obs.csv", *flags_pbft(cfg), *cfg.get("flags_extras", [])]


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
        sys.exit(f"ERRO: build {DEST} ausente; rode preparar-pbft.py (so apos o pino)")
    base_saida = (Path(sys.argv[2]) if len(sys.argv) > 2 else BASE / "dados-brutos").resolve()
    saida = base_saida / cfg["id"]
    saida.mkdir(parents=True, exist_ok=True)
    (saida / "obs").mkdir(exist_ok=True)
    (saida / "cfg.json").write_text(json.dumps(cfg, indent=1, ensure_ascii=False), encoding="utf-8")
    rc1, dt1 = rodar(cmd_mvp(cfg, str(saida)), saida, "mvp")
    rc2, dt2 = rodar(cmd_obs(cfg, str(saida)), saida, "observador")
    rss_mb = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss / 1024 if resource is not None else -1.0  # -1 = nao medido (Windows)
    res = {"id": cfg["id"], "rc_mvp": rc1, "rc_observador": rc2, "tempo_parede_mvp_s": round(dt1, 2),
           "tempo_parede_observador_s": round(dt2, 2), "max_rss_mb": round(rss_mb, 1), "so_observador": False}
    for nome, arq, dest, padrao in (("mvp", saida / f"pbft-{cfg['n']}v-t0.csv", saida, "pbft-*v-t0.csv"),
                                    ("obs", saida / "obs" / "pbft-obs.csv", saida / "obs", "pbft-obs.csv")):
        if not arq.exists():
            cand = sorted(dest.glob(padrao))
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
    eq = res.get("md5_sem_hashcode_mvp") == res.get("md5_sem_hashcode_obs")
    print(f"{cfg['id']} rc=({rc1},{rc2}) parede={dt1:.0f}+{dt2:.0f}s blocos={res['blocos_mvp']}/{res['blocos_obs']} equiv={eq}")
    sys.exit(0 if rc1 == 0 and rc2 == 0 else 1)


if __name__ == "__main__":
    main()

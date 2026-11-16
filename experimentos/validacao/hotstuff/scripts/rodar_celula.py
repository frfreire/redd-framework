#!/usr/bin/env python3
"""Executa UMA celula de V-A/V-C: duas JVMs em serie, uma semente, mesma convencao do piloto (rodar_celula do piloto).

JVM 1: jabs.example.MVPComparison (oficial). Em celulas `vano` acrescenta --load-record=true --load-record-t0=<t0>
       (registro de enlace por no; EXIGE -XX:hashCode=2, que o cfg ja traz em jvm_flags).
JVM 2: ObservadorVal (= ObservadorHotStuff do piloto + escrita de obs/pacotes-por-altura.csv.gz; EMENDA 1b) (mesma semente/config; sem load-record) so para contar timeouts disparados (instrumento) e
       conferir que o CSV por bloco e identico ao da JVM 1 (MD5 sem BlockHashCode).
Build: /tmp/opencode/val-hs (preparar-val.py). Saida: <saida>/<id>/. -XX:hashCode=2 OBRIGATORIO: aborta sem ele.
Saida do resultado.json compativel com o executar-lote (rc_mvp, rc_observador, blocos_mvp, blocos_obs).
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
DEST = Path("/tmp/opencode/val-hs")
FLAG_OBRIGATORIA = "-XX:hashCode=2"


def classpath(extra=False):
    cp = f"{DEST}/target/classes:" + (DEST / "cp.txt").read_text().strip()
    return (f"{DEST}/harness-classes:" + cp) if extra else cp


def flags_hs(cfg):
    v = cfg["variante"]
    return [f"--hs-view-timeout={v['view_timeout_s']}", f"--hs-max-future-view-horizon={v['max_future_view_horizon']}",
            f"--hs-batch-size={v['L']}", f"--hs-tx-size={v['s_tx']}",
            f"--hs-individual-signature-size={v['sig_individual_B']}",
            f"--hs-aggregated-signature-size={v['sig_agregada_B']}",
            f"--hs-aggregated-share-size={v['share_agregada_B']}", f"--hs-message-overhead={v['overhead_msg_B']}",
            f"--hs-signature-mode={v['signature_mode']}", f"--hs-leader-policy={v['leader_policy']}",
            f"--hs-voter-encoding={v['voter_encoding']}",
            *([f"--attack={v['attack']}", f"--byzantine-count={cfg['byzantinos']}"] if v.get("attack") else []),
            *cfg.get("flags_extras", [])]   # V-B: flag do modo 'fora da faixa' (so apos BYZ-fora-da-faixa-hotstuff)


def cmd_mvp(cfg, saida):
    n = cfg["n"]
    extra = []
    if cfg.get("load_record_t0") is not None:
        extra = ["--load-record=true", f"--load-record-t0={cfg['load_record_t0']}"]
    return ["java", *cfg["jvm_flags"], "-cp", classpath(), "jabs.example.MVPComparison", "--protocols=HOTSTUFF",
            f"--validators={n}", "--trials=1", f"--duration={cfg['duracao_s']}", f"--seed={cfg['semente'] - n * 1000}",
            f"--output={saida}", f"--csv-out={saida}/mvp-results.csv", *extra, *flags_hs(cfg)]


def cmd_obs(cfg, saida):
    n = cfg["n"]
    return ["java", *cfg["jvm_flags"], "-cp", classpath(True), "ObservadorVal", f"--run-seed={cfg['semente']}",
            f"--validators={n}", f"--duration={cfg['duracao_s']}", f"--out-dir={saida}/obs", "--csv=hotstuff-obs.csv",
            *flags_hs(cfg)]


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
        sys.exit(f"ERRO: {FLAG_OBRIGATORIA} obrigatorio (pre-registro, secao 6); cfg sem a flag: {cfg['id']}")
    base_saida = (Path(sys.argv[2]) if len(sys.argv) > 2 else BASE / "dados-brutos").resolve()
    saida = base_saida / cfg["id"]
    saida.mkdir(parents=True, exist_ok=True)
    (saida / "obs").mkdir(exist_ok=True)
    (saida / "cfg.json").write_text(json.dumps(cfg, indent=1, ensure_ascii=False), encoding="utf-8")
    rc1, dt1 = rodar(cmd_mvp(cfg, str(saida)), saida, "mvp")
    rc2, dt2 = rodar(cmd_obs(cfg, str(saida)), saida, "observador")
    rss_mb = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss / 1024
    res = {"id": cfg["id"], "rc_mvp": rc1, "rc_observador": rc2, "tempo_parede_mvp_s": round(dt1, 2),
           "tempo_parede_observador_s": round(dt2, 2), "max_rss_mb": round(rss_mb, 1)}
    n = cfg["n"]
    for nome, arq, dest in (("mvp", saida / f"hotstuff-{n}v-t0.csv", saida),
                            ("obs", saida / "obs" / "hotstuff-obs.csv", saida / "obs")):
        if not arq.exists():
            cand = sorted(dest.glob(f"hotstuff-*v-t0.csv" if nome == "mvp" else "hotstuff-obs.csv"))
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
    print(f"{cfg['id']} rc=({rc1},{rc2}) parede={dt1:.0f}+{dt2:.0f}s blocos={res['blocos_mvp']}/{res['blocos_obs']} "
          f"equiv={res.get('md5_sem_hashcode_mvp') == res.get('md5_sem_hashcode_obs')}")
    sys.exit(0 if rc1 == 0 and rc2 == 0 else 1)


if __name__ == "__main__":
    main()

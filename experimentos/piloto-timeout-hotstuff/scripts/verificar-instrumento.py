#!/usr/bin/env python3
"""Verificacoes de instrumento do piloto (barato): salva evidencia em dados-brutos/validacao/.

V1 hashCode: mesma celula com e sem -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 (a flag so e exigida por
   --load-record, que o piloto nao usa). Compara o CSV por bloco sem a coluna BlockHashCode e mede a parede.
V2 assinatura agregada: em modo INDIVIDUAL, --hs-aggregated-* nao pode alterar a saida (96 -> 200).
V3 overhead de mensagem (sensibilidade): --hs-message-overhead 10 -> 100 DEVE alterar a saida (sao bytes de pacote).
Uso: verificar-instrumento.py --saida dados-brutos/validacao
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import rodar_celula as rc  # noqa: E402


def celula(n, L, dur, sem, **v):
    var = {"view_timeout_s": 60, "max_future_view_horizon": 1000, "L": L, "s_tx": 512, "sig_individual_B": 64,
           "sig_agregada_B": 96, "share_agregada_B": 96, "overhead_msg_B": 10, "signature_mode": "INDIVIDUAL",
           "leader_policy": "ROTATING", "voter_encoding": "BITMAP", "attack": None}
    var.update(v)
    return {"n": n, "semente": sem, "duracao_s": dur, "byzantinos": 0, "variante": var}


def rodar(cfg, cid, saida, flags):
    cfg = dict(cfg)
    cfg["id"] = cid
    cfg["jvm_flags"] = flags
    p = saida / f"{cid}.cfg.json"
    p.write_text(json.dumps(cfg))
    t = time.time()
    r = subprocess.run([sys.executable, str(AQUI / "rodar_celula.py"), str(p), str(saida)], capture_output=True, text=True)
    res = json.loads((saida / cid / "resultado.json").read_text())
    res["parede_total_s"] = round(time.time() - t, 1)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--saida", required=True)
    a = ap.parse_args()
    saida = Path(a.saida).resolve()
    saida.mkdir(parents=True, exist_ok=True)
    rel = {"V1_hashcode": [], "V2_agregada_irrelevante_em_INDIVIDUAL": [], "V3_overhead_altera": []}
    sem_hash = ["-Xmx2g"]
    com_hash = ["-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2", "-Xmx2g"]
    for n, L, dur in ((4, 100, 30), (16, 100, 30), (4, 500, 30), (16, 500, 30)):
        c = celula(n, L, dur, 12345 + n * 1000 + 1)
        r0 = rodar(c, f"v1_semhash_n{n}_L{L}", saida, sem_hash)
        r1 = rodar(c, f"v1_hash2_n{n}_L{L}", saida, com_hash)
        rel["V1_hashcode"].append({"n": n, "L": L, "duracao_s": dur,
                                   "md5_sem_hashcode_mvp_semflag": r0["md5_sem_hashcode_mvp"],
                                   "md5_sem_hashcode_mvp_hashcode2": r1["md5_sem_hashcode_mvp"],
                                   "iguais": r0["md5_sem_hashcode_mvp"] == r1["md5_sem_hashcode_mvp"],
                                   "parede_mvp_s_semflag": r0["tempo_parede_mvp_s"],
                                   "parede_mvp_s_hashcode2": r1["tempo_parede_mvp_s"]})
    base = celula(16, 100, 30, 12345 + 16 * 1000 + 1)
    r0 = rodar(base, "v2_base", saida, sem_hash)
    r1 = rodar(celula(16, 100, 30, 12345 + 16 * 1000 + 1, sig_agregada_B=200, share_agregada_B=200), "v2_agregada200", saida, sem_hash)
    rel["V2_agregada_irrelevante_em_INDIVIDUAL"].append({"md5_base": r0["md5_sem_hashcode_mvp"], "md5_agregada200": r1["md5_sem_hashcode_mvp"],
                                                       "iguais": r0["md5_sem_hashcode_mvp"] == r1["md5_sem_hashcode_mvp"]})
    r2 = rodar(celula(16, 100, 30, 12345 + 16 * 1000 + 1, overhead_msg_B=100), "v3_ovh100", saida, sem_hash)
    rel["V3_overhead_altera"].append({"md5_ovh10": r0["md5_sem_hashcode_mvp"], "md5_ovh100": r2["md5_sem_hashcode_mvp"],
                                      "iguais": r0["md5_sem_hashcode_mvp"] == r2["md5_sem_hashcode_mvp"],
                                      "blocos_ovh10": r0["blocos_mvp"], "blocos_ovh100": r2["blocos_mvp"]})
    (saida / "verificacao-instrumento.json").write_text(json.dumps(rel, indent=1))
    print(json.dumps(rel, indent=1))


if __name__ == "__main__":
    main()

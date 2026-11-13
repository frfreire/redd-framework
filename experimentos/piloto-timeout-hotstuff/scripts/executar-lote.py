#!/usr/bin/env python3
"""Executa um lote de celulas em paralelo (um processo/JVM por celula, uma semente por processo).

Uso: executar-lote.py <dir_celulas> --saida <dir_brutos> [--paralelo 8] [--pular-existentes]
Le <dir_celulas>/manifesto.json; roda rodar_celula.py por celula (ate --paralelo ao mesmo tempo);
grava <dir_brutos>/execucao.json com rc e tempo de parede por celula e confere celulas esperadas x obtidas
(CSV por bloco presente e nao vazio). Celula com rc != 0 NAO e descartada em silencio: fica registrada.
"""
import argparse
import json
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

AQUI = Path(__file__).resolve().parent


def rodar(cfg_path, saida):
    t0 = time.time()
    r = subprocess.run([sys.executable, str(AQUI / "rodar_celula.py"), str(cfg_path), str(saida)],
                       capture_output=True, text=True)
    return cfg_path.stem, r.returncode, round(time.time() - t0, 1), r.stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("celulas")
    ap.add_argument("--saida", required=True)
    ap.add_argument("--paralelo", type=int, default=8)
    ap.add_argument("--pular-existentes", action="store_true")
    ap.add_argument("--rc-mvp-registrado", action="store_true",
                    help="celulas com ataque: MVPComparison pode falhar alto (0 blocos finalizados); o rc e REGISTRADO, nao e erro de execucao")
    a = ap.parse_args()
    if a.paralelo > 8:
        sys.exit("maxCelulasDeSimulacaoParalelas = 8 (enxame.config.json)")
    d = Path(a.celulas).resolve()
    man = json.loads((d / "manifesto.json").read_text())
    saida = Path(a.saida).resolve()
    saida.mkdir(parents=True, exist_ok=True)
    pend = []
    for cid in man["celulas"]:
        if a.pular_existentes and (saida / cid / "resultado.json").exists():
            continue
        pend.append(d / f"{cid}.json")
    print(f"{len(pend)} celula(s) a rodar de {man['n_celulas']} esperadas; paralelo={a.paralelo}", flush=True)
    res = []
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=a.paralelo) as ex:
        for cid, rc, dt, out in ex.map(lambda p: rodar(p, saida), pend):
            print(f"[{time.time()-t0:7.0f}s] {out or cid + ' rc=' + str(rc)}", flush=True)
            res.append({"id": cid, "rc": rc, "tempo_parede_s": dt})
    # confere esperadas x obtidas
    faltando, rc_nao_zero = [], []
    for cid in man["celulas"]:
        rj = saida / cid / "resultado.json"
        if not rj.exists():
            faltando.append(cid)
            continue
        j = json.loads(rj.read_text())
        if j["rc_observador"] != 0 or (j["rc_mvp"] != 0 and not a.rc_mvp_registrado):
            rc_nao_zero.append(cid)
        elif not a.rc_mvp_registrado and (j["blocos_mvp"] <= 0 or j["blocos_obs"] <= 0):
            faltando.append(cid)
    exec_path = saida / "execucao.json"
    antigo = json.loads(exec_path.read_text()) if exec_path.exists() else {"execucoes": []}
    antigo["execucoes"].append({"manifesto": str(d), "n_esperadas": man["n_celulas"], "executadas_agora": res,
                                "faltando_ou_vazias": faltando, "rc_nao_zero": rc_nao_zero,
                                "parede_total_s": round(time.time() - t0, 1)})
    exec_path.write_text(json.dumps(antigo, indent=1))
    print(f"esperadas={man['n_celulas']} faltando_ou_vazias={len(faltando)} rc_nao_zero={len(rc_nao_zero)}")
    sys.exit(0 if not faltando and not rc_nao_zero else 3)


if __name__ == "__main__":
    main()

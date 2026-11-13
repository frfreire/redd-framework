#!/usr/bin/env python3
"""Confere, por contagem, TODAS as celulas do piloto: esperadas x obtidas, codigos de saida, equivalencia
observador x MVPComparison (md5 do CSV por bloco sem BlockHashCode), semente declarada e flags --hs-* de cada comando.
Tambem verifica que os ataques SILENT/WITHHOLD/DOUBLE_SIGN sao recusados pelo MVPComparison para HOTSTUFF (evidencia
de que EQUIVOCATION e o unico ataque do repositorio disponivel para o HotStuff). Somente biblioteca padrao.
Saida: conferencia.json + stdout.
"""
import json
import subprocess
from collections import Counter
from pathlib import Path

B = Path(__file__).resolve().parent.parent
DEST = Path("/tmp/opencode/piloto-hs")

LOTES = [("medicao", "medicao-L100"), ("medicao", "medicao-L10"), ("medicao", "medicao-L500"),
         ("verificacao", "verificacao-m3-L100"), ("verificacao", "verificacao-m2-L100"),
         ("verificacao", "sens-T100m3-L10"), ("verificacao", "sens-T100m3-L500"),
         ("verificacao", "sens-overhead100"), ("ataque", "ataque"), ("sonda", "sonda"), ("sonda", "sonda-stable")]


def atq_recusado(nome):
    cp = f"{DEST}/target/classes:" + (DEST / "cp.txt").read_text().strip()
    r = subprocess.run(["java", "-cp", cp, "jabs.example.MVPComparison", "--protocols=HOTSTUFF", "--validators=4",
                        "--trials=1", "--duration=5", "--seed=1", "--output=/tmp/opencode/piloto-hs/atq-recusado",
                        "--csv-out=/tmp/opencode/piloto-hs/atq-recusado/r.csv", "--hs-view-timeout=60",
                        "--hs-max-future-view-horizon=1000", "--hs-batch-size=100", "--hs-tx-size=512",
                        "--hs-individual-signature-size=64", "--hs-aggregated-signature-size=96",
                        "--hs-aggregated-share-size=96", "--hs-message-overhead=10", "--hs-signature-mode=INDIVIDUAL",
                        "--hs-leader-policy=STABLE", "--hs-voter-encoding=BITMAP", f"--attack={nome}",
                        "--byzantine-count=1"], capture_output=True, text=True)
    msg = (r.stderr + r.stdout).strip().splitlines()
    return {"rc": r.returncode, "mensagem": next((l for l in msg if "nao implementado" in l or "desconhecido" in l or "Exception" in l), msg[-1] if msg else "")}


def visao_vs_altura():
    """Cruzamento independente do contador de timeouts: sem timeout, cada visao termina em DECIDE e a visao final de
    toda replica e a altura final + 1 (a replica ja entrou na proxima). Cada timeout adiciona +1 a visao de cada
    replica sem alterar a altura. Conta as celulas HONESTAS por (visao_max - altura_max)."""
    dist = Counter()
    por_lote = {}
    for base, lote in LOTES:
        if lote in ("ataque",):
            continue
        man = json.loads((B / "celulas" / lote / "manifesto.json").read_text())
        c = Counter()
        for cid in man["celulas"]:
            rs = json.loads((B / "dados-brutos" / base / cid / "obs" / "observador-resumo.json").read_text())
            por_replica = [v - rs["altura_maxima_finalizada"] for v in rs["visao_corrente_final_por_no"]]
            c[f"visao_menos_altura={min(por_replica)}..{max(por_replica)}"] += 1
            c["timeouts_total_!=0"] += 1 if rs["timeouts_disparados_total"] != 0 else 0
        por_lote[lote] = dict(c)
        dist.update(c)
    return dict(dist), por_lote


def main():
    tot = Counter()
    por_lote = {}
    for base, lote in LOTES:
        man = json.loads((B / "celulas" / lote / "manifesto.json").read_text())
        c = Counter()
        for cid in man["celulas"]:
            c["esperadas"] += 1
            rj = B / "dados-brutos" / base / cid / "resultado.json"
            if not rj.exists():
                c["faltando"] += 1
                continue
            r = json.loads(rj.read_text())
            c["obtidas"] += 1
            c["rc_mvp_0" if r["rc_mvp"] == 0 else "rc_mvp_nao0"] += 1
            c["rc_obs_0" if r["rc_observador"] == 0 else "rc_obs_nao0"] += 1
            if r["rc_mvp"] == 0:
                c["equiv_ok" if r.get("md5_sem_hashcode_mvp") == r.get("md5_sem_hashcode_obs") else "equiv_DIVERGE"] += 1
        por_lote[lote] = dict(c)
        tot.update(c)
        print(f"{lote:24s} {dict(c)}")
    recusas = {a: atq_recusado(a) for a in ("SILENT", "WITHHOLD", "DOUBLE_SIGN")}
    for a, v in recusas.items():
        print(f"--attack={a}: rc={v['rc']} {v['mensagem'][:160]}")
    vd, vpl = visao_vs_altura()
    print("visao_final - altura_final nas celulas honestas:", vd)
    out = {"visao_menos_altura_celulas_honestas": vd, "visao_menos_altura_por_lote": vpl,
           "total": dict(tot), "por_lote": por_lote, "ataques_recusados_pelo_MVPComparison_para_HOTSTUFF": recusas}
    (B / "conferencia.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))
    print("TOTAL", dict(tot))


if __name__ == "__main__":
    main()

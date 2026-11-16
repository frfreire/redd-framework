#!/usr/bin/env python3
"""AUXILIAR da etapa 2 (NAO pre-registrado; declarado como desvio). SOMENTE LEITURA: nao altera dado.
Confere, de forma independente do analisar-val.py, a integridade por celula (rc, MD5 por bloco entre as duas JVMs,
blocos mvp == obs, flag -XX:hashCode=2 no comando, sementes distintas, nenhuma celula extra/faltante) e soma o custo real
a partir dos resultado.json. Saida: conferencia-custo-real.json. Uso: aux-conferencia-custo.py"""
import json
import sys
from pathlib import Path

V = Path(__file__).resolve().parent.parent
esp = json.loads((V / "celulas-esperadas.json").read_text())["celulas"]
out = {"blocos": {}, "problemas": []}
sementes = {}
tot_cpu = 0.0
for b in ("va", "vano", "vc"):
    ids = {c["id"] for c in esp[b]}
    base = V / "dados-brutos" / b
    obtidas = {p.parent.name for p in base.glob("*/resultado.json")}
    cpu = par = 0.0
    maxrss = 0.0
    mx_cel = ("", 0.0)
    for i in sorted(obtidas & ids):
        r = json.loads((base / i / "resultado.json").read_text())
        cfg = json.loads((base / i / "cfg.json").read_text())
        if r["rc_mvp"] != 0 or r["rc_observador"] != 0:
            out["problemas"].append(f"{i}: rc {r['rc_mvp']},{r['rc_observador']}")
        if r.get("md5_sem_hashcode_mvp") != r.get("md5_sem_hashcode_obs"):
            out["problemas"].append(f"{i}: MD5 difere entre as JVMs")
        if r.get("blocos_mvp") != r.get("blocos_obs") or not r.get("blocos_mvp"):
            out["problemas"].append(f"{i}: blocos mvp/obs")
        if "-XX:hashCode=2" not in cfg.get("jvm_flags", []):
            out["problemas"].append(f"{i}: sem hashCode=2 no cfg")
        for nome in ("comando-mvp.txt", "comando-observador.txt"):
            if "-XX:hashCode=2" not in (base / i / nome).read_text():
                out["problemas"].append(f"{i}: {nome} sem hashCode=2")
        s = cfg.get("semente")
        sementes.setdefault((b, cfg["variante"]["signature_mode"], cfg["n"], s), []).append(i)
        t = r["tempo_parede_mvp_s"] + r["tempo_parede_observador_s"]
        cpu += t
        maxrss = max(maxrss, r["max_rss_mb"])
        if t > mx_cel[1]:
            mx_cel = (i, round(t, 2))
    out["blocos"][b] = {"esperadas": len(ids), "obtidas": len(obtidas), "faltando": sorted(ids - obtidas),
                        "extras": sorted(obtidas - ids), "soma_tempo_das_2_JVMs_s": round(cpu, 1),
                        "maior_celula": mx_cel, "max_rss_mb": maxrss}
    tot_cpu += cpu
    if ids != obtidas:
        out["problemas"].append(f"{b}: conjunto de celulas difere")
dup = {str(k): v for k, v in sementes.items() if len(v) > 1}
if dup:
    out["problemas"].append(f"chave (bloco,modo,n,semente) duplicada: {dup}")
out["soma_tempo_2_JVMs_total_s"] = round(tot_cpu, 1)
out["celulas_conferidas"] = sum(len(v) for v in sementes.values())
(V / "conferencia-custo-real.json").write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")
print(json.dumps(out, indent=1, ensure_ascii=False))
sys.exit(1 if out["problemas"] else 0)

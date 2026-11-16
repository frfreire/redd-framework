#!/usr/bin/env python3
"""V-B, etapa 3b: RESUMO DESCRITIVO e CUSTO REAL (so leitura de dados-brutos/vb e analise-vb.json; nao simula, nao decide nada).
Criado DEPOIS dos dados (arquivo novo, nao pre-registrado, nao esta na tabela de hashes): tudo o que sai daqui e DESCRITIVO / rotulo / pos-hoc
e NAO entra no veredito (o veredito e so o de analisar-vb.py, pre-registrado). Grava resumo-vb.json e custo-vb-real.json.
"""
import glob
import json
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
a = json.loads((VAL / "analise-vb.json").read_text())
res = {}
for p in sorted(glob.glob(str(VAL / "dados-brutos/vb/vb_*/resultado.json"))):
    r = json.loads(Path(p).read_text())
    res[r["id"]] = r

# --- custo real (tempo de parede por JVM, medido pelo rodar_celula.py; soma das duas JVMs = CPU-parede por celula)
cel = []
for cid, r in res.items():
    cel.append({"id": cid, "rc_mvp": r["rc_mvp"], "rc_observador": r["rc_observador"], "parede_mvp_s": r["tempo_parede_mvp_s"],
                "parede_obs_s": r["tempo_parede_observador_s"], "max_rss_mb": r["max_rss_mb"],
                "blocos_mvp": r["blocos_mvp"], "blocos_obs": r["blocos_obs"],
                "md5_igual_entre_JVMs": r["md5_sem_hashcode_mvp"] == r["md5_sem_hashcode_obs"] and r["blocos_mvp"] == r["blocos_obs"]})
tot = sum(c["parede_mvp_s"] + c["parede_obs_s"] for c in cel)
pior = max(cel, key=lambda c: c["parede_mvp_s"] + c["parede_obs_s"])
por_n = {}
for c in cel:
    n = int(c["id"].split("_n")[1].split("_")[0])
    por_n.setdefault(n, []).append(c["parede_mvp_s"] + c["parede_obs_s"])
custo = {"n_celulas_com_resultado": len(cel), "soma_parede_das_JVMs_s": round(tot, 1),
         "pior_celula": {"id": pior["id"], "s": round(pior["parede_mvp_s"] + pior["parede_obs_s"], 1)},
         "max_rss_mb": max(c["max_rss_mb"] for c in cel),
         "celulas_com_rc_diferente_de_zero": sorted(c["id"] for c in cel if c["rc_mvp"] or c["rc_observador"]),
         "celulas_com_md5_ou_blocos_diferentes_entre_JVMs": sorted(c["id"] for c in cel if not c["md5_igual_entre_JVMs"]),
         "soma_por_n_s": {str(n): round(sum(v), 1) for n, v in sorted(por_n.items())},
         "nota": "parede total do lote (8 paralelos) NAO esta aqui: vem do relogio do executar-lote (333 s ate a ultima celula, no stdout da sessao; "
                 "o log do lote nao foi gravado em arquivo, ver desvios). Estimativa pre-registrada: custo-vb-e7.json",
         "celulas": cel}
(VAL / "custo-vb-real.json").write_text(json.dumps(custo, indent=1, ensure_ascii=False))

# --- resumo descritivo por ponto (n, f): rotulos que NAO decidem
por_ponto = {}
for k in a["celulas"]:
    r = res[k["id"]]
    pt = por_ponto.setdefault((k["n"], k["f"]), {"classes": {}, "blocos": [], "ultimo_Fmin_s": [], "alturas_ge_20s": [], "por_semente": []})
    pt["classes"][k["classe"]] = pt["classes"].get(k["classe"], 0) + 1
    if k["classe"] == "VIVA":
        pt["blocos"].append(r["blocos_mvp"])
        pt["ultimo_Fmin_s"].append(k["ultimo_Fmin_s"])
        pt["alturas_ge_20s"].append(k["alturas_com_Fmin_ge_20s"])
        pt["por_semente"].append({"semente": k["semente"], "id": k["id"], "bizantinos": k["byzantine_ids_observador"],
                                  "blocos": r["blocos_mvp"], "alturas_ge_20s": k["alturas_com_Fmin_ge_20s"],
                                  "ultimo_Fmin_s": round(k["ultimo_Fmin_s"], 1)})
base = {n: sum(v["blocos"]) / len(v["blocos"]) for (n, f), v in por_ponto.items() if f == 0}
out = {"rotulo": "DESCRITIVO e POS-HOC (nao pre-registrado, nao decide): vazao relativa e dispersao por semente",
       "pontos": {}}
for (n, f), v in sorted(por_ponto.items()):
    if not v["blocos"]:
        out["pontos"][f"n{n}_f{f}"] = {"classes": v["classes"]}
        continue
    m = sum(v["blocos"]) / len(v["blocos"])
    out["pontos"][f"n{n}_f{f}"] = {"classes": v["classes"], "blocos_min": min(v["blocos"]), "blocos_max": max(v["blocos"]),
                                   "blocos_media": round(m, 1), "vazao_relativa_a_f0": round(m / base[n], 3),
                                   "ultimo_Fmin_s_min": round(min(v["ultimo_Fmin_s"]), 1),
                                   "alturas_ge_20s_min": min(v["alturas_ge_20s"]), "alturas_ge_20s_max": max(v["alturas_ge_20s"]),
                                   "conjuntos_bizantinos_distintos": len({tuple(s["bizantinos"]) for s in v["por_semente"]})}
out["n7_f3_por_semente"] = por_ponto[(7, 3)]["por_semente"]
out["n4_f2_conjuntos_bizantinos_das_executaveis"] = sorted({tuple(s["bizantinos"]) for s in por_ponto[(4, 2)]["por_semente"]})
(VAL / "resumo-vb.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))
print(json.dumps({k: custo[k] for k in ("n_celulas_com_resultado", "soma_parede_das_JVMs_s", "pior_celula", "max_rss_mb",
                                         "celulas_com_rc_diferente_de_zero", "celulas_com_md5_ou_blocos_diferentes_entre_JVMs", "soma_por_n_s")},
                 indent=1))
for p, v in out["pontos"].items():
    print(p, {k: v[k] for k in v if k != "classes"} | {"classes": v["classes"]})
print("n7f3", [(s["semente"], s["bizantinos"], s["blocos"]) for s in out["n7_f3_por_semente"]])
print("n4f2 conjuntos", out["n4_f2_conjuntos_bizantinos_das_executaveis"])

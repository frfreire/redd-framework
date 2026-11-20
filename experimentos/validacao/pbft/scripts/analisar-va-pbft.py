#!/usr/bin/env python3
"""Analise PRE-REGISTRADA de V-A do PBFT puro (agregado de rede por altura; e por no como ROTULO). So biblioteca padrao.
RASCUNHO: nada foi simulado; o --autoteste usa dado SINTETICO derivado da formula (nao do simulador).
NAO aprova: emite fatos e a classificacao pre-registrada; o veredito e do loop 3 (impacto ALTO: humano).

Criterios (PREREGISTRO.md, secao 3), fixados antes de qualquer dado:
  I  integridade por celula: rc=0 nas duas JVMs; blocos > 0; MD5 do CSV por bloco igual entre as JVMs; regime_heights >= min_alturas e
     igual ao numero de alturas de pacotes-por-altura; bytes do observador == CbRede_avg_B e CbRedeConsenso_avg_B do MVPComparison (+-0,01);
     SemAltura_B == 0 (cb e observador); no maximo 1 finalizacao por no por altura; 0 PRE_PREPARE rejeitados por nao-primario;
     0 votos descartados alem do teto; Tt_avg == -1 (nao medido, nunca zero); esperadas == obtidas.
  F  fidelidade a formula: |medido-formula|/formula <= tolerancia_F (1 %) em TODA celula integra.
       pacotes por altura  (n-1)(2n+1);   bytes de consenso por altura  (n-1)*O + 2n(n-1)*(H+O)   (H, O de config/fatores-va.json)
  E  b = inclinacao OLS de ln(medida) em ln(n) sobre as medias por n (5 pontos). Classes: b<0,5 O(1); <1,5 O(n); <2,5 O(n^2); senao >O(n^2).
DECIDEM: `rede_msgs` e `rede_consenso`: classe O(n^2) E fieis a formula. `rede_total` (com a carga do bloco, amostrada) so ROTULO.
Uso: analisar-va-pbft.py <dir_brutos_va> --saida analise.json
     analisar-va-pbft.py --autoteste
"""
import csv
import gzip
import json
import math
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
VAL = AQUI.parent
CFG = json.loads((VAL / "config" / "fatores-va.json").read_text(encoding="utf-8"))
H, O = CFG["pbft"]["H_hash_B"], CFG["pbft"]["O_overhead_B"]
TOL = CFG["tolerancia_F"]
MIN_ALTURAS = CFG["min_alturas_regime"]
FORM = {"rede_msgs": lambda n: (n - 1) * (2 * n + 1),
        "rede_consenso": lambda n: (n - 1) * O + 2 * n * (n - 1) * (H + O)}


def classe(b):
    return "O(1)" if b < 0.5 else "O(n)" if b < 1.5 else "O(n^2)" if b < 2.5 else "> O(n^2)"


def ols_b(ns, ys):
    x = [math.log(v) for v in ns]
    y = [math.log(v) for v in ys]
    mx, my = sum(x) / len(x), sum(y) / len(y)
    return sum((a - mx) * (c - my) for a, c in zip(x, y)) / sum((a - mx) ** 2 for a in x)


def linhas(path, opener=open):
    with opener(path, "rt", encoding="utf-8") as f:
        return [l for l in f if not l.startswith("#") and l.strip()]


def ler_celula(d):
    cfg = json.loads((d / "cfg.json").read_text())
    res = json.loads((d / "resultado.json").read_text())
    obs = json.loads((d / "obs" / "observador-resumo.json").read_text())
    out = {"id": cfg["id"], "n": cfg["n"], "semente": cfg["semente"], "rc_mvp": res["rc_mvp"], "rc_obs": res["rc_observador"],
           "blocos_mvp": res.get("blocos_mvp") or 0,
           "md5_igual": res.get("md5_sem_hashcode_mvp") == res.get("md5_sem_hashcode_obs") and (res.get("blocos_mvp") or 0) > 0,
           "uma_decisao": obs["max_finalizacoes_do_mesmo_no_na_mesma_altura"] == 1,
           "fora_do_turno": obs["pre_prepare_rejeitados_nao_primario_total"], "alem_teto": obs["votos_descartados_alem_do_teto_total"],
           "sem_altura_obs": obs["sem_altura_bytes_total"]}
    cb = d / "mvp-results-cb.csv"
    if cb.exists():
        rd = list(csv.DictReader(linhas(cb), delimiter=";"))
        if rd:
            out.update(rede_total=float(rd[0]["CbRede_avg_B"]), rede_consenso=float(rd[0]["CbRedeConsenso_avg_B"]),
                       regime_heights=int(rd[0]["regime_heights"]), sem_altura_cb=float(rd[0].get("SemAltura_B", "nan")))
    agg = d / "mvp-results.csv"
    if agg.exists():
        rd = list(csv.DictReader(linhas(agg), delimiter=";"))
        out["tt_nao_medido"] = bool(rd) and float(rd[0]["Tt_avg"]) == -1.0
    pk = d / "obs" / "pacotes-por-altura.csv.gz"
    if pk.exists():
        prs = list(csv.DictReader(linhas(pk, gzip.open), delimiter=","))
        if prs:
            out["rede_msgs"] = sum(int(r["pacotes"]) for r in prs) / len(prs)
            out["pacotes_alturas"] = len(prs)
            out["bytes_obs_total"] = sum(int(r["bytes_total"]) for r in prs) / len(prs)
            out["bytes_obs_consenso"] = sum(int(r["bytes_consenso"]) for r in prs) / len(prs)
    pn = d / "obs" / "por-no-por-altura.csv.gz"
    if pn.exists():
        acc = {"lider": {"p": [], "bc": []}, "validador": {"p": [], "bc": []}}
        for r in csv.DictReader(linhas(pn, gzip.open), delimiter=","):
            papel = "lider" if r["lider"] == "1" else "validador"
            acc[papel]["p"].append(int(r["pacotes"]))
            acc[papel]["bc"].append(int(r["bytes_consenso"]))
        for papel, v in acc.items():
            if v["p"]:
                out[f"no_{papel}_msgs"] = sum(v["p"]) / len(v["p"])
                out[f"no_{papel}_bytes_consenso"] = sum(v["bc"]) / len(v["bc"])
    out["integra"] = bool(out["rc_mvp"] == 0 and out["rc_obs"] == 0 and out["md5_igual"] and out["blocos_mvp"] > 0 and out["uma_decisao"]
                          and out["fora_do_turno"] == 0 and out["alem_teto"] == 0 and out["sem_altura_obs"] == 0
                          and out.get("sem_altura_cb") == 0 and out.get("tt_nao_medido") is True
                          and out.get("rede_total", 0) > 0 and out.get("rede_consenso", 0) > 0 and out.get("rede_msgs", 0) > 0
                          and abs(out.get("bytes_obs_total", -1) - out.get("rede_total", -2)) < 0.01
                          and abs(out.get("bytes_obs_consenso", -1) - out.get("rede_consenso", -2)) < 0.01
                          and out.get("pacotes_alturas") == out.get("regime_heights") and out.get("regime_heights", 0) >= MIN_ALTURAS)
    return out


def agrupar(cels, medidas, tol):
    por = {}
    for c in cels:
        por.setdefault(c["n"], []).append(c)
    res = {}
    for m in medidas:
        pontos, det = [], {}
        for n, cs in sorted(por.items()):
            ok = [c for c in cs if c["integra"] and m in c]
            if not ok:
                det[str(n)] = {"celulas": len(cs), "integras": 0}
                continue
            vals = [c[m] for c in ok]
            media = sum(vals) / len(vals)
            form = FORM[m](n)
            err = max(abs(v - form) / form for v in vals)
            det[str(n)] = {"celulas": len(cs), "integras": len(ok), "media": media, "min": min(vals), "max": max(vals), "formula": form,
                           "erro_rel_max": err, "fiel": err <= tol}
            pontos.append((n, media))
        r = {"por_n": det}
        if len({p[0] for p in pontos}) >= 4:
            b = ols_b([p[0] for p in pontos], [p[1] for p in pontos])
            bp = ols_b([n for n, _ in pontos], [FORM[m](n) for n, _ in pontos])
            r.update({"expoente_b": b, "classe_medida": classe(b), "b_previsto_pela_formula": bp, "classe_prevista_pela_formula": classe(bp),
                      "todos_fieis_a_formula": all(v.get("fiel") for v in det.values() if "fiel" in v),
                      "pontos_sem_celula_integra": [n for n, v in det.items() if v["integras"] == 0]})
        else:
            r["status"] = "DADOS INSUFICIENTES (< 4 valores de n com celula integra)"
        res[m] = r
    return res


def rotulo_total(cels):
    por = {}
    for c in cels:
        if c["integra"]:
            por.setdefault(c["n"], []).append(c["rede_total"])
    pts = [(n, sum(v) / len(v)) for n, v in sorted(por.items())]
    if len(pts) < 4:
        return {"status": "DADOS INSUFICIENTES"}
    b = ols_b([p[0] for p in pts], [p[1] for p in pts])
    return {"expoente_b": b, "classe_medida": classe(b), "nota": "ROTULO, nao decide: o bloco e amostrado de BITCOIN_BLOCK_SIZE_2020"}


def veredito_va(integr, res):
    ruins = {k: v for k, v in integr.items() if k in ("faltando", "extras", "nao_integras") and v}
    if ruins:
        return {"veredito": "INCONCLUSIVO (instrumento)", "motivos": [f"{k}: {v}" for k, v in ruins.items()]}
    motivos = []
    for m in ("rede_msgs", "rede_consenso"):
        r = res[m]
        if "expoente_b" not in r:
            return {"veredito": "INCONCLUSIVO (instrumento)", "motivos": [f"{m}: {r.get('status')}"]}
        if r["pontos_sem_celula_integra"]:
            return {"veredito": "INCONCLUSIVO (instrumento)", "motivos": [f"{m}: n sem celula integra {r['pontos_sem_celula_integra']}"]}
        if r["classe_medida"] != "O(n^2)":
            motivos.append(f"{m}: classe medida {r['classe_medida']} (b = {r['expoente_b']:.3f}) != O(n^2)")
        if not r["todos_fieis_a_formula"]:
            ruim = {n: round(v["erro_rel_max"], 5) for n, v in r["por_n"].items() if "fiel" in v and not v["fiel"]}
            motivos.append(f"{m}: ponto infiel a formula (tolerancia {TOL}): {ruim}")
    return {"veredito": "INCONSISTENTE" if motivos else "CONSISTENTE", "motivos": motivos}


def analisar(dir_va, esperadas):
    cels = [ler_celula(p.parent) for p in sorted(Path(dir_va).glob("*/cfg.json"))]
    ids = {c["id"] for c in cels}
    esp = {c["id"] for c in esperadas}
    integr = {"esperadas": len(esp), "obtidas": len(ids), "faltando": sorted(esp - ids), "extras": sorted(ids - esp),
              "nao_integras": sorted(c["id"] for c in cels if not c["integra"])}
    res = agrupar(cels, ["rede_msgs", "rede_consenso"], TOL)
    return {"integridade": integr, "va": res, "rede_total_ROTULO_NAO_DECIDE": rotulo_total(cels),
            "va_veredito_preregistrado": veredito_va(integr, res), "tolerancia_F": TOL, "min_alturas_regime": MIN_ALTURAS,
            "por_no_ROTULO_NAO_DECIDE": {k: sum(c[k] for c in cels if c["integra"] and k in c) / max(1, sum(1 for c in cels if c["integra"] and k in c))
                                         for k in ("no_lider_msgs", "no_validador_msgs", "no_lider_bytes_consenso", "no_validador_bytes_consenso")}}


def autoteste():
    def cel(n, k, fator=1.0, integra=True):
        return {"id": f"pva_n{n}_s{k:02d}", "n": n, "integra": integra, "rede_msgs": FORM["rede_msgs"](n) * fator,
                "rede_consenso": FORM["rede_consenso"](n) * fator, "rede_total": 1e6 * n}
    ns = (4, 7, 10, 16, 31)
    cels = [cel(n, k) for n in ns for k in range(1, 6)]
    r = agrupar(cels, ["rede_msgs", "rede_consenso"], TOL)
    assert r["rede_msgs"]["classe_medida"] == "O(n^2)" and r["rede_msgs"]["todos_fieis_a_formula"]
    integr = {"faltando": [], "extras": [], "nao_integras": []}
    assert veredito_va(integr, r)["veredito"] == "CONSISTENTE"
    # -0,5 % (como a sonda de n = 16): dentro de 1 %; -2 % em n = 31: fora
    cels2 = [cel(n, k, 0.995 if n == 16 else 1.0) for n in ns for k in range(1, 6)]
    assert veredito_va(integr, agrupar(cels2, ["rede_msgs", "rede_consenso"], TOL))["veredito"] == "CONSISTENTE"
    cels3 = [cel(n, k, 0.98 if n == 31 else 1.0) for n in ns for k in range(1, 6)]
    assert veredito_va(integr, agrupar(cels3, ["rede_msgs", "rede_consenso"], TOL))["veredito"] == "INCONSISTENTE"
    # classe errada: linear
    cels4 = [{**cel(n, k), "rede_msgs": 100.0 * n, "rede_consenso": 100.0 * n} for n in ns for k in range(1, 6)]
    assert veredito_va(integr, agrupar(cels4, ["rede_msgs", "rede_consenso"], TOL))["veredito"] == "INCONSISTENTE"
    # n sem celula integra
    cels5 = [cel(n, k, integra=(n != 16)) for n in ns for k in range(1, 6)]
    assert veredito_va(integr, agrupar(cels5, ["rede_msgs", "rede_consenso"], TOL))["veredito"] == "INCONCLUSIVO (instrumento)"
    assert FORM["rede_msgs"](4) == 27 and FORM["rede_consenso"](4) == 1038 and FORM["rede_consenso"](31) == 78420
    print("autoteste OK (dado SINTETICO derivado da formula; nao e saida do simulador)")


def main():
    a = sys.argv[1:]
    if a[:1] == ["--autoteste"]:
        autoteste()
        return
    if len(a) < 3 or "--saida" not in a:
        sys.exit(__doc__)
    esp = json.loads((VAL / "celulas-esperadas.json").read_text(encoding="utf-8"))["celulas"]["va"]
    doc = analisar(a[0], esp)
    Path(a[a.index("--saida") + 1]).write_text(json.dumps(doc, indent=1, ensure_ascii=False), encoding="utf-8")
    print("ok ->", a[a.index("--saida") + 1])


if __name__ == "__main__":
    main()

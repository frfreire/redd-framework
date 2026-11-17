#!/usr/bin/env python3
"""Analise PRE-REGISTRADA de V-A (agregado de rede + por no, das MESMAS celulas) e V-C ordinal do Tendermint. So biblioteca padrao.
RASCUNHO da etapa 1: nada foi simulado; o --autoteste usa dado SINTETICO derivado da formula (nao do simulador).
NAO aprova: emite fatos e a classificacao pre-registrada; o veredito e do loop 3 (impacto ALTO: humano).

Criterios (PREREGISTRO.md, secao 3), fixados antes de qualquer dado:
  I  integridade por celula: rc=0 nas duas JVMs; blocos_mvp > 0 (o MVPComparison ENGOLE falha de trial do Tendermint: rc 0 nao basta);
     MD5 do CSV por bloco igual entre as JVMs; timeouts_disparados_total == 0 (A1'); round_final_maximo == 0, descartes INVALID_VALUE == 0,
     equivocacoes == 0 e pacotes sem altura (voto nil) == 0 (caminho feliz); regime_heights >= 100; pacotes-por-altura com regime_heights
     alturas e bytes do observador == CbRede_avg_B do MVPComparison (+-0,01); esperadas == obtidas.
  F  fidelidade a formula (formula-analitica-tm.py, derivada do CODIGO): |medido-formula|/formula <= 0,001 em TODA celula integra.
  E  b = inclinacao OLS de ln(medida) em ln(n) sobre as medias por n (5 pontos). Classes: b<0,5 O(1); <1,5 O(n); <2,5 O(n^2); senao >O(n^2).
DECIDEM (V-A, agregado de rede por altura, ADR-0009 D3b "V-A no sentido O(n^2)"): `rede_msgs` (pacotes) classe O(n^2) e `rede_consenso`
(bytes sem a carga) classe O(n^2), ambas fieis a formula. `rede_total` (com a carga) e REPORTADA e so rotulada (prevista O(n) ate n=31:
o termo quadratico vale 11,5 % em n=31). POR NO (proponente x validador, mesmas celulas): SO ROTULO (nao decide).
Uso: analisar-va-tm.py <dir_brutos_va> [--vc <dir_brutos_vc>] --saida analise.json
     analisar-va-tm.py --autoteste
"""
import csv
import glob
import gzip
import importlib.util
import json
import math
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("formula", AQUI / "formula-analitica-tm.py")
FORM = importlib.util.module_from_spec(spec)
spec.loader.exec_module(FORM)

TOL_REDE = 0.001
MIN_ALTURAS = 100
T0_TRANSIENTE = 20.0
DECIDEM = {"rede_msgs": "O(n^2)", "rede_consenso": "O(n^2)"}
ROTULO_AGREGADO = ["rede_total"]
ROTULO_NO = ["no_validador_msgs", "no_proponente_msgs", "no_validador_bytes_consenso", "no_proponente_bytes_consenso",
             "no_validador_bytes_total", "no_proponente_bytes_total"]


def classe(b):
    return FORM.classe(b)


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
           "timeouts": obs["timeouts_disparados_total"], "round_max": obs["round_final_maximo"],
           "invalid": obs["descartes_INVALID_VALUE_total"], "equiv": obs["equivocacoes_observadas_total"],
           "sem_altura_pacotes": obs["sem_altura_pacotes_total"], "pico_futuras": obs["pico_mensagens_de_altura_futura_max_por_no"],
           "max_lead": obs["max_lead_de_alturas"]}
    cb = d / "mvp-results-cb.csv"
    if cb.exists():
        rd = list(csv.DictReader(linhas(cb), delimiter=";"))
        if rd:
            out.update(rede_total=float(rd[0]["CbRede_avg_B"]), rede_consenso=float(rd[0]["CbRedeConsenso_avg_B"]),
                       regime_heights=int(rd[0]["regime_heights"]))
    pk = d / "obs" / "pacotes-por-altura.csv.gz"
    if pk.exists():
        with gzip.open(pk, "rt", encoding="utf-8") as fh:
            prs = list(csv.DictReader(l for l in fh if not l.startswith("#")))
        if prs:
            out["rede_msgs"] = sum(int(r["pacotes"]) for r in prs) / len(prs)
            out["pacotes_alturas"] = len(prs)
            out["bytes_obs_total"] = sum(int(r["bytes_total"]) for r in prs) / len(prs)
    pn = d / "obs" / "por-no-por-altura.csv.gz"
    if pn.exists():
        por_h = {}
        with gzip.open(pn, "rt", encoding="utf-8") as fh:
            for r in csv.DictReader(l for l in fh if not l.startswith("#")):
                por_h.setdefault(int(r["height"]), []).append(r)
        acc = {k: [] for k in ROTULO_NO}
        for h, rs in por_h.items():
            prop = [r for r in rs if r["proponente_round0"] == "1"]
            val = [r for r in rs if r["proponente_round0"] == "0"]
            if len(prop) != 1 or not val:
                continue
            for papel, grupo in (("proponente", prop), ("validador", val)):
                acc[f"no_{papel}_msgs"].append(sum(int(r["pacotes"]) for r in grupo) / len(grupo))
                acc[f"no_{papel}_bytes_consenso"].append(sum(int(r["bytes_consenso"]) for r in grupo) / len(grupo))
                acc[f"no_{papel}_bytes_total"].append(sum(int(r["bytes_total"]) for r in grupo) / len(grupo))
        for k, v in acc.items():
            if v:
                out[k] = sum(v) / len(v)
    out["integra"] = bool(out["rc_mvp"] == 0 and out["rc_obs"] == 0 and out["md5_igual"] and out["blocos_mvp"] > 0
                          and out["timeouts"] == 0 and out["round_max"] == 0 and out["invalid"] == 0 and out["equiv"] == 0
                          and out["sem_altura_pacotes"] == 0 and out.get("rede_total", 0) > 0 and out.get("rede_consenso", 0) > 0
                          and out.get("rede_msgs", 0) > 0 and abs(out.get("bytes_obs_total", -1) - out.get("rede_total", -2)) < 0.01
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
            form = FORM.MEDIDAS[m](n)
            err = max(abs(v - form) / form for v in vals)
            det[str(n)] = {"celulas": len(cs), "integras": len(ok), "media": media, "min": min(vals), "max": max(vals), "formula": form,
                           "erro_rel_max": err, "fiel": err <= tol}
            pontos.append((n, media))
        r = {"por_n": det}
        if len({p[0] for p in pontos}) >= 4:
            b = FORM.ols_b([p[0] for p in pontos], [p[1] for p in pontos])
            prev = FORM.previsoes()[m]
            r.update({"expoente_b": b, "classe_medida": classe(b), "b_previsto": prev["b"], "classe_prevista_pela_formula": prev["classe"],
                      "bate_com_formula": classe(b) == prev["classe"], "todos_fieis_a_formula": all(v.get("fiel") for v in det.values() if "fiel" in v),
                      "pontos_sem_celula_integra": [n for n, v in det.items() if v["integras"] == 0]})
        else:
            r["status"] = "DADOS INSUFICIENTES (< 4 valores de n com celula integra)"
        res[m] = r
    return res


def veredito_va(integr, res):
    """INCONCLUSIVO (instrumento): celula faltante, extra, nao integra ou com timeout; ou n sem celula integra.
    INCONSISTENTE: rede_msgs ou rede_consenso com classe != O(n^2), ou algum ponto infiel a formula (F).
    CONSISTENTE: nenhum dos anteriores. Nao e aprovacao: o loop 3 (humano) decide."""
    ruins = {k: v for k, v in integr.items() if k in ("faltando", "extras", "nao_integras", "com_timeout") and v}
    if ruins:
        return {"veredito": "INCONCLUSIVO (instrumento)", "motivo": ruins}
    motivos, rotulos = [], []
    for m, r in res.items():
        if "expoente_b" not in r or r["pontos_sem_celula_integra"]:
            if m in DECIDEM:
                return {"veredito": "INCONCLUSIVO (instrumento)", "motivo": f"{m}: {r.get('status') or 'n sem celula integra'}"}
            continue
        if m in DECIDEM:
            if r["classe_medida"] != DECIDEM[m]:
                motivos.append(f"{m}: classe {r['classe_medida']} != criterio {DECIDEM[m]} (b={r['expoente_b']:.3f})")
            if not r["todos_fieis_a_formula"]:
                motivos.append(f"{m}: ponto infiel a formula do codigo")
        else:
            rotulos.append(f"{m} (so rotulo): classe {r['classe_medida']} (prevista {r['classe_prevista_pela_formula']}), "
                           f"fiel={r['todos_fieis_a_formula']}, b={r['expoente_b']:.3f}")
    return {"veredito": "INCONSISTENTE" if motivos else "CONSISTENTE", "motivos": motivos, "rotulos_nao_decisorios": rotulos}


def ler_tb(d):
    cfg = json.loads((d / "cfg.json").read_text())
    res = json.loads((d / "resultado.json").read_text())
    obs = json.loads((d / "obs" / "observador-resumo.json").read_text())
    out = {"id": cfg["id"], "n": cfg["n"], "rc_mvp": res["rc_mvp"], "rc_obs": res["rc_observador"], "timeouts": obs["timeouts_disparados_total"],
           "md5_igual": res.get("md5_sem_hashcode_mvp") == res.get("md5_sem_hashcode_obs") and (res.get("blocos_mvp") or 0) > 0}
    gzs = glob.glob(str(d / "tendermint-*v-t0.csv.gz"))
    if gzs:
        tb = []
        with gzip.open(gzs[0], "rt", encoding="utf-8") as f:
            for r in csv.DictReader(l for l in f if not l.startswith("#")):
                if float(r["Time"]) >= T0_TRANSIENTE:
                    tb.append(float(r["BlockFinalizationTime"]))   # Tb do PRIMEIRO no a finalizar (granularidade ADR-0006/ADR-0015 D1)
        if tb:
            dur = cfg["duracao_s"] - T0_TRANSIENTE
            out.update(Tb_avg_s=sum(tb) / len(tb), blocos=len(tb), vazao_tx_por_s=len(tb) / dur * cfg["variante"]["L"])
    out["integra"] = bool(out["rc_mvp"] == 0 and out["rc_obs"] == 0 and out["md5_igual"] and out["timeouts"] == 0 and "Tb_avg_s" in out)
    return out


def analisar_vc(dir_vc, saida):
    cels = [ler_tb(Path(d).parent) for d in sorted(glob.glob(f"{dir_vc}/*/cfg.json"))]
    esp = {c["id"] for c in json.loads((AQUI.parent / "celulas-esperadas.json").read_text())["celulas"]["vc"]}
    por = {}
    for c in cels:
        if c["integra"]:
            por.setdefault(c["n"], []).append(c)
    out = {"nota": "so fatos medidos; V-C numerico PROPOSTO INVIAVEL (aguarda confirmacao do humano); ver FONTES-V-C.md",
           "integridade": {"esperadas": len(esp), "obtidas": len(cels), "faltando": sorted(esp - {c["id"] for c in cels}),
                           "nao_integras": [c["id"] for c in cels if not c["integra"]], "com_timeout": [c["id"] for c in cels if c["timeouts"] > 0]},
           "por_n": {}}
    for n, cs in sorted(por.items()):
        out["por_n"][f"n{n}"] = {"celulas_integras": len(cs), "Tb_avg_s": sum(c["Tb_avg_s"] for c in cs) / len(cs),
                                 "vazao_tx_por_s_media": sum(c["vazao_tx_por_s"] for c in cs) / len(cs)}
    a, b = out["por_n"].get("n4"), out["por_n"].get("n16")
    out["ordem_n4_n16"] = ({"ordem_ok": b["Tb_avg_s"] > a["Tb_avg_s"] and b["vazao_tx_por_s_media"] < a["vazao_tx_por_s_media"],
                            "Tb_4": a["Tb_avg_s"], "Tb_16": b["Tb_avg_s"], "vazao_4": a["vazao_tx_por_s_media"], "vazao_16": b["vazao_tx_por_s_media"]}
                           if a and b else {"ordem_ok": None, "status": "INCONCLUSIVO (falta n=4 ou n=16 integro)"})
    out["rotulo"] = "TESTE ORDINAL FRACO; NAO e confronto numerico; nao valida magnitude; fora do veredito de V-A"
    Path(saida).write_text(json.dumps(out, indent=1))
    return out


def analisar(dir_va, saida):
    esp = json.loads((AQUI.parent / "celulas-esperadas.json").read_text())
    cels = [ler_celula(Path(d).parent) for d in sorted(glob.glob(f"{dir_va}/*/cfg.json"))]
    esperadas = {c["id"] for c in esp["celulas"]["va"]}
    obtidas = {c["id"] for c in cels}
    out = {"integridade": {"esperadas": len(esperadas), "obtidas": len(obtidas), "faltando": sorted(esperadas - obtidas),
                           "extras": sorted(obtidas - esperadas), "nao_integras": [c["id"] for c in cels if not c["integra"]],
                           "com_timeout": [c["id"] for c in cels if c["timeouts"] > 0]}}
    out["va"] = agrupar(cels, list(DECIDEM) + ROTULO_AGREGADO, TOL_REDE)
    out["va_veredito_preregistrado"] = veredito_va(out["integridade"], out["va"])
    out["va_por_no_ROTULO_NAO_DECIDE"] = agrupar(cels, ROTULO_NO, 0.02)
    out["retencao_sem_ataque_e_sem_timeout"] = {
        "pico_mensagens_de_altura_futura_max_entre_celulas": max((c["pico_futuras"] for c in cels), default=None),
        "max_lead_de_alturas": max((c["max_lead"] for c in cels), default=None),
        "nota": "contador do nucleo (peakFutureMessages); o TETO da PARIDADE ainda nao existe no codigo: a checagem 'teto nao atingido' so e mecanica "
                "se, no commit pinado, o codigo expuser um contador de descarte por teto (a definir; observador a ajustar e re-hashear)"}
    Path(saida).write_text(json.dumps(out, indent=1, ensure_ascii=False))
    return out


def autoteste():
    """Dado SINTETICO (formula) em /tmp/opencode/val-autoteste-tm: confere a logica do analisador, NAO o simulador."""
    import shutil
    base = Path("/tmp/opencode/val-autoteste-tm")
    if base.exists():
        shutil.rmtree(base)
    esp = json.loads((AQUI.parent / "celulas-esperadas.json").read_text())
    for c in esp["celulas"]["va"]:
        d = base / "va" / c["id"]
        (d / "obs").mkdir(parents=True)
        n = c["n"]
        (d / "cfg.json").write_text(json.dumps({"id": c["id"], "n": n, "semente": c["semente"], "duracao_s": 120, "variante": {"L": 100}}))
        (d / "resultado.json").write_text(json.dumps({"rc_mvp": 0, "rc_observador": 0, "md5_sem_hashcode_mvp": "x", "md5_sem_hashcode_obs": "x",
                                                      "blocos_mvp": 200}))
        (d / "obs" / "observador-resumo.json").write_text(json.dumps({
            "timeouts_disparados_total": 0, "round_final_maximo": 0, "descartes_INVALID_VALUE_total": 0, "equivocacoes_observadas_total": 0,
            "sem_altura_pacotes_total": 0, "pico_mensagens_de_altura_futura_max_por_no": 3, "max_lead_de_alturas": 1}))
        with gzip.open(d / "obs" / "pacotes-por-altura.csv.gz", "wt") as fh:
            fh.write("# sintetico\nheight,pacotes,bytes_total,bytes_consenso\n")
            for h in range(2, 102):
                fh.write(f"{h},{FORM.rede_msgs(n)},{FORM.rede_total(n)},{FORM.rede_consenso(n)}\n")
        with gzip.open(d / "obs" / "por-no-por-altura.csv.gz", "wt") as fh:
            fh.write("# sintetico\nheight,node,proponente_round0,pacotes,bytes_total,bytes_consenso\n")
            for h in range(2, 102):
                prop = (h - 1) % n
                for i in range(n):
                    papel = "proponente" if i == prop else "validador"
                    fh.write(f"{h},{i},{1 if i == prop else 0},{FORM.no_msgs(n, papel)},{FORM.no_bytes_total(n, papel)},"
                             f"{FORM.no_bytes_consenso(n, papel)}\n")
        (d / "mvp-results-cb.csv").write_text("# x\nproject;n;trials;regime_heights;CbRede_avg_B;CbRedeConsenso_avg_B\n"
                                              f"TENDERMINT;{n};1;100;{FORM.rede_total(n)};{FORM.rede_consenso(n)}\n")
    o = analisar(base / "va", base / "analise.json")
    for m, r in {**o["va"], **o["va_por_no_ROTULO_NAO_DECIDE"]}.items():
        print(f"[sintetico] {m:30s} b={r['expoente_b']:.3f} classe={r['classe_medida']:7s} formula={r['classe_prevista_pela_formula']:7s} fiel={r['todos_fieis_a_formula']}")
    print("[sintetico] veredito va:", o["va_veredito_preregistrado"]["veredito"], o["va_veredito_preregistrado"].get("rotulos_nao_decisorios"))
    assert o["va_veredito_preregistrado"]["veredito"] == "CONSISTENTE"
    # perturbacao +1 % em rede_consenso de uma celula tem de ser detectada como infiel => INCONSISTENTE
    cbp = base / "va" / esp["celulas"]["va"][0]["id"] / "mvp-results-cb.csv"
    t = cbp.read_text().splitlines()
    c = t[-1].split(";")
    c[5] = str(float(c[5]) * 1.01)
    cbp.write_text("\n".join(t[:-1] + [";".join(c)]) + "\n")
    o2 = analisar(base / "va", base / "analise-pert.json")
    print("[sintetico] perturbacao +1% ->", o2["va_veredito_preregistrado"]["veredito"], o2["va_veredito_preregistrado"].get("motivos"))
    assert o2["va_veredito_preregistrado"]["veredito"] == "INCONSISTENTE"
    # celula com timeout => INCONCLUSIVO
    rp = base / "va" / esp["celulas"]["va"][1]["id"] / "obs" / "observador-resumo.json"
    j = json.loads(rp.read_text())
    j["timeouts_disparados_total"] = 1
    rp.write_text(json.dumps(j))
    o3 = analisar(base / "va", base / "analise-timeout.json")
    print("[sintetico] timeout em 1 celula ->", o3["va_veredito_preregistrado"]["veredito"])
    assert o3["va_veredito_preregistrado"]["veredito"].startswith("INCONCLUSIVO")
    # V-C ordinal sintetico
    for c in esp["celulas"]["vc"]:
        d = base / "vc" / c["id"]
        (d / "obs").mkdir(parents=True)
        (d / "cfg.json").write_text(json.dumps({"id": c["id"], "n": c["n"], "duracao_s": 120, "variante": {"L": 100}}))
        (d / "resultado.json").write_text(json.dumps({"rc_mvp": 0, "rc_observador": 0, "md5_sem_hashcode_mvp": "x", "md5_sem_hashcode_obs": "x", "blocos_mvp": 9}))
        (d / "obs" / "observador-resumo.json").write_text(json.dumps({"timeouts_disparados_total": 0}))
        passo = 0.15 if c["n"] == 4 else 0.25
        with gzip.open(d / f"tendermint-{c['n']}v-t0.csv.gz", "wt") as fh:
            fh.write("Time,NodeID,BlockHeight,BlockFinalizationTime\n")
            t_ = 1.0
            while t_ < 120:
                fh.write(f"{t_},1,1,{passo * 0.9}\n")
                t_ += passo
    ovc = analisar_vc(base / "vc", base / "analise-vc.json")
    print("[sintetico] vc ordem:", ovc["ordem_n4_n16"])
    assert ovc["ordem_n4_n16"]["ordem_ok"] is True
    print("autoteste OK (dado SINTETICO derivado da formula; nao e saida do simulador)")


def main():
    a = sys.argv[1:]
    if a[:1] == ["--autoteste"]:
        return autoteste()
    if "--saida" not in a or not a or a[0].startswith("--"):
        sys.exit(__doc__)
    analisar(a[0], a[a.index("--saida") + 1])
    if "--vc" in a:
        analisar_vc(a[a.index("--vc") + 1], a[a.index("--saida") + 1].replace(".json", "-vc.json"))
    print("ok ->", a[a.index("--saida") + 1])


if __name__ == "__main__":
    main()

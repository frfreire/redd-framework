#!/usr/bin/env python3
"""Analise PRE-REGISTRADA de V-A (agregado de rede = bloco `va`; por no = bloco `vano`). Somente biblioteca padrao.
NAO aprova: emite fatos e a classificacao pre-registrada; o veredito e do loop 3 (impacto ALTO: humano).

Criterios (PREREGISTRO.md, secao 3), todos fixados antes de qualquer dado:
  I   integridade por celula: rc=0 nas duas JVMs; MD5 do CSV por bloco igual entre as JVMs; timeouts_disparados_total == 0
      (A1'); esperadas == obtidas. Celula que falha em I NAO e excluida do relatorio: e listada e marca o ponto.
  F   fidelidade: |medido - formula|/formula <= 0,001 (agregado) em TODA celula integra; por no <= 0,02. A formula
      (formula-analitica.py) foi derivada do CODIGO, nao deste dado.
  E   expoente b = inclinacao OLS de ln(bytes por bloco) em ln(n) sobre as medias por n (5 pontos); regra do ponto medio:
      b < 0,5 => O(1); 0,5 <= b < 1,5 => O(n); b >= 1,5 => O(n^2).
  S   validar-complexidade.py roda e e REPORTADO (esperado linear e esperado quadratico), mas NAO decide (dado
      deterministico sem ruido: o AIC enxerga qualquer curvatura; ver PREREGISTRO.md, 3.3).
EMENDA 1b (16/11/2026, antes de qualquer dado; PREREGISTRO.md, "Emendas antes dos dados"): o modo INDIVIDUAL RESPONDE por P4a e o
criterio de V-A e "O(n) em autenticadores e O(n^2) em bytes": `rede_msgs` (pacotes por bloco = autenticadores) deve ter classe O(n) e
`rede_consenso` (bytes sem a carga do bloco) classe O(n^2), ambas fieis a formula (F). `rede_total` (com a carga) e REPORTADA e so
rotulada (prevista O(n) para n<=31). AGGREGATED e SO sensibilidade rotulada: nunca decide, nem INCONCLUSIVO. V-C numerico e
inviavel (decisao do humano): so o teste ordinal fraco.
Uso: analisar-val.py <dir_brutos_va> [--vano <dir_brutos_vano>] [--vc <dir_brutos_vc>] --saida analise.json
     analisar-val.py --autoteste        (dado SINTETICO da formula em /tmp/opencode; confere a logica, nao o simulador)
"""
import csv
import glob
import gzip
import importlib.util
import json
import math
import subprocess
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
RAIZ = AQUI.parents[3]
spec = importlib.util.spec_from_file_location("formula", AQUI / "formula-analitica.py")
FORM = importlib.util.module_from_spec(spec)
spec.loader.exec_module(FORM)

TOL_REDE, TOL_NO = 0.001, 0.02
MIN_ALTURAS = 100          # minimo de alturas (V-A agregado) ou blocos na janela (V-A por no) por celula integra
LIMIAR_MATERIALIDADE = 0.10  # so ROTULA (nao decide): termo quadratico >= 10 % do valor ajustado em n maximo
CLASSE_PREVISTA = {  # derivada da FORMULA (codigo), antes do dado
    ("INDIVIDUAL", "rede_msgs"): "O(n)", ("AGGREGATED", "rede_msgs"): "O(n)",
    ("INDIVIDUAL", "rede_total"): "O(n)", ("INDIVIDUAL", "rede_consenso"): "O(n^2)",
    ("AGGREGATED", "rede_total"): "O(n)", ("AGGREGATED", "rede_consenso"): "O(n)",
    ("INDIVIDUAL", "no_lider"): "O(n^2)", ("INDIVIDUAL", "no_replica"): "O(1)",
    ("AGGREGATED", "no_lider"): "O(n)", ("AGGREGATED", "no_replica"): "O(1)"}
CLASSE_ADR = {"rede_msgs": "O(n)", "rede_total": "O(n)", "rede_consenso": "O(n)", "no_lider": "O(n)", "no_replica": "O(1)"}
# EMENDA 1b: criterio de aceite de V-A para P4a no modo INDIVIDUAL no bloco `va` (so estas duas medidas DECIDEM a classe)
CRITERIO_P4A_INDIVIDUAL = {"rede_msgs": "O(n)", "rede_consenso": "O(n^2)"}
FUNC = {"rede_msgs": FORM.rede_msgs, "rede_total": FORM.rede_total, "rede_consenso": FORM.rede_consenso, "no_lider": FORM.no_lider,
        "no_replica": FORM.no_replica}


def classe(b):
    # EMENDA 1b: 1,5 <= b < 2,5 e O(n^2); b >= 2,5 e ">O(n^2)" (antes todo b >= 1,5 era O(n^2)). Previsto: consenso b=1,856.
    return "O(1)" if b < 0.5 else ("O(n)" if b < 1.5 else ("O(n^2)" if b < 2.5 else ">O(n^2)"))


def ols_slope(ns, ys):
    x = [math.log(n) for n in ns]
    y = [math.log(v) for v in ys]
    mx, my = sum(x) / len(x), sum(y) / len(y)
    sxx = sum((a - mx) ** 2 for a in x)
    b = sum((a - mx) * (c - my) for a, c in zip(x, y)) / sxx
    ss_tot = sum((c - my) ** 2 for c in y)
    ss_res = sum((c - (my + b * (a - mx))) ** 2 for a, c in zip(x, y))
    return b, (1 - ss_res / ss_tot) if ss_tot > 0 else float("nan")


def linhas(path, opener=open):
    with opener(path, "rt", encoding="utf-8") as f:
        return [l for l in f if not l.startswith("#") and l.strip()]


def ler_celula_agregada(d):
    cfg = json.loads((d / "cfg.json").read_text())
    res = json.loads((d / "resultado.json").read_text())
    obs = json.loads((d / "obs" / "observador-resumo.json").read_text())
    out = {"id": cfg["id"], "n": cfg["n"], "modo": cfg["variante"]["signature_mode"], "semente": cfg["semente"],
           "rc_mvp": res["rc_mvp"], "rc_obs": res["rc_observador"],
           "md5_igual": res.get("md5_sem_hashcode_mvp") == res.get("md5_sem_hashcode_obs") and res.get("blocos_mvp", 0) > 0,
           "timeouts": obs["timeouts_disparados_total"], "visao_max": obs["visao_corrente_maxima"]}
    cb = d / "mvp-results-cb.csv"
    if cb.exists():
        rd = list(csv.DictReader(linhas(cb), delimiter=";"))
        if rd:
            out["rede_total"] = float(rd[0]["CbRede_avg_B"])
            out["rede_consenso"] = float(rd[0]["CbRedeConsenso_avg_B"])
            out["regime_heights"] = int(rd[0]["regime_heights"])
    # EMENDA 1b: pacotes por altura (observador ObservadorVal), media por altura sobre a MESMA janela de regime de CbRede_*
    pk = d / "obs" / "pacotes-por-altura.csv.gz"
    if pk.exists():
        with gzip.open(pk, "rt", encoding="utf-8") as fh:
            prs = list(csv.DictReader(l for l in fh if not l.startswith("#")))
        if prs:
            out["rede_msgs"] = sum(int(r["pacotes"]) for r in prs) / len(prs)
            out["pacotes_alturas"] = len(prs)
            # conferencia cruzada: bytes do observador == bytes do MVPComparison (mesma janela); diverge => nao integra
            out["bytes_obs_total"] = sum(int(r["bytes_total"]) for r in prs) / len(prs)
    out["integra"] = (out["rc_mvp"] == 0 and out["rc_obs"] == 0 and out["md5_igual"] and out["timeouts"] == 0
                      and "rede_total" in out and out["rede_total"] > 0 and out["rede_consenso"] > 0
                      and "rede_msgs" in out and out["rede_msgs"] > 0
                      and abs(out.get("bytes_obs_total", -1) - out.get("rede_total", -2)) < 0.01   # CSV imprime 3 casas
                      and out.get("pacotes_alturas") == out.get("regime_heights")
                      and out.get("regime_heights", 0) >= MIN_ALTURAS)
    return out


def ler_celula_no(d):
    cfg = json.loads((d / "cfg.json").read_text())
    res = json.loads((d / "resultado.json").read_text())
    obs = json.loads((d / "obs" / "observador-resumo.json").read_text())
    t0 = float(cfg["load_record_t0"])
    nos = sorted(glob.glob(str(d / "*-nos.csv")))
    corr = sorted(glob.glob(str(d / "*-corrida.csv")))
    out = {"id": cfg["id"], "n": cfg["n"], "modo": cfg["variante"]["signature_mode"], "semente": cfg["semente"],
           "rc_mvp": res["rc_mvp"], "rc_obs": res["rc_observador"], "timeouts": obs["timeouts_disparados_total"],
           "md5_igual": res.get("md5_sem_hashcode_mvp") == res.get("md5_sem_hashcode_obs") and res.get("blocos_mvp", 0) > 0}
    if not nos or not corr:
        out["integra"] = False
        out["falta"] = "arquivos -nos.csv/-corrida.csv ausentes"
        return out
    rows = list(csv.DictReader(linhas(nos[0]), delimiter=","))
    crow = list(csv.DictReader(linhas(corr[0]), delimiter=","))[0]
    t1 = float(crow["WindowEnd_s"])
    blocos = glob.glob(str(d / "hotstuff-*v-t0.csv.gz"))
    tempos = []
    if blocos:
        with gzip.open(blocos[0], "rt", encoding="utf-8") as f:
            for r in csv.DictReader(l for l in f if not l.startswith("#")):
                t = float(r["Time"])
                if t0 <= t <= t1:
                    tempos.append(t)
    nb = len(tempos)
    out["blocos_na_janela"] = nb
    out["janela"] = [t0, t1]
    recv = {int(r["NodeID"]): float(r["RecvBytes"]) for r in rows}
    if nb > 0 and len(recv) == cfg["n"]:
        out["no_lider"] = recv[0] / nb
        rep = [v / nb for k, v in recv.items() if k != 0]
        out["no_replica"] = sum(rep) / len(rep)
        out["no_replica_min_max"] = [min(rep), max(rep)]
    out["integra"] = (out["rc_mvp"] == 0 and out["rc_obs"] == 0 and out["md5_igual"] and out["timeouts"] == 0
                      and "no_lider" in out and out.get("blocos_na_janela", 0) >= MIN_ALTURAS)
    return out


def agrupar(cels, medidas, tol, formula_modo):
    por = {}
    for c in cels:
        por.setdefault(c["modo"], {}).setdefault(c["n"], []).append(c)
    resultado = {}
    for modo, pn in sorted(por.items()):
        resultado[modo] = {}
        for medida in medidas:
            pontos, det = [], {}
            for n, cs in sorted(pn.items()):
                ok = [c for c in cs if c["integra"]]
                if not ok:
                    det[str(n)] = {"celulas": len(cs), "integras": 0}
                    continue
                vals = [c[medida] for c in ok]
                m = sum(vals) / len(vals)
                form = FUNC[medida](n, modo)
                err = max(abs(v - form) / form for v in vals)
                det[str(n)] = {"celulas": len(cs), "integras": len(ok), "media": m, "min": min(vals), "max": max(vals),
                               "formula": form, "erro_rel_max": err, "fiel": err <= tol}
                pontos.append((n, m))
            r = {"por_n": det}
            if len({p[0] for p in pontos}) >= 4:
                b, r2 = ols_slope([p[0] for p in pontos], [p[1] for p in pontos])
                r.update({"expoente_b": b, "R2_loglog": r2, "classe_medida": classe(b),
                          "classe_prevista_pelo_codigo": CLASSE_PREVISTA[(modo, medida)],
                          "classe_esperada_ADR": CLASSE_ADR[medida],
                          "bate_com_previsao_do_codigo": classe(b) == CLASSE_PREVISTA[(modo, medida)],
                          "bate_com_ADR": classe(b) == CLASSE_ADR[medida],
                          "todos_fieis_a_formula": all(v.get("fiel") for v in det.values() if "fiel" in v),
                          "pontos_sem_celula_integra": [n for n, v in det.items() if v["integras"] == 0]})
            else:
                r["status"] = "DADOS INSUFICIENTES (< 4 valores de n com celula integra)"
            resultado[modo][medida] = r
    return resultado


def _agg(cid):
    return "_aggregated_" in cid


def veredito_va(bloco, integr, res):
    """Veredito PRE-REGISTRADO (PREREGISTRO.md, 3.2 e Emenda 1b de 16/11/2026). Nao e aprovacao; o loop 3 (humano) decide.
    DECIDE so o modo INDIVIDUAL (decisao 1 do humano). AGGREGATED e SO sensibilidade rotulada: nunca da INCONCLUSIVO nem INCONSISTENTE.
    INCONCLUSIVO (instrumento): celula INDIVIDUAL faltante, extra, nao integra ou com timeout; ou n sem celula INDIVIDUAL integra.
    INCONSISTENTE (bloco va, INDIVIDUAL): rede_msgs com classe != O(n) ou rede_consenso com classe != O(n^2) (CRITERIO_P4A_INDIVIDUAL),
                   ou qualquer ponto dessas duas infiel a formula (|medido-formula|/formula > 0,001).
    CONSISTENTE: nenhum dos anteriores. rede_total (com a carga) e REPORTADA e so rotulada.
    Bloco vano (por no, secundario): INDIVIDUAL decide so a classe prevista pelo codigo; infidelidade por no so rotula."""
    def so_ind(lista):
        return [c for c in lista if not _agg(c)]
    ind = {k: so_ind(integr[k]) for k in ("faltando", "extras", "nao_integras", "com_timeout")}
    rotulos = []
    for k, v in integr.items():
        if k in ("faltando", "extras", "nao_integras", "com_timeout") and [c for c in v if _agg(c)]:
            rotulos.append(f"SENSIBILIDADE AGGREGATED: {k} = {[c for c in v if _agg(c)]} (nao decide)")
    if any(ind.values()):
        return {"veredito": "INCONCLUSIVO (instrumento)", "motivo": {k: v for k, v in ind.items() if v},
                "rotulos_nao_decisorios": rotulos}
    motivos = []
    for modo, meds in res.items():
        for medida, r in meds.items():
            if "expoente_b" not in r:
                if modo == "INDIVIDUAL":
                    return {"veredito": "INCONCLUSIVO (instrumento)", "motivo": f"{modo}/{medida}: {r.get('status')}",
                            "rotulos_nao_decisorios": rotulos}
                rotulos.append(f"SENSIBILIDADE {modo}/{medida}: {r.get('status')}")
                continue
            if r["pontos_sem_celula_integra"]:
                if modo == "INDIVIDUAL":
                    return {"veredito": "INCONCLUSIVO (instrumento)", "motivo": f"{modo}/{medida}: n sem celula integra",
                            "rotulos_nao_decisorios": rotulos}
                rotulos.append(f"SENSIBILIDADE {modo}/{medida}: n sem celula integra")
                continue
            if modo != "INDIVIDUAL":
                rotulos.append(f"SENSIBILIDADE {modo}/{medida}: classe {r['classe_medida']} (prevista {r['classe_prevista_pelo_codigo']}), "
                               f"fiel_a_formula={r['todos_fieis_a_formula']}, b={r['expoente_b']:.3f}; nao decide")
                continue
            if bloco == "va":
                if medida in CRITERIO_P4A_INDIVIDUAL:   # rede_msgs e rede_consenso DECIDEM
                    if r["classe_medida"] != CRITERIO_P4A_INDIVIDUAL[medida]:
                        motivos.append(f"INDIVIDUAL/{medida}: classe {r['classe_medida']} != criterio {CRITERIO_P4A_INDIVIDUAL[medida]}")
                    if not r["todos_fieis_a_formula"]:
                        motivos.append(f"INDIVIDUAL/{medida}: ponto infiel a formula do codigo")
                else:                                    # rede_total: so rotulo
                    rotulos.append(f"INDIVIDUAL/{medida} (so rotulo): classe {r['classe_medida']} (prevista {r['classe_prevista_pelo_codigo']}), "
                                   f"fiel_a_formula={r['todos_fieis_a_formula']}, b={r['expoente_b']:.3f}")
            else:   # vano
                if not r["todos_fieis_a_formula"]:
                    rotulos.append(f"{modo}/{medida}: infiel a formula POR NO (formula nao validada; pode ser erro da formula)")
                if not r["bate_com_previsao_do_codigo"]:
                    motivos.append(f"{modo}/{medida}: classe {r['classe_medida']} != prevista {r['classe_prevista_pelo_codigo']}")
    return {"veredito": "INCONSISTENTE" if motivos else "CONSISTENTE", "motivos": motivos, "rotulos_nao_decisorios": rotulos}


def ordem_vc(por_L_n):
    """V-C ordinal (PREREGISTRO.md 3.4): em cada L, n=16 tem Tb MAIOR e vazao MENOR que n=4."""
    out = {}
    for L in sorted({k.split("_")[0] for k in por_L_n}):
        a, b = por_L_n.get(f"{L}_n4"), por_L_n.get(f"{L}_n16")
        if not a or not b:
            out[L] = {"ordem_ok": None, "status": "INCONCLUSIVO (falta n=4 ou n=16 integro)"}
            continue
        ok = b["Tb_avg_s"] > a["Tb_avg_s"] and b["vazao_tx_por_s_media"] < a["vazao_tx_por_s_media"]
        out[L] = {"ordem_ok": ok, "Tb_4": a["Tb_avg_s"], "Tb_16": b["Tb_avg_s"], "vazao_4": a["vazao_tx_por_s_media"],
                  "vazao_16": b["vazao_tx_por_s_media"]}
    return out


def materialidade_quadratica(cels, medida, modo):
    """Fracao do valor ajustado em n maximo que vem do termo a2*n^2 (ajuste quadratico de validar-complexidade.py)."""
    spec2 = importlib.util.spec_from_file_location("vc", RAIZ / "scripts/jabs/validar-complexidade.py")
    vc = importlib.util.module_from_spec(spec2)
    spec2.loader.exec_module(vc)
    pts = [(c["n"], c[medida]) for c in cels if c["modo"] == modo and c["integra"]]
    if len({p[0] for p in pts}) < 4:
        return None
    xs, ys = [float(p[0]) for p in pts], [float(p[1]) for p in pts]
    q = vc.ajustar(xs, ys, 2)
    if not q:
        return None
    z = max(xs) / q["xm"]
    termo = q["coef"][2] * z ** 2
    total = sum(q["coef"][i] * z ** i for i in range(3))
    return {"fracao_termo_quadratico_em_n_max": termo / total if total else None,
            "material": (termo / total) >= LIMIAR_MATERIALIDADE if total else None}


def rodar_script_complexidade(cels, medida, modo, esperado, tmp):
    p = Path(tmp) / f"vc_{modo}_{medida}.csv"
    with open(p, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["n", "bytes_trafegados"])
        for c in cels:
            if c["modo"] == modo and c["integra"]:
                w.writerow([c["n"], c[medida]])
    r = subprocess.run([sys.executable, str(RAIZ / "scripts/jabs/validar-complexidade.py"), str(p), "--esperado", esperado,
                        "--protocolo", f"HOTSTUFF/{modo}/{medida}"], capture_output=True, text=True)
    return {"rc": r.returncode, "saida": r.stdout.strip().splitlines()}


def ler_tb(d):
    """V-C: Tb medio (latencia de finalizacao desde a criacao do bloco), do mvp-results.csv e do CSV por bloco."""
    cfg = json.loads((d / "cfg.json").read_text())
    res = json.loads((d / "resultado.json").read_text())
    obs = json.loads((d / "obs" / "observador-resumo.json").read_text())
    T0_TRANSIENTE = 20.0   # PRE-REGISTRO 3.4: descarta blocos finalizados antes de 20 s simulados
    out = {"id": cfg["id"], "n": cfg["n"], "L": cfg["variante"]["L"], "modo": cfg["variante"]["signature_mode"],
           "rc_mvp": res["rc_mvp"], "rc_obs": res["rc_observador"], "timeouts": obs["timeouts_disparados_total"],
           "md5_igual": res.get("md5_sem_hashcode_mvp") == res.get("md5_sem_hashcode_obs") and res.get("blocos_mvp", 0) > 0}
    gzs = glob.glob(str(d / "hotstuff-*v-t0.csv.gz"))
    if gzs:
        tb, nb = [], 0
        with gzip.open(gzs[0], "rt", encoding="utf-8") as f:
            for r in csv.DictReader(l for l in f if not l.startswith("#")):
                if float(r["Time"]) >= T0_TRANSIENTE:
                    tb.append(float(r["BlockFinalizationTime"]))   # Tb do PRIMEIRO no a finalizar (granularidade ADR-0006)
        if tb:
            dur = cfg["duracao_s"] - T0_TRANSIENTE
            out["Tb_avg_s"] = sum(tb) / len(tb)
            out["blocos"] = len(tb)
            out["vazao_blocos_por_s"] = len(tb) / dur
            out["vazao_tx_por_s"] = out["vazao_blocos_por_s"] * cfg["variante"]["L"]
    out["integra"] = out["rc_mvp"] == 0 and out["rc_obs"] == 0 and out["md5_igual"] and out["timeouts"] == 0 and "Tb_avg_s" in out
    return out


def analisar_vc(dir_vc, saida):
    cels = [ler_tb(Path(d).parent) for d in sorted(glob.glob(f"{dir_vc}/*/cfg.json"))]
    esp = json.loads((AQUI.parent / "celulas-esperadas.json").read_text())
    esperadas = {c["id"] for c in esp["celulas"]["vc"]}
    obtidas = {c["id"] for c in cels}
    por = {}
    for c in cels:
        if c["integra"]:
            por.setdefault((c["L"], c["n"]), []).append(c)
    out = {"nota": "so fatos medidos (L=100); V-C numerico inviavel (decisao do humano 16/11/2026); ver FONTES-V-C.md", "por_L_n": {},
           "integridade": {"esperadas": len(esperadas), "obtidas": len(obtidas), "faltando": sorted(esperadas - obtidas),
                           "extras": sorted(obtidas - esperadas), "nao_integras": [c["id"] for c in cels if not c["integra"]],
                           "com_timeout": [c["id"] for c in cels if c["timeouts"] > 0]}}
    for (L, n), cs in sorted(por.items()):
        tb = [c["Tb_avg_s"] for c in cs]
        vz = [c["vazao_tx_por_s"] for c in cs]
        out["por_L_n"][f"L{L}_n{n}"] = {"celulas_integras": len(cs), "Tb_avg_s": sum(tb) / len(tb), "Tb_min": min(tb),
                                        "Tb_max": max(tb), "vazao_tx_por_s_media": sum(vz) / len(vz)}
    out["ordem_n4_n16"] = ordem_vc(out["por_L_n"])
    out["veredito_numerico"] = ("INVIAVEL (declarado pelo humano em 16/11/2026): nenhum numero VERIFICADO de Yin et al. 2019; "
                                "o teste ordinal abaixo e REGISTRO QUALITATIVO e NAO e confronto numerico")
    out["rotulo"] = "TESTE ORDINAL FRACO; NAO e confronto numerico com o publicado; nao valida magnitude"
    Path(saida).write_text(json.dumps(out, indent=1))
    return out


def analisar(dir_va, dir_no, saida):
    import tempfile
    esp = json.loads((AQUI.parent / "celulas-esperadas.json").read_text())
    out = {"integridade": {}}
    with tempfile.TemporaryDirectory() as tmp:
        if dir_va:
            cels = [ler_celula_agregada(Path(d).parent) for d in sorted(glob.glob(f"{dir_va}/*/cfg.json"))]
            esperadas = {c["id"] for c in esp["celulas"]["va"]}
            obtidas = {c["id"] for c in cels}
            out["integridade"]["va"] = {"esperadas": len(esperadas), "obtidas": len(obtidas),
                                        "faltando": sorted(esperadas - obtidas), "extras": sorted(obtidas - esperadas),
                                        "nao_integras": [c["id"] for c in cels if not c["integra"]],
                                        "com_timeout": [c["id"] for c in cels if c["timeouts"] > 0]}
            out["va"] = agrupar(cels, ["rede_msgs", "rede_total", "rede_consenso"], TOL_REDE, None)
            out["va_veredito_preregistrado"] = veredito_va("va", out["integridade"]["va"], out["va"])
            out["va_materialidade_quadratica"] = {f"{modo}/{m}": materialidade_quadratica(cels, m, modo)
                                                  for modo in ("INDIVIDUAL", "AGGREGATED") for m in ("rede_msgs", "rede_total", "rede_consenso")}
            # Regra S pre-registrada (PREREGISTRO 3.3): "linear" e ACEITO se o script concorda (rc 0) OU o termo quadratico
            # NAO e material (< LIMIAR_MATERIALIDADE do valor ajustado em n maximo); "quadratico" e aceito se rc 0 e material.
            out["va_regra_S_aceita"] = {}
            for modo in ("INDIVIDUAL", "AGGREGATED"):
                for m in ("rede_msgs", "rede_total", "rede_consenso"):
                    mat = out["va_materialidade_quadratica"][f"{modo}/{m}"]
                    lin = rodar_script_complexidade(cels, m, modo, "linear", tmp)["rc"] == 0
                    qua = rodar_script_complexidade(cels, m, modo, "quadratico", tmp)["rc"] == 0
                    out["va_regra_S_aceita"][f"{modo}/{m}"] = {
                        "linear_aceito": bool(lin or (mat and mat["material"] is False)),
                        "quadratico_aceito": bool(qua and mat and mat["material"])}
            out["va_script_validar_complexidade_NAO_DECIDE"] = {
                f"{modo}/{m}/{e}": rodar_script_complexidade(cels, m, modo, e, tmp)
                for modo in ("INDIVIDUAL", "AGGREGATED") for m in ("rede_msgs", "rede_total", "rede_consenso")
                for e in ("linear", "quadratico")}
        if dir_no:
            cels = [ler_celula_no(Path(d).parent) for d in sorted(glob.glob(f"{dir_no}/*/cfg.json"))]
            esperadas = {c["id"] for c in esp["celulas"]["vano"]}
            obtidas = {c["id"] for c in cels}
            out["integridade"]["vano"] = {"esperadas": len(esperadas), "obtidas": len(obtidas),
                                          "faltando": sorted(esperadas - obtidas), "extras": sorted(obtidas - esperadas),
                                          "nao_integras": [c["id"] for c in cels if not c["integra"]],
                                          "com_timeout": [c["id"] for c in cels if c["timeouts"] > 0]}
            out["vano"] = agrupar(cels, ["no_lider", "no_replica"], TOL_NO, None)
            out["vano_veredito_preregistrado"] = veredito_va("vano", out["integridade"]["vano"], out["vano"])
    Path(saida).write_text(json.dumps(out, indent=1, ensure_ascii=False))
    return out


def autoteste():
    """Dado SINTETICO (formula) em /tmp/opencode/val-autoteste: confere a logica do analisador, NAO o simulador."""
    base = Path("/tmp/opencode/val-autoteste")
    if base.exists():
        for p in sorted(base.rglob("*"), reverse=True):
            p.unlink() if p.is_file() else p.rmdir()
    esp = json.loads((AQUI.parent / "celulas-esperadas.json").read_text())
    for c in esp["celulas"]["va"]:
        d = base / "va" / c["id"]
        (d / "obs").mkdir(parents=True)
        (d / "cfg.json").write_text(json.dumps({"id": c["id"], "n": c["n"], "semente": c["semente"],
                                                "variante": {"signature_mode": c["modo"]}}))
        (d / "resultado.json").write_text(json.dumps({"rc_mvp": 0, "rc_observador": 0, "md5_sem_hashcode_mvp": "x",
                                                      "md5_sem_hashcode_obs": "x", "blocos_mvp": 10}))
        (d / "obs" / "observador-resumo.json").write_text(json.dumps({"timeouts_disparados_total": 0, "visao_corrente_maxima": 9}))
        with gzip.open(d / "obs" / "pacotes-por-altura.csv.gz", "wt") as fh:   # EMENDA 1b: dado SINTETICO da formula
            fh.write("# sintetico\nheight,pacotes,bytes_total,bytes_consenso\n")
            for h in range(2, 102):
                fh.write(f"{h},{FORM.rede_msgs(c['n'])},{FORM.rede_total(c['n'], c['modo'])},{FORM.rede_consenso(c['n'], c['modo'])}\n")
        (d / "mvp-results-cb.csv").write_text("# x\nproject;n;trials;regime_heights;CbRede_avg_B;CbRedeConsenso_avg_B\n"
                                              f"HOTSTUFF;{c['n']};1;100;{FORM.rede_total(c['n'], c['modo'])};{FORM.rede_consenso(c['n'], c['modo'])}\n")
    out = analisar(base / "va", None, base / "analise-va.json")
    for modo in out["va"]:
        for m, r in out["va"][modo].items():
            print(f"[sintetico] {modo:10s} {m:14s} b={r['expoente_b']:.3f} classe={r['classe_medida']:7s} "
                  f"prevista={r['classe_prevista_pelo_codigo']:7s} bate_codigo={r['bate_com_previsao_do_codigo']} "
                  f"bate_ADR={r['bate_com_ADR']} fiel={r['todos_fieis_a_formula']}")
    # perturbacao: +1 % em uma celula tem de ser detectada como infiel
    d = base / "va" / esp["celulas"]["va"][0]["id"]
    cbp = d / "mvp-results-cb.csv"
    txt = cbp.read_text().splitlines()
    campos = txt[-1].split(";")
    campos[5] = str(float(campos[5]) * 1.01)   # rede_consenso (DECIDE, EMENDA 1b); campos[4]=rede_total e conferida contra o observador
    cbp.write_text("\n".join(txt[:-1] + [";".join(campos)]) + "\n")
    out2 = analisar(base / "va", None, base / "analise-va-pert.json")
    n0 = esp["celulas"]["va"][0]["n"]
    m0 = esp["celulas"]["va"][0]["modo"]
    print("[sintetico] veredito va:", out["va_veredito_preregistrado"]["veredito"], out["va_veredito_preregistrado"].get("rotulos_nao_decisorios"))
    print("[sintetico] perturbacao +1% detectada:", not out2["va"][m0]["rede_consenso"]["por_n"][str(n0)]["fiel"])

    # --- caminho POR NO (vano) com dado sintetico da formula: 100 blocos na janela [20, 100] ---
    import gzip as _gz
    for c in esp["celulas"]["vano"]:
        d = base / "vano" / c["id"]
        (d / "obs").mkdir(parents=True)
        (d / "cfg.json").write_text(json.dumps({"id": c["id"], "n": c["n"], "semente": c["semente"], "load_record_t0": 20.0,
                                                "variante": {"signature_mode": c["modo"]}}))
        (d / "resultado.json").write_text(json.dumps({"rc_mvp": 0, "rc_observador": 0, "md5_sem_hashcode_mvp": "x",
                                                      "md5_sem_hashcode_obs": "x", "blocos_mvp": 10}))
        (d / "obs" / "observador-resumo.json").write_text(json.dumps({"timeouts_disparados_total": 0, "visao_corrente_maxima": 9}))
        nb = 100
        with _gz.open(d / f"hotstuff-{c['n']}v-t0.csv.gz", "wt") as fh:
            fh.write("Time,NodeID,BlockHeight\n")
            for i in range(nb):
                fh.write(f"{21 + i * 0.79},1,{i + 1}\n")
        rows = ["NodeID,SentBytes,RecvBytes,BusyTime_s,U_i", f"0,0,{FORM.no_lider(c['n'], c['modo']) * nb},0,0"]
        rows += [f"{k},0,{FORM.no_replica(c['n'], c['modo']) * nb},0,0" for k in range(1, c["n"])]
        (d / "hotstuff-x-nos.csv").write_text("# c\n" + "\n".join(rows) + "\n")
        (d / "hotstuff-x-corrida.csv").write_text("# c\nSeed,WindowStart_s,WindowEnd_s\n1,20,100\n")
    o3 = analisar(None, base / "vano", base / "analise-vano.json")
    # --- caminho V-C com dado sintetico: Tb cresce e vazao cai de n=4 para n=16 ---
    for c in esp["celulas"]["vc"]:
        d = base / "vc" / c["id"]
        (d / "obs").mkdir(parents=True)
        (d / "cfg.json").write_text(json.dumps({"id": c["id"], "n": c["n"], "semente": c["semente"], "duracao_s": 120,
                                                "variante": {"signature_mode": "INDIVIDUAL", "L": c["L"]}}))
        (d / "resultado.json").write_text(json.dumps({"rc_mvp": 0, "rc_observador": 0, "md5_sem_hashcode_mvp": "x",
                                                      "md5_sem_hashcode_obs": "x", "blocos_mvp": 10}))
        (d / "obs" / "observador-resumo.json").write_text(json.dumps({"timeouts_disparados_total": 0, "visao_corrente_maxima": 9}))
        passo = 0.15 if c["n"] == 4 else 0.20
        with _gz.open(d / f"hotstuff-{c['n']}v-t0.csv.gz", "wt") as fh:
            fh.write("Time,NodeID,BlockHeight,BlockFinalizationTime\n")
            t = 1.0
            h = 0
            while t < 120:
                h += 1
                fh.write(f"{t},1,{h},{passo * 0.9}\n")
                t += passo
    ovc = analisar_vc(base / "vc", base / "analise-vc.json")
    print("[sintetico] vc ordem:", {L: v["ordem_ok"] for L, v in ovc["ordem_n4_n16"].items()}, "integridade:", ovc["integridade"]["obtidas"], "/", ovc["integridade"]["esperadas"])
    print("[sintetico] veredito vano:", o3["vano_veredito_preregistrado"]["veredito"])
    print("[sintetico] veredito va com perturbacao:", out2["va_veredito_preregistrado"]["veredito"], out2["va_veredito_preregistrado"].get("motivos"))
    print("[sintetico] ordem_vc ok  :", ordem_vc({"L100_n4": {"Tb_avg_s": .13, "vazao_tx_por_s_media": 900}, "L100_n16": {"Tb_avg_s": .17, "vazao_tx_por_s_media": 700}}))
    print("[sintetico] ordem_vc nok :", ordem_vc({"L100_n4": {"Tb_avg_s": .13, "vazao_tx_por_s_media": 900}, "L100_n16": {"Tb_avg_s": .12, "vazao_tx_por_s_media": 950}}))
    for modo in o3["vano"]:
        for m, r in o3["vano"][modo].items():
            print(f"[sintetico] vano {modo:10s} {m:10s} b={r['expoente_b']:.3f} classe={r['classe_medida']:7s} "
                  f"prevista={r['classe_prevista_pelo_codigo']:7s} bate_codigo={r['bate_com_previsao_do_codigo']} fiel={r['todos_fieis_a_formula']}")


def main():
    a = sys.argv[1:]
    if a[:1] == ["--autoteste"]:
        return autoteste()
    if "--saida" not in a or not a or a[0].startswith("--"):
        sys.exit(__doc__)
    dir_va = a[0]
    dir_no = a[a.index("--vano") + 1] if "--vano" in a else None
    analisar(dir_va, dir_no, a[a.index("--saida") + 1])
    if "--vc" in a:   # V-C: Tb e vazao em n=4/16 (dirs de celulas va e vc); so fatos
        analisar_vc(a[a.index("--vc") + 1], a[a.index("--saida") + 1].replace(".json", "-vc.json"))
    print("ok ->", a[a.index("--saida") + 1])


if __name__ == "__main__":
    main()

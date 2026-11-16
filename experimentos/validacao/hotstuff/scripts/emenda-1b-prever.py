#!/usr/bin/env python3
"""EMENDA 1b (16/11/2026), ANTES DE QUALQUER DADO. Calcula, so a partir da FORMULA (derivada do codigo; nao ha dado desta
rodada), a classe esperada de cada metrica de V-A para o modo INDIVIDUAL (que responde por P4a) e para AGGREGATED (so
sensibilidade rotulada). Nao simula. Grava emenda-1b-previsoes.json.

Metricas (granularidade: AGREGADO DE REDE por bloco decidido, janela de regime):
  rede_msgs      pacotes entregues pela rede por bloco (contagem de mensagens; proxy de autenticadores NO SENTIDO DO ARTIGO,
                 em que um QC conta como UM autenticador). Formula: 8(n-1) = PREPARE + PRE_COMMIT + COMMIT + DECIDE (4(n-1)),
                 3 rodadas de votos (3(n-1)) e NEW_VIEW com QC ((n-1)).
  rede_consenso  bytes agregados SEM a carga do bloco
  rede_total     bytes agregados COM a carga do bloco ((n-1)*P)
Regra de classe (ponto medio): b<0,5 O(1); 0,5<=b<1,5 O(n); 1,5<=b<2,5 O(n^2); b>=2,5 acima de O(n^2).
"""
import importlib.util
import json
from pathlib import Path

AQUI = Path(__file__).resolve().parent
NS = [4, 7, 10, 16, 31]


def carregar(nome, arq):
    spec = importlib.util.spec_from_file_location(nome, AQUI / arq)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


FORM = carregar("formula", "formula-analitica.py")
AN = carregar("analisar", "analisar-val.py")
FUNC = {"rede_msgs": FORM.rede_msgs, "rede_assinaturas": FORM.rede_assinaturas, "rede_consenso": FORM.rede_consenso,
        "rede_total": FORM.rede_total}

out = {"nota": "calculado da FORMULA (leitura de codigo); nenhum dado desta rodada", "ns": NS, "previsao": {}}
for modo in ("INDIVIDUAL", "AGGREGATED"):
    for medida, fn in FUNC.items():
        ys = [fn(n, modo) for n in NS]
        b, r2 = AN.ols_slope(NS, ys)
        cels = [{"n": n, "modo": modo, "integra": True, medida: fn(n, modo)} for n in NS]
        mat = AN.materialidade_quadratica(cels, medida, modo)
        # razao entre o crescimento observado e o linear puro: y(31)/y(4) contra 31/4 e (31/4)^2
        out["previsao"][f"{modo}/{medida}"] = {
            "valores_por_n": dict(zip(map(str, NS), ys)), "expoente_b": round(b, 4), "R2_loglog": round(r2, 5),
            "classe": AN.classe(b), "razao_y31_y4": round(ys[-1] / ys[0], 3), "razao_n31_n4": round(31 / 4, 3),
            "razao_n31_n4_ao_quadrado": round((31 / 4) ** 2, 3),
            "fracao_termo_quadratico_em_n31": None if not mat else round(mat["fracao_termo_quadratico_em_n_max"], 4)}
Path(AQUI.parent / "emenda-1b-previsoes.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))
for k, v in out["previsao"].items():
    print(f"{k:28s} b={v['expoente_b']:.4f} classe={v['classe']:7s} y31/y4={v['razao_y31_y4']:8.3f} "
          f"termo_quad_em_n31={v['fracao_termo_quadratico_em_n31']}")
print("n^1 daria", out["previsao"]["INDIVIDUAL/rede_msgs"]["razao_n31_n4"], "e n^2 daria",
      out["previsao"]["INDIVIDUAL/rede_msgs"]["razao_n31_n4_ao_quadrado"])

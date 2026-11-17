#!/usr/bin/env python3
"""V-B do Tendermint (RASCUNHO, pre-registrado ANTES dos dados): classifica cada celula e calcula o veredito POR n. SO LEITURA; nao simula.
Adaptado de experimentos/validacao/hotstuff/scripts/analisar-vb.py (E7). DIFERENCA ESTRUTURAL: em V-B do Tendermint so existe a JVM do
ObservadorTm (o MVPComparison nao le --attack para TENDERMINT); logo NAO ha conferencia cruzada de CSV por bloco entre JVMs nem a linha
`failed: ... finalizou 0 blocos` do MVPComparison. Os criterios mecanicos abaixo usam so rc_observador, a pilha de stderr-observador.log,
observador-resumo.json e observador-por-altura.csv.gz.

Classes por celula (mecanicas, nesta ordem):
  NAO_EXECUTAVEL_POR_REGRA_DO_ATAQUE  stderr-observador.log (pilha) ou stdout-observador.log tem `EQUIVOCATION em <FASE> (no <id>) exige ao menos
        (2 vizinhos | 1 destinatario honesto) ... Ataque nao aplicado: simulacao abortada em vez de degradar` (Tendermint.requireEquivocationSplit,
        IllegalStateException; FASE em PROPOSAL|PREVOTE|PRECOMMIT), E rc_observador != 0, E obs/observador-resumo.json AUSENTE (o resumo so e escrito
        depois de scenario.run()). Nao e ruptura, nao e sucesso, nao e substituida, nao e reexecutada.
  VIVA                 rc_observador == 0, resumo presente, e existe altura com Fmin_s >= 20 s (T_VIVA_S) em observador-por-altura.csv.gz.
  SEM_VIVACIDADE       rc_observador == 0, resumo presente e NENHUMA altura com Fmin_s >= 20 s. Sub-rotulos: SEM_BLOCO (finalizacoes_locais_total == 0)
        | PARADA_ANTES_DE_T0.
  OUTRA_FALHA          qualquer outra coisa (celula faltante; rc_observador != 0 sem a mensagem da regra, p.ex. falha do logger com 0 blocos; mensagem da
        regra mas o observador terminou; outra IllegalStateException de EQUIVOCATION). Relatada; NAO conta como ruptura nem como sucesso; o ponto
        (n,f) fica INCONCLUSIVO. NAO ha parte do HotStuff "MVPComparison falhou com `finalizou 0 blocos`": zero blocos vira OUTRA_FALHA se rc != 0.
MIN_EXEC = 6 celulas executaveis (VIVA + SEM_VIVACIDADE) por ponto (decisao do humano, vigente para o HotStuff; PROPOSTA manter).
Veredito: ver `veredito_ponto` e `veredito_n`. Criterio inteiro (decisao 16/11): f* = floor((n-1)/3)+1; L(f)=1 dentro da faixa e L(f*)=0.
Uso:  analisar-vb-tm.py <dados-brutos/vb> [--saida analise-vb.json]   |   analisar-vb-tm.py --autoteste (dado SINTETICO)
"""
import csv
import gzip
import json
import re
import sys
import tempfile
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
T_VIVA_S = 20.0
MIN_EXEC = 6
RE_REGRA = re.compile(r"EQUIVOCATION em (PROPOSAL|PREVOTE|PRECOMMIT) \(no (\d+)\) exige ao menos (2 vizinhos|1 destinatario honesto)"
                      r".*Ataque nao aplicado: simulacao abortada em vez de degradar", re.S)
RE_OUTRA_EQ = re.compile(r"EQUIVOCATION")
NAO_EXEC = "NAO_EXECUTAVEL_POR_REGRA_DO_ATAQUE"


def fmax(n):
    return (n - 1) // 3


def texto(p):
    return p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""


def alturas(p):
    with gzip.open(p, "rt", encoding="utf-8") as f:
        return [(int(r["height"]), float(r["Fmin_s"]), int(r["n_finalizaram"])) for r in csv.DictReader(f)]


def classificar(d):
    out = {"classe": "OUTRA_FALHA", "sub": None, "motivo": None}
    rj = d / "resultado.json"
    if not rj.exists():
        out["motivo"] = "FALTANTE: sem resultado.json"
        return out
    r = json.loads(rj.read_text())
    out["rc_observador"] = r.get("rc_observador")
    log = texto(d / "stderr-observador.log") + "\n" + texto(d / "stdout-observador.log")
    resumo_p, altura_p = d / "obs" / "observador-resumo.json", d / "obs" / "observador-por-altura.csv.gz"
    m = RE_REGRA.search(log)
    if m:
        out["fase"], out["no_que_violou"] = m.group(1), int(m.group(2))
        if r["rc_observador"] != 0 and not resumo_p.exists():
            out["classe"] = NAO_EXEC
        else:
            out["motivo"] = "mensagem da regra no log, mas o observador terminou (rc==0 ou resumo presente): incoerente"
        return out
    if r["rc_observador"] != 0 or not resumo_p.exists() or not altura_p.exists():
        out["motivo"] = ("observador sem resultado (rc != 0 ou sem resumo/por-altura) e sem a mensagem da regra"
                         + ("; ha outra mensagem de EQUIVOCATION no log" if RE_OUTRA_EQ.search(log) else ""))
        return out
    resumo = json.loads(resumo_p.read_text())
    total = resumo["finalizacoes_locais_total"]
    alts = alturas(altura_p)
    apos = [(h, t, nf) for h, t, nf in alts if t >= T_VIVA_S]
    out.update(finalizacoes_locais_total=total, byzantine_ids_observador=resumo.get("byzantine_ids"),
               altura_maxima_finalizada=resumo.get("altura_maxima_finalizada"), alturas_com_Fmin_ge_20s=len(apos),      # rotulo (nao decide)
               max_replicas_que_finalizaram_apos_20s=max((nf for _, _, nf in apos), default=0),                          # rotulo (nao decide)
               ultimo_Fmin_s=max((t for _, t, _ in alts), default=None), round_final_maximo=resumo.get("round_final_maximo"),
               equivocacoes_observadas_total=resumo.get("equivocacoes_observadas_total"),
               timeouts_disparados_total=resumo.get("timeouts_disparados_total"))                                         # rotulos
    if total == 0:
        out["classe"], out["sub"] = "SEM_VIVACIDADE", "SEM_BLOCO"
        return out
    out["classe"] = "VIVA" if apos else "SEM_VIVACIDADE"
    out["sub"] = None if apos else "PARADA_ANTES_DE_T0"
    return out


def veredito_ponto(c, min_exec=MIN_EXEC):
    k = c["viva"] + c["sem"]
    if c["outra"] > 0:
        return {"k_exec": k, "L": None, "estado": "INCONCLUSIVO", "porque": f"{c['outra']} OUTRA_FALHA/faltante(s) no ponto"}
    if k < min_exec:
        return {"k_exec": k, "L": None, "estado": "INCONCLUSIVO", "porque": f"k_exec={k} < minimo {min_exec}"}
    sup = round(1 - 0.05 ** (1 / k), 3) if c["viva"] == 0 else None    # rotulo (NAO decide): limite superior 95 % de P(viva) com L=0
    return {"k_exec": k, "L": c["viva"] / k, "estado": "DECIDIDO", "porque": None, "rotulo_L_sup95_se_L0": sup}


def veredito_n(n, pontos):
    fm = fmax(n)
    viol, inconc, misto = [], [], []
    for f in range(0, fm + 2):
        p = pontos.get(f)
        if f <= fm and p is not None and p["sem"] > 0:
            viol.append((f, "perda de vivacidade dentro da tolerancia (L<1)"))      # UMA celula basta (contraexemplo, sem minimo)
            continue
        if p is None or p["estado"] != "DECIDIDO":
            inconc.append(f)
            continue
        if f == fm + 1:
            if p["L"] == 1:
                viol.append((f, "sem ruptura em f* (L=1)"))
            elif p["L"] > 0:
                misto.append(f)
    if viol:
        return "INCONSISTENTE", {"violacoes": viol, "pontos_inconclusivos": inconc}
    if inconc:
        return "INCONCLUSIVO", {"pontos_inconclusivos": inconc, "pontos_mistos": misto}
    if misto:
        return "MISTO", {"pontos_mistos": misto}
    return "CONSISTENTE", {}


def veredito_global(por_n):
    v = list(por_n.values())
    if all(x == "CONSISTENTE" for x in v):
        return "CONSISTENTE"
    for r in ("INCONSISTENTE", "INCONCLUSIVO", "MISTO"):
        if r in v:
            dec = {n: x for n, x in por_n.items() if x in ("CONSISTENTE", "INCONSISTENTE", "MISTO")}
            return r if r == "INCONSISTENTE" else f"{r} (n decididos: {dec})"
    return "?"


def analisar(base, saida=None):
    esp = json.loads((VAL / "celulas-esperadas.json").read_text())["celulas"]["vb"]
    prev = json.loads((VAL / "vb-celulas-abortam-previsto.json").read_text())
    previstos = {c["id"] for c in prev["abortam_previsto"]}
    cel, nexec = {}, []
    for c in esp:
        k = classificar(Path(base) / c["id"])
        k.update({"id": c["id"], "n": c["n"], "f": c["f"], "semente": c["semente"], "fora_da_faixa": c["fora_da_faixa"]})
        cel[c["id"]] = k
        if k["classe"] == NAO_EXEC:
            nexec.append({"id": c["id"], "semente": c["semente"], "fase": k.get("fase"), "no_que_violou": k.get("no_que_violou")})
    ct, pontos, vn = {}, {}, {}
    for k in cel.values():
        x = ct.setdefault((k["n"], k["f"]), {"viva": 0, "sem": 0, "outra": 0, "nexec": 0})
        x[{"VIVA": "viva", "SEM_VIVACIDADE": "sem", "OUTRA_FALHA": "outra", NAO_EXEC: "nexec"}[k["classe"]]] += 1
    for n in sorted({k[0] for k in ct}):
        pontos[n] = {f: {**veredito_ponto(ct[(n, f)]), **ct[(n, f)]} for f in range(0, fmax(n) + 2)}
        vn[n] = veredito_n(n, pontos[n])
    por_classe = {}
    for k in cel.values():
        por_classe[k["classe"]] = por_classe.get(k["classe"], 0) + 1
    obs_nexec = {x["id"] for x in nexec}
    doc = {"rotulo": "V-B Tendermint. Criterio inteiro f* = floor((n-1)/3)+1; so celulas executaveis; 1 p.p. NAO testavel (n<=13). Vivacidade sob "
                     "EQUIVOCATION com particao por indice PROVISORIA (Q28); SEGURANCA NAO medida; unico instrumento = ObservadorTm.",
           "T_VIVA_S": T_VIVA_S, "MIN_EXEC": MIN_EXEC, "n_celulas_esperadas": len(esp),
           "n_celulas_com_resultado": sum(1 for k in cel.values() if "rc_observador" in k), "por_classe": por_classe,
           "nao_executaveis_por_regra_do_ataque": nexec, "outras_falhas": [k for k in cel.values() if k["classe"] == "OUTRA_FALHA"],
           "previsao_x_observado": {"previstos": sorted(previstos), "observados": sorted(obs_nexec),
                                    "previstos_que_rodaram": sorted(previstos - obs_nexec), "abortos_nao_previstos": sorted(obs_nexec - previstos)},
           "pontos": {str(n): {str(f): p for f, p in ps.items()} for n, ps in pontos.items()},
           "veredito_por_n": {str(n): {"veredito": v[0], "detalhe": v[1]} for n, v in vn.items()},
           "veredito_global": veredito_global({n: v[0] for n, v in vn.items()}), "celulas": list(cel.values())}
    if saida:
        Path(saida).write_text(json.dumps(doc, indent=1, ensure_ascii=False))
    return doc


def autoteste():
    """DADO SINTETICO (fabricado aqui, NAO e saida do simulador): confere so a logica de classificacao e de veredito."""
    msg1 = ("java.lang.IllegalStateException: EQUIVOCATION em PREVOTE (no 2) exige ao menos 1 destinatario honesto em cada metade disjunta dos "
            "vizinhos; honestos na 1a metade=0, na 2a=1 (n=3). Ataque nao aplicado: simulacao abortada em vez de degradar para no honesto.")
    msg2 = ("EQUIVOCATION em PROPOSAL (no 1) exige ao menos 2 vizinhos para enviar mensagens conflitantes a destinatarios disjuntos; "
            "a rede tem 1. Ataque nao aplicado: simulacao abortada em vez de degradar.")
    outra = "java.lang.IllegalStateException: EQUIVOCATION (no 3): bloco real sem pai (genesis?) na altura 0; nao ha como fabricar o conflito"

    def mk(base, nome, rc2, err, resumo=True, rows=None, total=None):
        d = base / nome
        (d / "obs").mkdir(parents=True)
        (d / "resultado.json").write_text(json.dumps({"rc_mvp": None, "rc_observador": rc2, "so_observador": True}))
        (d / "stderr-observador.log").write_text(err + "\n")
        (d / "stdout-observador.log").write_text("")
        if resumo:
            (d / "obs" / "observador-resumo.json").write_text(json.dumps({"finalizacoes_locais_total": total if total is not None else len(rows or []),
                                                                         "byzantine_ids": [0], "altura_maxima_finalizada": len(rows or [])}))
            with gzip.open(d / "obs" / "observador-por-altura.csv.gz", "wt") as f:
                f.write("height,Fmin_s,Fmax_s,n_finalizaram\n")
                for i, t in enumerate(rows or [], 1):
                    f.write(f"{i},{t},{t},3\n")
        return d

    with tempfile.TemporaryDirectory() as td:
        b = Path(td)
        assert classificar(mk(b, "regra1", 1, msg1, resumo=False))["classe"] == NAO_EXEC
        assert classificar(mk(b, "regra2", 1, msg2, resumo=False))["classe"] == NAO_EXEC
        assert classificar(mk(b, "regra-obs-ok", 0, msg1, rows=[1.0]))["classe"] == "OUTRA_FALHA"
        assert classificar(mk(b, "regra-rc0", 0, msg1, resumo=False))["classe"] == "OUTRA_FALHA"
        assert classificar(mk(b, "outra-eq", 1, outra, resumo=False))["classe"] == "OUTRA_FALHA"
        assert classificar(mk(b, "rc1-sem-msg", 1, "Exception qualquer", resumo=False))["classe"] == "OUTRA_FALHA"
        k = classificar(mk(b, "sembloco", 0, "", rows=[]))
        assert (k["classe"], k["sub"]) == ("SEM_VIVACIDADE", "SEM_BLOCO"), k
        k = classificar(mk(b, "parada", 0, "", rows=[1.0, 5.0, 19.99]))
        assert (k["classe"], k["sub"]) == ("SEM_VIVACIDADE", "PARADA_ANTES_DE_T0"), k
        assert classificar(mk(b, "viva", 0, "", rows=[1.0, 20.0]))["classe"] == "VIVA"
        assert classificar(b / "inexistente")["classe"] == "OUTRA_FALHA"

    def P(v, s, o=0, x=0):
        c = {"viva": v, "sem": s, "outra": o, "nexec": x}
        return {**veredito_ponto(c), **c}
    assert P(6, 0, 0, 4)["estado"] == "DECIDIDO" and P(5, 0, 0, 5)["estado"] == "INCONCLUSIVO" and P(10, 0, 1)["estado"] == "INCONCLUSIVO"
    ok4 = {0: P(10, 0), 1: P(10, 0), 2: P(0, 6, 0, 4)}
    assert veredito_n(4, ok4)[0] == "CONSISTENTE"
    assert veredito_n(4, {**ok4, 2: P(0, 4, 0, 6)})[0] == "INCONCLUSIVO"   # ESTE e o caso previsto (6 abortam, k_exec=4 < 6)
    assert veredito_n(4, {**ok4, 2: P(2, 4, 0, 4)})[0] == "MISTO"
    assert veredito_n(4, {**ok4, 2: P(6, 0, 0, 4)})[0] == "INCONSISTENTE"
    assert veredito_n(4, {**ok4, 1: P(9, 1)})[0] == "INCONSISTENTE"
    assert veredito_n(4, {**ok4, 1: P(0, 10)})[0] == "INCONSISTENTE"      # n=4 f=1 sem vivacidade (previsao de risco do teste do Tendermint)
    assert veredito_n(4, {**ok4, 1: P(9, 0, 1)})[0] == "INCONCLUSIVO"
    assert veredito_global({4: "INCONCLUSIVO", 7: "CONSISTENTE", 10: "CONSISTENTE", 13: "CONSISTENTE"}).startswith("INCONCLUSIVO")
    assert veredito_global({4: "INCONSISTENTE", 7: "CONSISTENTE", 10: "CONSISTENTE", 13: "CONSISTENTE"}) == "INCONSISTENTE"
    print("autoteste OK (dado SINTETICO: fabricado pelo proprio script; nao e saida do simulador)")


def main():
    a = sys.argv[1:]
    if a == ["--autoteste"]:
        return autoteste()
    if not a:
        sys.exit(__doc__)
    d = analisar(a[0], a[a.index("--saida") + 1] if "--saida" in a else None)
    print(json.dumps({k: d[k] for k in ("n_celulas_esperadas", "n_celulas_com_resultado", "por_classe", "veredito_por_n", "veredito_global",
                                       "previsao_x_observado")}, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Analise PRE-REGISTRADA da rodada K=40 (PREREGISTRO.md). So biblioteca padrao. Nao simula nada: le dados brutos.

Reaproveita, SEM editar, confirmacao/scripts/analisar-confirmacao-v2.py (analisar_grupo: primario por estatistica de ordem
sobre os K maximos por celula, secundario por N efetivo, S2 por corridas, bootstrap, Clopper-Pearson, IC v1 de visoes
empilhadas) e, por ele, analisar-confirmacao.py (v1). Acrescenta so o que o pre-registro declara:
  (a) T fixo do piloto por n (n=64: 2,065; n=128: 3,396) e so n em {64, 128};
  (b) exige o SHA-256 do PREREGISTRO.md igual ao gravado (aborta se alterado) e confere esperadas x obtidas;
  (c) A1': celula com timeouts_disparados_total != 0 NAO e excluida (excluir viesaria o UB para baixo): fica na analise
      e e reportada como falha de A1' (diferente do v2, onde o timeout era de 60 s);
  (d) evidencia de H-cel: theta por corridas e por Ferro-Segers em q=0,99 e q=0,999, tabela de sensibilidade do UB a
      theta, theta* (maior theta em que o indice j do limite superior sobe) e rotulo descritivo; so o ROTULO do PASSA muda,
      nunca o veredito PASSA/FALHA;
  (e') veredito_final por n com a regra de precedencia pre-registrada (A1' > instrumento > sem UB > UB<=T/2) e conferencias
      de instrumento (MD5 das duas JVMs, visao final - altura final);
  (e) complementar rotulado: empilhamento K40 + confirmacao K=20 (+ R) em K=60/80 por n; NAO decide.
Saida: confirmacao-k40/analise-k40.json.
Uso: analisar-k40.py            (rodada real; exige dados-brutos/k40 completo)
     analisar-k40.py --autoteste (so imprime; usa as celulas ja existentes da confirmacao K=20; NAO escreve arquivo; serve
                                  para conferir que o encadeamento reproduz os numeros de analise-confirmacao-v2.json)
"""
import hashlib
import importlib.util
import json
import math
import sys
from pathlib import Path

K40 = Path(__file__).resolve().parent.parent
PIL = K40.parent
CONF = PIL / "confirmacao"
sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("v2", CONF / "scripts" / "analisar-confirmacao-v2.py")
v2 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v2)
NS = (64, 128)
Q99, Q999 = 0.99, 0.999
GRADE_THETA = [1.0, 0.95, 0.9, 0.85, 0.8, 0.75, 0.7, 0.65, 0.6, 0.55, 0.5]


def j_sup(K, u):
    cdf = v2.binom_cdf_list(K, u)
    for jj in range(1, K + 1):
        if cdf[jj - 1] >= 1 - v2.ALFA2:
            return jj
    return None


def theta_estrela(K, u):
    """Maior theta em (0,1] tal que o indice j do limite superior (calculado em u**theta) e MAIOR que j(theta=1).
    Bisseccao exata sobre a funcao monotona j(theta). None se j nunca sobe (nao ocorre para K=40)."""
    j1 = j_sup(K, u)
    lo, hi = 0.01, 1.0  # j(lo) > j1 (ou None=sem UB); j(hi) == j1
    jl = j_sup(K, u ** lo)
    if jl is not None and jl == j1:
        return None
    for _ in range(60):
        mid = (lo + hi) / 2
        jm = j_sup(K, u ** mid)
        if jm is None or jm > j1:
            lo = mid
        else:
            hi = mid
    return lo


def theta_ferro_segers(series, q):
    """Estimador dos intervalos de Ferro e Segers (2003) do indice extremal, intervalos entre excedencias DENTRO de cada
    celula (nao cruza fronteira de celula). Limiar = quantil q do empilhado (nearest-rank, igual a theta_runs).
    theta = min(1, 2(sum T)^2/((N-1) sum T^2)) se max T <= 2; senao min(1, 2(sum(T-1))^2/((N-1) sum (T-1)(T-2)))."""
    todos = sorted(x for s in series for x in s)
    lim = v2.v1.nr(todos, q)[0]
    gaps, exc = [], 0
    for s in series:
        pos = [i for i, x in enumerate(s) if x > lim]
        exc += len(pos)
        gaps += [b - a for a, b in zip(pos, pos[1:])]
    Ng = len(gaps)
    if Ng == 0:
        return {"limiar": lim, "excedencias": exc, "intervalos": 0, "theta_hat": None}
    if max(gaps) <= 2:
        den = Ng * sum(t * t for t in gaps)
        th = 2 * sum(gaps) ** 2 / den if den else None
    else:
        den = Ng * sum((t - 1) * (t - 2) for t in gaps)
        th = 2 * sum(t - 1 for t in gaps) ** 2 / den if den else None
    return {"limiar": lim, "excedencias": exc, "intervalos": Ng, "max_intervalo": max(gaps),
            "theta_hat": (min(1.0, th) if th is not None else None)}


def evidencia_hcel(n, cels, T):
    series = [c["serie"] for c in cels]
    K = len(series)
    mbar = sum(len(s) for s in series) / K
    u = v2.Q ** mbar
    M = sorted(max(s) for s in series)
    out = {"u": u, "j_theta1": j_sup(K, u), "theta_estrela_j_sobe": theta_estrela(K, u), "tabela_sensibilidade": []}
    for th in GRADE_THETA:
        j = j_sup(K, u ** th)
        ub = M[j - 1] if j else None
        out["tabela_sensibilidade"].append({"theta": th, "u_theta": u ** th, "j": j, "UB": ub,
                                            "UB_menor_igual_T_meio": (ub <= T / 2) if ub is not None else None})
    pas = [r["theta"] for r in out["tabela_sensibilidade"] if r["UB_menor_igual_T_meio"]]
    out["menor_theta_da_grade_em_que_ainda_passa"] = min(pas) if pas else None
    for rot, q in (("p99", Q99), ("p99_9", Q999)):
        th = v2.theta_runs(series, q)
        th["CP95_theta_aproximado_(runs/excedencias_nao_sao_independentes)"] = (
            v2.clopper_pearson(th["corridas"], th["excedencias"]) if th["excedencias"] else None)
        out[f"theta_runs_{rot}"] = th
    for rot, q in (("p99", Q99), ("p99_9", Q999)):
        out[f"theta_ferro_segers_{rot}"] = theta_ferro_segers(series, q)
    # rotulo DESCRITIVO (nao decide o aceite): theta de cauda em p99,9 (runs e Ferro-Segers) vs theta* (o theta abaixo do qual
    # o indice j do limite superior subiria; PREREGISTRO.md, secao 2). Estimativas pontuais; ver IC aproximado reportado.
    pts = [out["theta_runs_p99_9"]["theta_hat"], out["theta_ferro_segers_p99_9"]["theta_hat"]]
    ts = out["theta_estrela_j_sobe"]
    out["theta_ref_p99_9_pontuais"] = pts
    out["rotulo_H_cel"] = ("H-cel NAO CONTRADITA pela evidencia descritiva (nao e prova)"
                           if ts is not None and all(x is not None and x >= ts for x in pts)
                           else "H-cel NAO SUSTENTADA pela evidencia descritiva (conclusao condicionada a H-cel)")
    return out


def equivalencia_e_visao(dirs):
    """Conferencias de instrumento por celula (reportadas; regra no PREREGISTRO.md secao 3): MD5 do CSV por bloco (sem
    BlockHashCode) igual entre MVPComparison e observador; visao final por no - altura final in {0,1}."""
    diverge, histo = [], {}
    for d in dirs:
        try:
            r = json.loads((d / "resultado.json").read_text())
            rs = json.loads((d / "obs" / "observador-resumo.json").read_text())
        except Exception:
            continue
        if r.get("md5_sem_hashcode_mvp") != r.get("md5_sem_hashcode_obs") or r.get("blocos_mvp") != r.get("blocos_obs") \
                or not r.get("blocos_mvp"):
            diverge.append(d.name)
        for v in rs["visao_corrente_final_por_no"]:
            k = v - rs["altura_maxima_finalizada"]
            histo[k] = histo.get(k, 0) + 1
    return {"celulas_com_md5_divergente_ou_sem_blocos": diverge,
            "visao_final_menos_altura_final_histograma": dict(sorted(histo.items())),
            "visao_final_menos_altura_final_em_0_1": set(histo) <= {0, 1}}


def veredito_final(r, a1_ok, instr_ok):
    """Regra pre-registrada (PREREGISTRO.md, secao 3), nesta ordem de precedencia."""
    p = r["primario"]
    if not a1_ok:
        return "FALHA (A1': timeout espurio em celula sem falha)"
    if not instr_ok:
        return "INCONCLUSIVO (instrumento: MD5/blocos divergentes entre as duas JVMs)"
    if p["superior"] is None:
        return "INCONCLUSIVO (sem UB: K obtido < K_min)"
    if p["superior"] > r["T_meio"]:
        return "FALHA"
    return "PASSA" if r["evidencia_H_cel_descritiva"]["rotulo_H_cel"].startswith("H-cel NAO CONTRADITA") \
        else "PASSA condicionado a H-cel"


def carregar(dirs):
    cels, prob = [], []
    for d in dirs:
        try:
            cels.append(v2.carregar_celula(d))
        except Exception as e:  # celula faltante/ilegivel: nao e completada
            prob.append({"id": d.name, "erro": f"{type(e).__name__}: {e}"})
    return cels, prob


def separar_rc(cels):
    ok, prob = [], []
    for c in cels:
        if c["rc"] != (0, 0):
            prob.append({"id": c["id"], "erro": f"rc={c['rc']} (fora da analise; K reduzido, sem substituicao)"})
        else:
            ok.append(c)
    return ok, prob


def main():
    auto = sys.argv[1:] == ["--autoteste"]
    pre = K40 / "PREREGISTRO.md"
    sha_pre = hashlib.sha256(pre.read_bytes()).hexdigest() if pre.exists() else None
    if not auto:
        gravado = (K40 / "PREREGISTRO.sha256").read_text().split()[0]
        assert sha_pre == gravado, "PREREGISTRO.md ALTERADO depois do registro do hash"
    esp = json.loads((K40 / "celulas-esperadas.json").read_text())
    ids_por_n = {n: [c["id"] for c in esp["celulas"] if c["n"] == n] for n in NS}
    brutos = K40 / "dados-brutos" / "k40"
    if auto:  # so conferencia do encadeamento, sobre dados ja existentes
        man = json.loads((CONF / "celulas" / "confirmacao-L100" / "manifesto.json").read_text())
        dirs_n = {n: [CONF / "dados-brutos" / "confirmacao" / i for i in man["celulas"] if f"_n{n}_" in i] for n in NS}
    else:
        dirs_n = {n: [brutos / i for i in ids_por_n[n]] for n in NS}
    saida = {"preregistro_sha256": sha_pre, "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
             "v2_script_sha256": hashlib.sha256((CONF / "scripts" / "analisar-confirmacao-v2.py").read_bytes()).hexdigest(),
             "parametros": {"Q": v2.Q, "alfa_por_cauda": v2.ALFA2, "H0": v2.H0, "KMAX_LAG": v2.KMAX_LAG,
                            "T": {str(n): v2.T_PILOTO[n] for n in NS}, "N_ALVO": v2.N_ALVO, "boot_B": v2.BOOT_B,
                            "boot_seed": v2.BOOT_SEED, "autoteste": auto},
             "primario_K40": {}, "A1_timeouts": {}, "esperadas_obtidas": {}, "problemas": [], "complementar_rotulado": {}}
    cels_n = {}
    for n in NS:
        cels, prob = carregar(dirs_n[n])
        cels, prob2 = separar_rc(cels)
        saida["problemas"] += prob + prob2
        saida["esperadas_obtidas"][str(n)] = [len(dirs_n[n]), len(cels)]
        cels_n[n] = cels
        saida["A1_timeouts"][str(n)] = {"celulas_com_timeout": [c["id"] for c in cels if c["timeouts"] != 0],
                                        "timeouts_total": sum(c["timeouts"] for c in cels),
                                        "A1_prime": "OK" if all(c["timeouts"] == 0 for c in cels) else "FALHA (reportar celula e altura)"}
        r = v2.analisar_grupo(n, cels)
        r["evidencia_H_cel_descritiva"] = evidencia_hcel(n, cels, v2.T_PILOTO[n])
        r["instrumento"] = equivalencia_e_visao(dirs_n[n])
        r["VEREDITO_PRE_REGISTRADO"] = veredito_final(
            r, saida["A1_timeouts"][str(n)]["A1_prime"] == "OK",
            not r["instrumento"]["celulas_com_md5_divergente_ou_sem_blocos"])
        saida["primario_K40"][str(n)] = r
    # complementar rotulado: K40 + confirmacao K=20 (+ R K=20) por n; NAO decide
    for n in NS:
        man = json.loads((CONF / "celulas" / "confirmacao-L100" / "manifesto.json").read_text())
        conf, _ = separar_rc(carregar([CONF / "dados-brutos" / "confirmacao" / i for i in man["celulas"] if f"_n{n}_" in i])[0])
        R_dirs = sorted((PIL / "dados-brutos" / "medicao").glob(f"med_rotating_L100_n{n}_*")) + \
            sorted((PIL / "dados-brutos" / "verificacao").glob(f"ver_m3_rotating_L100_n{n}_*"))
        R, _ = separar_rc(carregar(R_dirs)[0])
        R = [c for c in R if c["timeouts"] == 0]  # como no v2: R tinha timeout de 60 s (outra configuracao)
        saida["complementar_rotulado"][str(n)] = {
            "ROTULO": "COMPLEMENTAR/POS-HOC; NAO decide; NAO entra no aceite",
            "K40_mais_confirmacaoK20": v2.analisar_grupo(n, cels_n[n] + conf),
            "K40_mais_confirmacaoK20_mais_R": v2.analisar_grupo(n, cels_n[n] + conf + R)}
    if auto:  # exercita tambem o complementar e a serializacao (as celulas da confirmacao entram duas vezes: so teste de codigo)
        Path("/tmp/opencode").mkdir(parents=True, exist_ok=True)
        Path("/tmp/opencode/analise-k40-autoteste.json").write_text(json.dumps(saida, indent=1, ensure_ascii=False, default=str))
        for n in NS:
            c = saida["complementar_rotulado"][str(n)]
            print(f"[AUTOTESTE complementar, SEM significado estatistico] n={n} K={c['K40_mais_confirmacaoK20']['K']}/"
                  f"{c['K40_mais_confirmacaoK20_mais_R']['K']} j={c['K40_mais_confirmacaoK20']['primario']['j_sup']}/"
                  f"{c['K40_mais_confirmacaoK20_mais_R']['primario']['j_sup']}")
        for n in NS:
            r = saida["primario_K40"][str(n)]
            p, s = r["primario"], r["secundario"]
            e = r["evidencia_H_cel_descritiva"]
            print(f"[AUTOTESTE, dados K=20 da confirmacao] n={n} K={r['K']} j={p['j_sup']} UB={p['superior']} {p['veredito']} | "
                  f"SEC Neff={s['N_eff_info']['N_eff']:.0f} {s['veredito']} | theta*={e['theta_estrela_j_sobe']} "
                  f"theta99={e['theta_runs_p99']['theta_hat']} theta99.9(runs)={e['theta_runs_p99_9']['theta_hat']} "
                  f"theta99.9(FS)={e['theta_ferro_segers_p99_9']['theta_hat']} | {e['rotulo_H_cel']} | "
                  f"instr_diverge={r['instrumento']['celulas_com_md5_divergente_ou_sem_blocos']} "
                  f"visao-altura_ok={r['instrumento']['visao_final_menos_altura_final_em_0_1']} | VEREDITO={r['VEREDITO_PRE_REGISTRADO']}")
        return
    (K40 / "analise-k40.json").write_text(json.dumps(saida, indent=1, ensure_ascii=False, default=str))
    for n in NS:
        r = saida["primario_K40"][str(n)]
        p, s = r["primario"], r["secundario"]
        print(f"n={n} K={r['K']} N={r['N']} j={p['j_sup']} UB={p['superior']} T/2={r['T_meio']} {p['veredito']} | "
              f"SEC DEFF={s['N_eff_info']['DEFF']:.3f} Neff={s['N_eff_info']['N_eff']:.0f} {s['veredito']} | "
              f"{r['rotulo_robustez']} | VEREDITO PRE-REGISTRADO: {r['VEREDITO_PRE_REGISTRADO']}")
    sys.exit(0 if not saida["problemas"] and all(v["A1_prime"] == "OK" for v in saida["A1_timeouts"].values()) else 3)


if __name__ == "__main__":
    main()

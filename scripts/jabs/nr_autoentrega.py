#!/usr/bin/env python3
r"""Evidencia de D2 (ADR-0015, registro de 10/11/2026): AUTOENTREGA fora do C_B principal, coluna propria. Compara o build do
HEAD (git archive, ver construir-base-head.py) com a arvore de trabalho; todas as JVM com
-XX:+UnlockExperimentalVMOptions -XX:hashCode=2. A mudanca e SO DE CONTABILIDADE: nenhum evento muda de tempo nem de ordem.

Uso:
  construir-base-head.py --commit HEAD --destino <TMP>/opencode/d2-head          # uma vez (<TMP> = tempfile.gettempdir())
  nr_autoentrega.py --base <TMP>/opencode/d2-head --atual . [--saida <TMP>/opencode/d2-nr] [--so ABCD]

Casos (semente fixa):
  A  protocolos que NAO contam autoentrega (DAGSPER, HOTSTUFF, TENDERMINT): HEAD x atual IDENTICOS byte a byte (todos os
     arquivos, inclusive linhas '#'); e o aditivo mvp-results-autoentrega.csv NAO pode existir no atual.
  B  protocolos que contavam (ARQ=PBFT, MCO2/TREE=Casper, AMBIFY=Parlia, OFFSET/EARTH=Nakamoto), por n:
        B1 CSV por trial: o atual so ganha a coluna Cb_autoentrega_B NO FIM; toda coluna existente mantem nome e posicao
           (inclusive o '-basic-log.csv' do BlockFinalizationLogger de TREE/Casper, Parlia e Nakamoto: 9 colunas identicas
           fora TrafficUntilFinalization = base - Cb_autoentrega_B; comentario '#' declara EXCLUDING e SimulatorVersion);
          mesmo numero de linhas; TODA coluna que nao e de trafego (Time, NodeID, BlockHeight, BlockSize, BlockCreationTime,
          BlockCreator, BlockFinalizationTime, hash, Tb_Avg, BFT, Pdv) IDENTICA; ESTA e a prova de que Tb/blocos/Tt nao mudam
          (PARA com saida 3 se alguma divergir: a mudanca deixaria de ser so contabilidade, decisao humana).
       B2 conservacao LINHA A LINHA: TrafficUntilFinalization(base) == TrafficUntilFinalization(atual) + Cb_autoentrega_B(atual)
          (nada some: o byte mudou de coluna); Cb_AvgTraffic_MB e TrafficConsensusUntilFinalization do atual <= os da base.
       B3 mvp-results.csv: so Cb_avg e Cb_std podem diferir (Tb_avg, Tb_std, Tt_avg, Tt_std, project, n, trials identicos).
       B4 mvp-results-autoentrega.csv existe no atual (ausente na base) e tem a sentinela -1 apenas onde nada foi medido.
       B5 determinismo: atual-a x atual-b IDENTICOS byte a byte.
     Imprime ANTES x DEPOIS por protocolo e n (Cb_avg em bytes, autoentrega media por bloco, fracao).
   C  ByzantineSweep: VOTING/POS/POSA/POW com ataque e max-byz <= universo: cabecalho = HEAD + avgSelfDelivery_MB,
      stdSelfDelivery_MB no fim; so avgTraffic/stdTraffic podem diferir, e avgTraffic(HEAD) ~ avgTraffic(atual) +
      avgSelfDelivery_MB (tol. 2,5e-6; -1 = nao medido: sem conservacao); linhas '#' do HEAD preservadas + 2 novas; DAGSPER
      IDENTICO byte a byte, sem linhas novas; max-byz > universo falha ALTO (atual) e max-byz absurdo nao entra em laco.
Saida: 0 = conforme; 1 = divergencia inesperada; 2 = erro de execucao; 3 = coluna de DINAMICA (Tb/blocos/Tt/hash) mudou: PARE.
"""
import argparse
import csv
import os
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

TMP_OPENCODE = Path(tempfile.gettempdir()) / "opencode"  # no Linux: /tmp/opencode

JVM = ["-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2"]
HS = ["--hs-view-timeout=10.0", "--hs-max-future-view-horizon=1000", "--hs-batch-size=20", "--hs-tx-size=512",
      "--hs-individual-signature-size=64", "--hs-aggregated-signature-size=96", "--hs-aggregated-share-size=96",
      "--hs-message-overhead=10", "--hs-signature-mode=INDIVIDUAL", "--hs-leader-policy=ROTATING",
      "--hs-voter-encoding=BITMAP"]
TM = ["--tm-propose-timeout=1.0", "--tm-propose-delta=0.5", "--tm-prevote-timeout=1.0", "--tm-prevote-delta=0.5",
      "--tm-precommit-timeout=1.0", "--tm-precommit-delta=0.5", "--tm-batch-size=20", "--tm-tx-size=512",
      "--tm-signature-size=64", "--tm-proposer-signature-size=80", "--tm-message-overhead=10", "--tm-max-future-horizon=1000"]
PBFT = ["--pbft-max-future-horizon=1000"]  # teto de retencao do PBFT: obrigatorio, sem padrao (a base antiga ignora o flag)
# Q32 A: --n-semantics e obrigatoria onde n difere (MCO2/TREE/AMBIFY, POS/POSA); a nao regressao fixa legacy
SEM_N = ["--n-semantics=legacy"]
EXTRAS = {"HOTSTUFF": HS, "TENDERMINT": TM, "ARQ": PBFT, "MCO2": SEM_N, "TREE": SEM_N, "AMBIFY": SEM_N}
COLUNA_NOVA = "Cb_autoentrega_B"
COLUNA_CONSENSO = "Cb_autoentrega_consenso_B"
COL_TRAFEGO = {"TrafficUntilFinalization", "Cb_AvgTraffic_MB", "TrafficConsensusUntilFinalization"}
ADITIVO = "mvp-results-autoentrega.csv"
COLS_SWEEP = ["avgSelfDelivery_MB", "stdSelfDelivery_MB"]  # colunas aditivas do s.csv do ByzantineSweep (no fim)
TOL_SWEEP = 2.5e-6  # arredondamento de 6 casas das duas parcelas (%.6f)

# (protocolo, n, duracao s): duracoes em que o protocolo FINALIZA blocos (as mesmas de nr_byz_unica.py)
CASOS_A = [("DAGSPER", 10, 600), ("HOTSTUFF", 4, 30), ("HOTSTUFF", 7, 30), ("TENDERMINT", 4, 30), ("TENDERMINT", 7, 30)]
CASOS_B = [("ARQ", 4, 60), ("ARQ", 7, 60), ("ARQ", 10, 60), ("ARQ", 13, 60),
           ("MCO2", 10, 1200), ("MCO2", 20, 1200), ("TREE", 10, 3000), ("AMBIFY", 10, 600), ("AMBIFY", 20, 600),
           ("OFFSET", 40, 20000), ("EARTH", 40, 20000)]


def classpath(raiz: Path) -> str:
    cp = raiz / "target" / "cp.txt"
    if not cp.exists():
        sys.exit(f"ERRO: {cp} ausente")
    return f"{raiz / 'target' / 'classes'}{os.pathsep}{cp.read_text(encoding='utf-8').strip()}"


def java(raiz: Path, args, timeout=3600):
    return subprocess.run(["java", *JVM, "-cp", classpath(raiz), *args], capture_output=True, text=True,
                          cwd=str(raiz), timeout=timeout)


def mvp(raiz, saida: Path, proto, n, dur, semente):
    r = java(raiz, ["jabs.example.MVPComparison", f"--protocols={proto}", f"--validators={n}", "--trials=1",
                    f"--duration={dur}", f"--seed={semente}", f"--output={saida}", *EXTRAS.get(proto, [])])
    if r.returncode != 0:
        print(f"ERRO {proto} n={n} em {raiz} rc={r.returncode}\n{r.stdout[-600:]}\n{r.stderr[-900:]}")
        sys.exit(2)


def arquivos(d: Path):
    return {p.relative_to(d).as_posix(): p.read_bytes() for p in sorted(d.rglob("*")) if p.is_file()}


def ler_csv(path: Path):
    """Linhas sem '#', separador detectado; devolve (cabecalho, linhas)."""
    linhas = [l for l in path.read_text(encoding="utf-8", errors="replace").splitlines() if l.strip() and not l.startswith("#")]
    delim = ";" if linhas[0].count(";") > linhas[0].count(",") else ","
    rows = list(csv.reader(linhas, delimiter=delim))
    return rows[0], rows[1:]


def comentarios(path: Path):
    """Linhas '#' do arquivo (comentario de cabecalho), em ordem."""
    return [l for l in path.read_text(encoding="utf-8", errors="replace").splitlines() if l.startswith("#")]


def num(x):
    return float(x)


def rodar_todos(tarefas, maxp=4):
    """tarefas: lista de (raiz, saida, proto, n, dur, semente); no maximo 4 JVM simultaneas (limite documentado do projeto)."""
    with ThreadPoolExecutor(max_workers=maxp) as ex:
        list(ex.map(lambda t: mvp(*t), tarefas))


def caso_a(base, atual, saida, semente):
    print("== A: protocolos que NAO contam autoentrega: IDENTICOS byte a byte (e sem aditivo)")
    tarefas = []
    for p, n, d in CASOS_A:
        for nome, raiz in (("base", base), ("atual", atual)):
            tarefas.append((raiz, saida / f"A-{p}-{n}-{nome}", p, n, d, semente))
    rodar_todos(tarefas)
    ok = True
    for p, n, d in CASOS_A:
        fa, fb = arquivos(saida / f"A-{p}-{n}-base"), arquivos(saida / f"A-{p}-{n}-atual")
        if not fa:
            print(f"[A {p} n={n}] SEM_DADOS")
            ok = False
        elif fa.keys() != fb.keys():
            print(f"[A {p} n={n}] DIVERGENTE: arquivos so em base={sorted(fa.keys()-fb.keys())} so em atual={sorted(fb.keys()-fa.keys())}")
            ok = False
        else:
            ruins = [k for k in fa if fa[k] != fb[k]]
            if ruins:
                print(f"[A {p} n={n}] DIVERGENTE: {ruins}")
                ok = False
            else:
                print(f"[A {p} n={n} dur={d}] IDENTICO: {len(fa)} arquivos, {sum(len(v) for v in fa.values())} bytes")
    return ok


def caso_b(base, atual, saida, semente):
    print("== B: protocolos que contavam: diferenca SO em Cb (e derivadas) + coluna nova; resto IDENTICO")
    tarefas = []
    for p, n, d in CASOS_B:
        for nome, raiz in (("base", base), ("atual-a", atual), ("atual-b", atual)):
            tarefas.append((raiz, saida / f"B-{p}-{n}-{nome}", p, n, d, semente))
    rodar_todos(tarefas)
    ok = True
    dinamica_mudou = False
    tabela = []
    for p, n, d in CASOS_B:
        rot = f"B {p} n={n} dur={d}"
        b, a, a2 = (saida / f"B-{p}-{n}-{x}" for x in ("base", "atual-a", "atual-b"))
        # B5 determinismo
        fa, fb = arquivos(a), arquivos(a2)
        det = fa and fa.keys() == fb.keys() and all(fa[k] == fb[k] for k in fa)
        print(f"[{rot}] B5 determinismo atual-a x atual-b: {'IDENTICO' if det else 'DIVERGENTE'}")
        ok &= bool(det)
        # arquivos: o atual so pode ter A MAIS o aditivo
        fbase = arquivos(b)
        extras = sorted(set(fa) - set(fbase))
        faltam = sorted(set(fbase) - set(fa))
        if faltam or extras != [ADITIVO]:
            print(f"[{rot}] DIVERGENTE: arquivos faltando={faltam} extras={extras} (esperado extras=[{ADITIVO}])")
            ok = False
            continue
        # B1/B2 por trial CSV
        csvs = [k for k in fbase if k.endswith(".csv") and k != "mvp-results.csv"]
        for k in csvs:
            if "-basic-log" in k:  # BlockFinalizationLogger (TREE/Casper, Parlia, Nakamoto): coluna aditiva no fim; so TrafficUntilFinalization cai
                cb, rb = ler_csv(b / k)
                ca, ra = ler_csv(a / k)
                if ca[:len(cb)] != cb or ca[len(cb):] != [COLUNA_NOVA]:
                    print(f"[{rot}] DIVERGENTE: {k} cabecalho. base={cb} atual={ca} (esperado base + [{COLUNA_NOVA}] no fim)")
                    ok = False
                    continue
                if len(ra) != len(rb):
                    print(f"[{rot}] DIVERGENTE: {k} linhas base={len(rb)} atual={len(ra)}")
                    ok = False
                    dinamica_mudou = True
                    continue
                it = cb.index("TrafficUntilFinalization")
                iauto = len(cb)
                din = [(j, cb[i]) for j, (x, y) in enumerate(zip(rb, ra)) for i in range(len(cb)) if i != it and x[i] != y[i]]
                if din:
                    print(f"[{rot}] *** DINAMICA MUDOU em {k}: {len(din)} celula(s), ex.: {din[0]}")
                    dinamica_mudou = True
                    ok = False
                    continue
                # conservacao linha a linha: base == atual + autoentrega (autoentrega -1 = nao medido: o trafego tem de ser igual)
                cons_ruins = [j for j, (x, y) in enumerate(zip(rb, ra))
                              if ((num(x[it]) != num(y[it]) + num(y[iauto])) if num(y[iauto]) >= 0
                                  else (num(x[it]) != num(y[it])))]
                subiu = [j for j, (x, y) in enumerate(zip(rb, ra)) if num(y[it]) > num(x[it])]
                mudou = sum(1 for x, y in zip(rb, ra) if x[it] != y[it])
                nneg = sum(1 for y in ra if num(y[iauto]) < 0)
                # comentario '#': o da base e prefixo do atual e declara EXCLUDING, granularidade e SimulatorVersion
                cm_b, cm_a = comentarios(b / k), comentarios(a / k)
                txt = "\n".join(cm_a)
                cm_ruim = (len(cm_a) != len(cm_b) or not all(y.startswith(x) for x, y in zip(cm_b, cm_a))
                           or not all(t in txt for t in ("EXCLUDING", "granularity", "SimulatorVersion=")))
                if cons_ruins or subiu or cm_ruim:
                    print(f"[{rot}] DIVERGENTE B1/B2 em {k}: conservacao={len(cons_ruins)} subiu={len(subiu)} "
                          f"comentario_ruim={cm_ruim}")
                    ok = False
                print(f"[{rot}] B1/B2 {k} (log basico): {len(ra)} linhas; 9 colunas base IDENTICAS exceto TrafficUntilFinalization "
                      f"(difere em {mudou}); base = atual + {COLUNA_NOVA} em todas as linhas; linhas com sentinela -1={nneg}")
                continue
            cb, rb = ler_csv(b / k)
            ca, ra = ler_csv(a / k)
            # rodada 4 do D2: no PBFT o Enhanced ganha tambem a coluna so-consenso, logo depois de COLUNA_NOVA
            tem_cons = ca[len(cb):] == [COLUNA_NOVA, COLUNA_CONSENSO]
            if ca[:len(cb)] != cb or (ca[len(cb):] != [COLUNA_NOVA] and not tem_cons):
                print(f"[{rot}] DIVERGENTE: cabecalho. base={cb} atual={ca}")
                ok = False
                continue
            if len(rb) != len(ra):
                print(f"[{rot}] DIVERGENTE: linhas base={len(rb)} atual={len(ra)}")
                ok = False
                dinamica_mudou = True
                continue
            idx_t = {c: i for i, c in enumerate(cb) if c in COL_TRAFEGO}
            idx_d = [i for i, c in enumerate(cb) if c not in COL_TRAFEGO]
            iauto = len(cb)
            dyn_ruins = [(j, cb[i]) for j, (x, y) in enumerate(zip(rb, ra)) for i in idx_d if x[i] != y[i]]
            if dyn_ruins:
                print(f"[{rot}] *** DINAMICA MUDOU em {k}: {len(dyn_ruins)} celula(s), ex.: linha {dyn_ruins[0][0]} coluna {dyn_ruins[0][1]}")
                dinamica_mudou = True
                ok = False
                continue
            it = idx_t["TrafficUntilFinalization"]
            ic = idx_t["Cb_AvgTraffic_MB"]
            icons = idx_t["TrafficConsensusUntilFinalization"]
            cons_ruins = [j for j, (x, y) in enumerate(zip(rb, ra)) if num(x[it]) != num(y[it]) + num(y[iauto])]
            avg_ruins = [j for j, (x, y) in enumerate(zip(rb, ra)) if num(y[ic]) > num(x[ic]) + 1e-12]
            cons_cons = [j for j, (x, y) in enumerate(zip(rb, ra))
                         if num(x[icons]) >= 0 and (num(y[icons]) > num(x[icons]) or num(y[icons]) < 0)]
            # rodada 4: conservacao do consenso LINHA A LINHA (base == atual + autoentrega so-consenso; -1 = nao medido
            # na base ou no atual: sem conservacao, o valor tem de ser igual)
            cons_cons_ruins = []
            if tem_cons:
                ico = len(cb) + 1
                for j, (x, y) in enumerate(zip(rb, ra)):
                    bc, ac, ec = num(x[icons]), num(y[icons]), num(y[ico])
                    if bc < 0 or ec < 0:
                        if bc != ac:
                            cons_cons_ruins.append(j)
                    elif bc != ac + ec or ec > num(y[iauto]):
                        cons_cons_ruins.append(j)
            if cons_ruins or avg_ruins or cons_cons or cons_cons_ruins:
                print(f"[{rot}] DIVERGENTE B2 em {k}: conservacao={len(cons_ruins)} avg>base={len(avg_ruins)} "
                      f"consenso={len(cons_cons)} conservacao_consenso={len(cons_cons_ruins)}")
                ok = False
            soma_auto = sum(num(y[iauto]) for y in ra)
            nneg = sum(1 for y in ra if num(y[iauto]) < 0)
            print(f"[{rot}] B1/B2 {k}: {len(ra)} linhas; dinamica IDENTICA; base = atual + autoentrega em todas as linhas; "
                  f"autoentrega total={int(soma_auto)} B; linhas com sentinela -1={nneg}")
        # B3 mvp-results.csv
        hb, lb = ler_csv(b / "mvp-results.csv")
        ha, la = ler_csv(a / "mvp-results.csv")
        if hb != ha or len(lb) != len(la):
            print(f"[{rot}] DIVERGENTE B3: cabecalho/linhas de mvp-results.csv")
            ok = False
            continue
        difer = sorted({hb[i] for x, y in zip(lb, la) for i in range(len(hb)) if x[i] != y[i]})
        if not set(difer) <= {"Cb_avg", "Cb_std"}:
            print(f"[{rot}] *** B3 DINAMICA MUDOU em mvp-results.csv: colunas diferentes={difer}")
            dinamica_mudou = True
            ok = False
            continue
        print(f"[{rot}] B3 mvp-results.csv: colunas que diferem={difer or 'nenhuma'}; Tb/Tt/trials identicos")
        # B4 aditivo
        hz, lz = ler_csv(a / ADITIVO)
        tabela.append((p, n, lb[0], la[0], hb, hz, lz[0]))
    print("\n-- ANTES (HEAD) x DEPOIS (atual): Cb_avg em MB (mvp-results.csv) e autoentrega (mvp-results-autoentrega.csv, bytes)")
    print(f"{'protocolo':9} {'n':>3} {'Cb_avg ANTES':>13} {'Cb_avg DEPOIS':>14} {'autoentr.avg B':>15} {'consenso B':>11}")
    for p, n, lb0, la0, hb, hz, lz0 in tabela:
        icb = hb.index("Cb_avg")
        print(f"{p:9} {n:>3} {lb0[icb]:>13} {la0[icb]:>14} {lz0[hz.index('Cb_autoentrega_avg_B')]:>15} "
              f"{lz0[hz.index('Cb_autoentrega_consenso_avg_B')]:>11}")
    if dinamica_mudou:
        print("PARE: coluna de DINAMICA (Tb/blocos/Tt/hash) mudou. A autoentrega afetaria o atraso; decisao humana.")
    return ok, dinamica_mudou


def sweep(raiz, saida: Path, tipo, n, maxbyz, ataque, semente, dur, extras=()):
    saida.mkdir(parents=True, exist_ok=True)
    return java(raiz, ["jabs.example.ByzantineSweep", f"--type={tipo}", f"--validators={n}", f"--max-byz={maxbyz}",
                       "--trials=1", f"--duration={dur}", f"--attack={ataque}", f"--seed={semente}",
                       f"--out={saida / 's.csv'}", *(["--pbft-max-future-horizon=1000"] if tipo == "VOTING" else []),
                       *(SEM_N if tipo in ("POS", "POSA") else []), *extras], timeout=3600)


def caso_c(base, atual, saida, semente):
    print("== C: ByzantineSweep (max-byz <= universo): so avgTraffic/stdTraffic podem diferir; DAGSPER identico; divida do laco")
    ok = True
    for tipo, n, ataque, dur, maxb in (("VOTING", 10, "EQUIVOCATION", 120, 3), ("POS", 10, "WITHHOLD", 1200, 3),
                                       ("POSA", 25, "WITHHOLD", 300, 2), ("POW", 40, "WITHHOLD", 8000, 1),
                                       ("DAGSPER", 10, "WITHHOLD", 600, 3)):
        for nome, raiz in (("base", base), ("atual", atual)):
            r = sweep(raiz, saida / f"C-{tipo}-{nome}", tipo, n, maxb, ataque, semente, dur)
            if r.returncode != 0:
                print(f"ERRO sweep {tipo} {nome} rc={r.returncode}\n{r.stdout[-500:]}\n{r.stderr[-700:]}")
                sys.exit(2)
        pb, pa = saida / f"C-{tipo}-base", saida / f"C-{tipo}-atual"
        if tipo == "DAGSPER":  # nao separa autoentrega: pasta inteira (s.csv com linhas '#' e CSV por trial) IDENTICA byte a byte
            fa, fb = arquivos(pb), arquivos(pa)
            if not fa or fa.keys() != fb.keys() or any(fa[k] != fb[k] for k in fa):
                print(f"[C {tipo}] DIVERGENTE: nao e byte a byte igual (arquivos base={sorted(fa)} atual={sorted(fb)}; "
                      f"bytes diferentes={sorted(k for k in fa if k in fb and fa[k] != fb[k])})")
                ok = False
            else:
                print(f"[C {tipo} n={n} max-byz={maxb}] CONFORME: IDENTICO byte a byte, {len(fa)} arquivos, sem linhas novas")
            continue
        hb, lb = ler_csv(pb / "s.csv")
        ha, la = ler_csv(pa / "s.csv")
        # D2: cabecalho = HEAD + avgSelfDelivery_MB,stdSelfDelivery_MB no fim; mesmo numero de linhas
        if ha != hb + COLS_SWEEP or len(lb) != len(la):
            print(f"[C {tipo}] DIVERGENTE: cabecalho/linhas. base={hb} atual={ha} (esperado base + {COLS_SWEEP}); linhas {len(lb)} x {len(la)}")
            ok = False
            continue
        # linhas '#': as do HEAD preservadas no inicio e 2 novas (semantica EXCLUEM; Seed/SimulatorVersion/Granularity)
        cm_b, cm_a = comentarios(pb / "s.csv"), comentarios(pa / "s.csv")
        novas = "\n".join(cm_a[len(cm_b):])
        cm_ok = (cm_a[:len(cm_b)] == cm_b and len(cm_a) == len(cm_b) + 2
                 and all(t in novas for t in ("EXCLUEM", "Seed=", "SimulatorVersion=", "Granularity=")))
        difer = sorted({hb[i] for x, y in zip(lb, la) for i in range(len(hb)) if x[i] != y[i]})
        permitido = {"avgTraffic", "stdTraffic"}
        iat, k = hb.index("avgTraffic"), len(hb)
        medidas = [(num(x[iat]), num(y[iat]), num(y[k])) for x, y in zip(lb, la)]
        # conservacao (media): base ~ atual + avgSelfDelivery_MB; -1 = nao medido: nao conferir (so nao pode subir)
        ruins_cons = [(b0, a0, s0) for b0, a0, s0 in medidas
                      if (abs(b0 - (a0 + s0)) > TOL_SWEEP if s0 >= 0 else a0 > b0 + TOL_SWEEP)]
        desvio = max((abs(b0 - (a0 + s0)) for b0, a0, s0 in medidas if s0 >= 0), default=0.0)
        nao_med = sum(1 for _, _, s0 in medidas if s0 < 0)
        if set(difer) <= permitido and cm_ok and not ruins_cons:
            print(f"[C {tipo} n={n} max-byz={maxb}] CONFORME: colunas que diferem={difer or 'nenhuma'}; cabecalho = base + {COLS_SWEEP}; "
                  f"2 linhas '#' novas; conservacao avgTraffic(base) = atual + avgSelfDelivery_MB em {len(medidas) - nao_med} linha(s) "
                  f"(desvio max {desvio:.2e}, tol {TOL_SWEEP:.1e}); nao medido(-1)={nao_med}")
        else:
            print(f"[C {tipo}] DIVERGENTE: colunas que diferem={difer} (permitido={sorted(permitido)}); linhas_'#'_ok={cm_ok}; "
                  f"conservacao violada={ruins_cons[:2]}")
            ok = False
    # divida do laco: max-byz > universo falha alto, sem laco, sem arquivo
    r = sweep(atual, saida / "C-universo", "VOTING", 10, 2147483647, "EQUIVOCATION", semente, 10)
    msg = r.stdout + r.stderr
    cond = r.returncode != 0 and "maior que o universo" in msg and not (saida / "C-universo" / "s.csv").exists()
    print(f"[C max-byz=2147483647 > universo] {'OK falha alto, nada gravado' if cond else 'FALHOU'} rc={r.returncode}")
    ok &= cond
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True)
    ap.add_argument("--atual", default=".")
    ap.add_argument("--saida", default=str(TMP_OPENCODE / "d2-nr"))
    ap.add_argument("--semente", type=int, default=1)
    ap.add_argument("--so", default="ABC")
    a = ap.parse_args()
    base, atual, saida = Path(a.base).resolve(), Path(a.atual).resolve(), Path(a.saida)
    if saida.exists() and any(saida.iterdir()):
        sys.exit(f"ERRO: saida {saida} ja existe e nao esta vazia (nao sobrescrevo)")
    saida.mkdir(parents=True, exist_ok=True)
    print(f"semente={a.semente} JVM={' '.join(JVM)} base={base} atual={atual}")
    ok, dinamica = True, False
    if "A" in a.so:
        ok &= caso_a(base, atual, saida, a.semente)
    if "B" in a.so:
        r, dinamica = caso_b(base, atual, saida, a.semente)
        ok &= r
    if "C" in a.so:
        ok &= caso_c(base, atual, saida, a.semente)
    print("RESULTADO:", "CONFORME" if ok else "DIVERGENTE")
    if dinamica:
        return 3
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
r"""Evidencia de BYZ-UNICA (ADR-0015, registros de 10/11, 11/11 e 16/11): contagem inteira + faixa BFT + gerador unico
aninhado + universo so de validadores/votantes. Compara o build do HEAD (git archive, ver construir-base-head.py) com a
arvore de trabalho. Padrao de nr_byz_fora_faixa.py: NAO ignora linhas '#'; a exigencia nos casos "sem ataque" e saida
IDENTICA byte a byte. Todas as JVM rodam com -XX:+UnlockExperimentalVMOptions -XX:hashCode=2.

Uso:
  construir-base-head.py --commit HEAD --destino /tmp/opencode/byz-unica-head          # uma vez
  nr_byz_unica.py --base /tmp/opencode/byz-unica-head --atual . [--saida /tmp/opencode/byz-unica-nr]

Casos:
  A  SEM ataque (MVPComparison: ARQ, MCO2, TREE, AMBIFY, OFFSET, EARTH, DAGSPER; e ByzantineSweep f=0 por tipo)
     HEAD x atual -> arquivos IDENTICOS (mvp-results.csv e CSV por trial, byte a byte)
     EXCECAO D2 (ADR-0015): sweep f=0 de VOTING, POS e POSA (POW idem) ganha a coluna aditiva no fim e as 2 linhas '#' novas, e
     avgTraffic(HEAD) ~ avgTraffic(atual) + avgSelfDelivery_MB (tol. 2,5e-6; -1 = nao medido); o resto segue estrito.
     DAGSPER segue byte a byte. (Os casos MVPComparison de protocolos que separam a autoentrega mudam por D2 e sao cobertos
     por nr_autoentrega.py; este caso A continua os comparando de forma estrita, entao so e conforme com --base anterior a D2
     ou com --base que ja contem D2.)
  B  HOTSTUFF COM ataque (n=7, f=2; n=10, f=3): HEAD x atual -> IDENTICOS (o gerador unico reproduz o conjunto do HEAD)
  C  CONJUNTOS/CONTAGENS por protocolo SOB ATAQUE (so createNetwork, sem simular): tabela antes x depois, com a contagem
     efetiva e os ids; DIFERENCA ESPERADA e documentada (ARQ, Casper, Parlia, Nakamoto, DAGsper); nunca falha por diferir.
     Verifica no 'atual': contagem == f pedido em TODAS as linhas (o defeito f+1 do HEAD aparece no 'antes').
  D  ByzantineSweep f > floor((n-1)/3): atual falha ALTO sem a flag (nada gravado) e roda com a flag rotulando
     '# fora_da_faixa=true'; o HEAD roda sem avisar (e o defeito que a decisao 3 elimina).
Saida: 0 = tudo conforme; 1 = divergencia inesperada; 2 = erro de execucao.
"""
import argparse
import csv
import importlib.util
import os
import subprocess
import sys
import tempfile
from pathlib import Path

TMP_OPENCODE = Path(tempfile.gettempdir()) / "opencode"  # no Linux: /tmp/opencode
AQUI = Path(__file__).resolve().parent
JVM = ["-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2"]
HS = ["--hs-view-timeout=10.0", "--hs-max-future-view-horizon=1000", "--hs-batch-size=20", "--hs-tx-size=512",
      "--hs-individual-signature-size=64", "--hs-aggregated-signature-size=96", "--hs-aggregated-share-size=96",
      "--hs-message-overhead=10", "--hs-signature-mode=INDIVIDUAL", "--hs-leader-policy=ROTATING",
      "--hs-voter-encoding=BITMAP"]


def classpath(raiz: Path) -> str:
    cp = raiz / "target" / "cp.txt"
    if not cp.exists():
        sys.exit(f"ERRO: {cp} ausente")
    return f"{raiz / 'target' / 'classes'}{os.pathsep}{cp.read_text(encoding='utf-8').strip()}"


def java(raiz: Path, classe_ou_args, saida_log=None, timeout=900):
    cmd = ["java", *JVM, "-cp", classpath(raiz), *classe_ou_args]
    return subprocess.run(cmd, capture_output=True, text=True, cwd=str(raiz), timeout=timeout)


def arquivos(d: Path):
    return {p.relative_to(d).as_posix(): p.read_bytes() for p in sorted(d.rglob("*")) if p.is_file()}


def comparar(rotulo, a: Path, b: Path):
    fa, fb = arquivos(a), arquivos(b)
    if not fa:
        print(f"[{rotulo}] SEM_DADOS: {a} vazio")
        return False
    if fa.keys() != fb.keys():
        print(f"[{rotulo}] DIVERGENTE: arquivos diferentes: so em A={sorted(fa.keys() - fb.keys())} so em B={sorted(fb.keys() - fa.keys())}")
        return False
    ruins = [k for k in fa if fa[k] != fb[k]]
    if ruins:
        print(f"[{rotulo}] DIVERGENTE: bytes diferentes em {ruins}")
        return False
    print(f"[{rotulo}] IDENTICO: {len(fa)} arquivos, {sum(len(v) for v in fa.values())} bytes")
    return True


TIPOS_SWEEP_AUTOENTREGA = ("VOTING", "POS", "POSA", "POW")  # D2: tipos do ByzantineSweep que separam a autoentrega


def carregar_nr():
    spec = importlib.util.spec_from_file_location("nao_regressao", AQUI / "nao_regressao.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def ler_csv(path: Path):
    """(cabecalho, linhas) sem as linhas '#'; NAO descarta colunas 'hash' (aqui o hash tem de ser identico)."""
    linhas = [l for l in path.read_text(encoding="utf-8", errors="replace").splitlines() if l.strip() and not l.startswith("#")]
    rows = list(csv.reader(linhas))
    return (rows[0], rows[1:]) if rows else ([], [])


def comentarios(path: Path):
    return [l for l in path.read_text(encoding="utf-8", errors="replace").splitlines() if l.startswith("#")]


def comparar_sweep_autoentrega(rotulo, a: Path, b: Path):
    """D2 (ADR-0015), ByzantineSweep f=0 de VOTING/POS/POSA (POW idem): a = HEAD, b = atual. Mesmos arquivos. s.csv: linhas '#'
    do HEAD preservadas no inicio e EXATAMENTE 2 novas (semantica EXCLUEM; Seed/SimulatorVersion/Granularity); cabecalho = HEAD
    + avgSelfDelivery_MB,stdSelfDelivery_MB; so avgTraffic/stdTraffic podem diferir, sob conservacao (nao_regressao.
    reconciliar_autoentrega). CSV por trial: so a coluna Cb_autoentrega_B no fim e o trafego sob conservacao; o resto IDENTICO,
    hash incluido; comentario '#' do HEAD e prefixo do atual. A coluna aditiva TEM de existir (nao so ser tolerada)."""
    nr = carregar_nr()
    fa, fb = arquivos(a), arquivos(b)
    if not fa:
        print(f"[{rotulo}] SEM_DADOS: {a} vazio")
        return False
    if fa.keys() != fb.keys():
        print(f"[{rotulo}] DIVERGENTE: arquivos diferentes: so em A={sorted(fa.keys() - fb.keys())} so em B={sorted(fb.keys() - fa.keys())}")
        return False
    ruins, linhas = [], 0
    for k in fa:
        if not k.endswith(".csv"):
            if fa[k] != fb[k]:
                ruins.append(f"{k}: bytes diferentes")
            continue
        ha, hb = comentarios(a / k), comentarios(b / k)
        if k == "s.csv":
            novas = "\n".join(hb[len(ha):])
            if hb[:len(ha)] != ha or len(hb) != len(ha) + 2 or not all(t in novas for t in ("EXCLUEM", "Seed=", "SimulatorVersion=", "Granularity=")):
                ruins.append(f"{k}: linhas '#' (esperado: as do HEAD + 2 novas com EXCLUEM/Seed/SimulatorVersion/Granularity; HEAD={len(ha)} atual={len(hb)})")
        elif len(ha) != len(hb) or not all(y.startswith(x) for x, y in zip(ha, hb)):
            ruins.append(f"{k}: comentario '#' do HEAD nao e prefixo do atual")
        ca, ra = ler_csv(a / k)
        cb, rb = ler_csv(b / k)
        cb2, rb2, notas, aplicou = nr.reconciliar_autoentrega(k, ca, ra, cb, rb)
        linhas += len(ra)
        if not aplicou:
            ruins.append(f"{k}: sem a coluna aditiva no fim (cabecalho HEAD={len(ca)} col., atual={len(cb)} col., linhas {len(ra)} x {len(rb)})")
        elif notas:
            ruins.append("; ".join(notas))
        elif cb2 != ca or rb2 != ra:
            i = next((i for i, (x, y) in enumerate(zip(ra, rb2), 1) if x != y), 0)
            ruins.append(f"{k}: diferenca fora do trafego (cabecalho igual={cb2 == ca}, primeira linha {i})")
    if ruins:
        print(f"[{rotulo}] DIVERGENTE: {ruins}")
        return False
    print(f"[{rotulo}] CONFORME (D2): {len(fa)} arquivos, {linhas} linhas de dados; so a coluna aditiva no fim e o trafego sob conservacao diferem")
    return True


def com_semantica_n(extras):
    """Q32 A: --n-semantics e obrigatoria onde n difere (MCO2/TREE/AMBIFY, POS/POSA); a nao regressao fixa legacy
    quando o chamador nao pediu outra."""
    return list(extras) if any(e.startswith("--n-semantics=") for e in extras) else ["--n-semantics=legacy", *extras]


def mvp(raiz, saida: Path, proto, n, dur, semente, extras=()):
    extras = com_semantica_n(extras)
    if proto == "ARQ" and not any(e.startswith("--pbft-max-future-horizon=") for e in extras):
        extras = [*extras, "--pbft-max-future-horizon=1000"]  # PARIDADE: teto obrigatorio, sem padrao
    return java(raiz, ["jabs.example.MVPComparison", f"--protocols={proto}", f"--validators={n}", "--trials=1",
                       f"--duration={dur}", f"--seed={semente}", f"--output={saida}", *extras])


def sweep(raiz, saida: Path, tipo, n, maxbyz, ataque, semente, trials=1, dur=30, extras=()):
    saida.mkdir(parents=True, exist_ok=True)
    extras = com_semantica_n(extras)
    if tipo == "VOTING" and not any(e.startswith("--pbft-max-future-horizon=") for e in extras):
        extras = [*extras, "--pbft-max-future-horizon=1000"]  # PARIDADE: teto obrigatorio, sem padrao
    return java(raiz, ["jabs.example.ByzantineSweep", f"--type={tipo}", f"--validators={n}", f"--max-byz={maxbyz}",
                       f"--trials={trials}", f"--duration={dur}", f"--attack={ataque}", f"--seed={semente}",
                       f"--out={saida / 's.csv'}", *extras])


def exige_rc0(r, rotulo):
    if r.returncode != 0:
        print(f"[{rotulo}] ERRO rc={r.returncode}\n{r.stdout[-800:]}\n{r.stderr[-800:]}")
        sys.exit(2)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True)
    ap.add_argument("--atual", default=".")
    ap.add_argument("--saida", default=str(TMP_OPENCODE / "byz-unica-nr"))
    ap.add_argument("--semente", type=int, default=1)
    ap.add_argument("--so", default="ABCD", help="subconjunto de casos (ex.: AB)")
    a = ap.parse_args()
    base, atual, saida = Path(a.base).resolve(), Path(a.atual).resolve(), Path(a.saida)
    if saida.exists() and any(saida.iterdir()):
        sys.exit(f"ERRO: saida {saida} ja existe e nao esta vazia (nao sobrescrevo)")
    ok = True
    print(f"semente={a.semente} JVM={' '.join(JVM)}")

    if "A" in a.so:
        print("== A: SEM ataque, HEAD x atual (byte a byte)")
        # (protocolo, n, duracao): duracoes em que o protocolo FINALIZA blocos (MCO2 a 60 s finaliza 0)
        casos = [("ARQ", 7, 60), ("ARQ", 10, 60), ("MCO2", 10, 1200), ("TREE", 10, 3000), ("AMBIFY", 10, 600),
                 ("OFFSET", 40, 20000), ("EARTH", 40, 20000), ("DAGSPER", 10, 600)]
        for proto, n, dur in casos:
            for nome, raiz in (("base", base), ("atual", atual)):
                exige_rc0(mvp(raiz, saida / f"A-{proto}-{n}-{nome}", proto, n, dur, a.semente), f"A-{proto}-{nome}")
            ok &= comparar(f"A {proto} n={n} dur={dur}", saida / f"A-{proto}-{n}-base", saida / f"A-{proto}-{n}-atual")
        # com --n-semantics=nodes (a semantica do estudo, Q32): tambem identico sem ataque
        for proto, n, dur in (("MCO2", 10, 1200), ("AMBIFY", 10, 600)):
            for nome, raiz in (("base", base), ("atual", atual)):
                exige_rc0(mvp(raiz, saida / f"A-{proto}-nodes-{nome}", proto, n, dur, a.semente, ["--n-semantics=nodes"]),
                          f"A-{proto}-nodes-{nome}")
            ok &= comparar(f"A {proto} n={n} nodes", saida / f"A-{proto}-nodes-base", saida / f"A-{proto}-nodes-atual")
        # ByzantineSweep com f=0 (so o nivel sem bizantino), legacy: o CSV agregado e o de cada trial devem ser identicos
        # duracao por tipo em que o tipo FINALIZA blocos (POS: intervalo 15 s; POSA: 3 s; DAGSPER: 15 s)
        for tipo, n, ataque, dur in (("VOTING", 10, "EQUIVOCATION", 120), ("POS", 10, "WITHHOLD", 1200),
                                     ("POSA", 10, "WITHHOLD", 600), ("DAGSPER", 10, "WITHHOLD", 600)):
            for nome, raiz in (("base", base), ("atual", atual)):
                exige_rc0(sweep(raiz, saida / f"A-sw-{tipo}-{nome}", tipo, n, 0, ataque, a.semente, dur=dur),
                          f"A-sw-{tipo}-{nome}")
            if tipo in TIPOS_SWEEP_AUTOENTREGA:  # D2: so a coluna aditiva e o trafego sob conservacao; o resto estrito
                ok &= comparar_sweep_autoentrega(f"A sweep {tipo} n={n} f=0 (legacy, D2)", saida / f"A-sw-{tipo}-base", saida / f"A-sw-{tipo}-atual")
            else:  # DAGSPER: byte a byte, sem linhas novas
                ok &= comparar(f"A sweep {tipo} n={n} f=0 (legacy)", saida / f"A-sw-{tipo}-base", saida / f"A-sw-{tipo}-atual")

    if "B" in a.so:
        print("== B: HOTSTUFF COM ataque, HEAD x atual (o conjunto do HEAD e reproduzido pelo gerador unico)")
        for n, f in ((7, 2), (10, 3), (4, 1)):
            ex = [*HS, "--attack=EQUIVOCATION", f"--byzantine-count={f}"]
            for nome, raiz in (("base", base), ("atual", atual)):
                exige_rc0(mvp(raiz, saida / f"B-hs-{n}-{f}-{nome}", "HOTSTUFF", n, 30, a.semente, ex), f"B-hs-{n}-{nome}")
            ok &= comparar(f"B HOTSTUFF n={n} f={f}", saida / f"B-hs-{n}-{f}-base", saida / f"B-hs-{n}-{f}-atual")

    if "C" in a.so:
        print("== C: contagem e conjunto EFETIVOS sob ataque (sem simular), HEAD x atual")
        fonte = AQUI / "EvidenciaByzUnica.java"
        saida.mkdir(parents=True, exist_ok=True)
        txt = {}
        for nome, raiz, modo in (("antes(HEAD, pct)", base, "pct"), ("depois(atual, pct)", atual, "pct"),
                                 ("depois(atual, f inteiro)", atual, "sweep")):
            r = java(raiz, [str(fonte), modo])
            if r.returncode != 0:
                print(f"[C {nome}] ERRO rc={r.returncode}\n{r.stdout[-600:]}\n{r.stderr[-1200:]}")
                return 2
            linhas = [l for l in r.stdout.splitlines() if "f_pedido=" in l]
            (saida / f"C-{modo}-{'base' if raiz == base else 'atual'}.txt").write_text("\n".join(linhas) + "\n")
            txt[nome] = linhas
        antes, depois_pct, depois_f = txt["antes(HEAD, pct)"], txt["depois(atual, pct)"], txt["depois(atual, f inteiro)"]

        def contagem(l):
            return int(l.split("contagem=")[1].split()[0]), int(l.split("f_pedido=")[1].split()[0])

        defeito = [l for l in antes if contagem(l)[0] != contagem(l)[1]]
        print(f"   ANTES (HEAD): {len(defeito)} de {len(antes)} linhas com contagem != f pedido")
        for l in defeito[:8]:
            print("      " + l)
        ruins_pct = [l for l in depois_pct if contagem(l)[0] != contagem(l)[1]]
        ruins_f = [l for l in depois_f if contagem(l)[0] != contagem(l)[1]]
        # Rodada 3 (ADR-0015 decisao 1): o caminho por PERCENTUAL e LEGADO e usa o teto EXATO do double recebido, sem
        # tolerancia. Reconstruir f por pct = 100.0*f/n da f+1 onde o double excede o racional: isso e o contrato, nao
        # defeito, e por isso e so INFORMATIVO aqui. O criterio bloqueante e o caminho por f inteiro: 96 de 96.
        print(f"   DEPOIS (atual, percentual LEGADO, teto exato do double; informativo): {len(ruins_pct)} de {len(depois_pct)} "
              f"com contagem != f (todas com f+1: {all(contagem(l)[0] == contagem(l)[1] + 1 for l in ruins_pct)})")
        print(f"   DEPOIS (atual, f inteiro):  {len(ruins_f)} de {len(depois_f)} com contagem != f  [bloqueante: deve ser 0]")
        ok &= not ruins_f
        mudou = sum(1 for x, y in zip(antes, depois_f) if x != y)
        print(f"   conjunto/contagem mudou em {mudou} de {len(antes)} linhas (esperado: ARQ, Casper, Parlia, Nakamoto, DAGsper)")
        for x, y in list(zip(antes, depois_f))[:4]:
            print("      antes : " + x)
            print("      depois: " + y)

    if "D" in a.so:
        print("== D: ByzantineSweep f > floor((n-1)/3)")
        n, lim = 10, 3
        r = sweep(atual, saida / "D-sem", "VOTING", n, lim + 1, "EQUIVOCATION", a.semente, trials=1, dur=10)
        msg = r.stdout + r.stderr
        cond = (r.returncode != 0 and not (saida / "D-sem" / "s.csv").exists()
                and f"n={n} f={lim + 1}" in msg and "fora da faixa" in msg and f"floor((n-1)/3)={lim}" in msg)
        print(f"[D sem flag] {'OK' if cond else 'FALHOU'}: rc={r.returncode} csv_criado={(saida / 'D-sem' / 's.csv').exists()}")
        ok &= cond
        r = sweep(atual, saida / "D-com", "VOTING", n, lim + 1, "EQUIVOCATION", a.semente, trials=1, dur=10,
                  extras=["--allow-beyond-bft-threshold=true"])
        exige_rc0(r, "D-com")
        t = (saida / "D-com" / "s.csv").read_text(encoding="utf-8")
        achou = f"# fora_da_faixa=true n={n} byzantine_count={lim + 1} limite_bft={lim}" in t
        print(f"[D com flag] {'rotulo presente' if achou else 'ROTULO AUSENTE'}")
        ok &= achou
        r = sweep(base, saida / "D-head", "VOTING", n, lim + 1, "EQUIVOCATION", a.semente, trials=1, dur=10)
        print(f"[D HEAD sem flag] rc={r.returncode} (o HEAD roda f={lim + 1} > limite sem aviso: defeito que a decisao 3 elimina)")

    print("RESULTADO:", "CONFORME" if ok else "DIVERGENTE")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

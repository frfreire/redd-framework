#!/usr/bin/env python3
"""DRY-RUN dos comandos do V-ABC do Tendermint: NAO executa JVM; monta o comando de uma celula de cada bloco (va, vc, vb com e sem a flag de fora da
faixa) e confere MECANICAMENTE (i) que as flags --tm-* do comando sao exatamente as 12 que MVPComparison.parseTendermintConfigIfRequested exige (lidas do
codigo-fonte do build indicado, nao de memoria), (ii) que toda flag passada ao MVPComparison esta em KNOWN (senao ele a ignora com aviso), (iii) que
-XX:hashCode=2 esta no comando das duas JVMs e (iv) que --seed = semente - n*1000 e --run-seed = semente. Pre-requisito: celulas geradas em /tmp (gerar-celulas-tm.py).
Uso: dry-run-comandos-tm.py [--src /tmp/opencode/tm-dry/src/main/java/jabs/example/MVPComparison.java] [--celulas /tmp/opencode/tm-cel]
"""
import importlib.util
import json
import os
import re
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
SRC = Path(sys.argv[sys.argv.index("--src") + 1]) if "--src" in sys.argv else Path("/tmp/opencode/tm-dry/src/main/java/jabs/example/MVPComparison.java")
CEL = Path(sys.argv[sys.argv.index("--celulas") + 1]) if "--celulas" in sys.argv else Path("/tmp/opencode/tm-cel")
# so este processo (nao executa JVM): aponta rodar_celula.py para o build de dry-run, que o rodar_celula le de TM_BUILD_DRYRUN
os.environ["TM_BUILD_DRYRUN"] = str(Path(sys.argv[sys.argv.index("--build") + 1]) if "--build" in sys.argv else Path("/tmp/opencode/tm-dry"))

spec = importlib.util.spec_from_file_location("rc", AQUI / "rodar_celula.py")
rc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rc)

java = SRC.read_text(encoding="utf-8")
bloco = java[java.index("parseTendermintConfigIfRequested(java.util.List<String> protocols"):]
bloco = bloco[:bloco.index("return builder.build();")]
exigidas = sorted(set(re.findall(r'required(?:Double|Int|Long)\(cli, "(tm-[a-z-]+)"', bloco)))
known = set(re.findall(r'"([a-z0-9-]+)"', java[java.index("private static final String[] KNOWN"):java.index("/** ADR-0012: registro de carga")]))
ok = True


def chk(cond, msg):
    global ok
    print(("OK    " if cond else "FALHA ") + msg)
    ok = ok and cond


print("flags --tm-* exigidas pelo codigo:", len(exigidas), exigidas)
chk(len(exigidas) == 12, "12 flags --tm-* exigidas")
amostra = {"va": "tva_n31_s01", "vc": "tvc_n16_s01", "vb": "tvb_n4_f01_s01"}
amostra_fora = "tvb_n4_f02_s03"
for b, cid in list(amostra.items()) + [("vb(fora da faixa)", amostra_fora)]:
    d = CEL / ("vb" if b.startswith("vb") else b)
    cfg = json.loads((d / f"{cid}.json").read_text())
    n = cfg["n"]
    obs = rc.cmd_obs(cfg, "/tmp/x")
    print(f"\n[{b}] {cid}\n  OBS: {' '.join(obs)}")
    chk("-XX:hashCode=2" in obs, f"{cid}: hashCode=2 na JVM do observador")
    chk(f"--run-seed={cfg['semente']}" in obs, f"{cid}: --run-seed = semente")
    flags_obs = {a[2:].split("=")[0] for a in obs if a.startswith("--tm-")}
    chk(flags_obs == set(exigidas), f"{cid}: observador recebe as 12 flags --tm-* exigidas (sem sobra nem falta)")
    if b.startswith("vb"):
        chk(cfg["variante"]["attack"] == ("EQUIVOCATION" if cfg["byzantinos"] >= 1 else None), f"{cid}: ataque coerente com f")
        chk(("--allow-beyond-bft-threshold=true" in obs) == (cfg["byzantinos"] > (n - 1) // 3), f"{cid}: flag fora da faixa so em f > floor((n-1)/3)")
        continue
    mvp = rc.cmd_mvp(cfg, "/tmp/x")
    print(f"  MVP: {' '.join(mvp)}")
    chk("-XX:hashCode=2" in mvp, f"{cid}: hashCode=2 na JVM do MVPComparison")
    chk(f"--seed={cfg['semente'] - n * 1000}" in mvp, f"{cid}: --seed = semente - n*1000")
    flags_mvp = {a[2:].split("=")[0] for a in mvp if a.startswith("--")}
    chk(flags_mvp <= known, f"{cid}: toda flag do MVPComparison esta em KNOWN (faltam: {sorted(flags_mvp - known)})")
    chk({f for f in flags_mvp if f.startswith("tm-")} == set(exigidas), f"{cid}: MVPComparison recebe as 12 flags --tm-*")
print("\nTUDO OK" if ok else "\nHA FALHA")
sys.exit(0 if ok else 1)

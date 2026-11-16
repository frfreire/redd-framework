#!/usr/bin/env python3
"""DRY-RUN: monta (NAO executa) os comandos das duas JVMs para uma celula de cada bloco e confere flags. Nao simula.
Uso: dry-run-comandos.py <dir_celulas_gerado_em_/tmp>
"""
import importlib.util
import json
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("rc", AQUI / "rodar_celula.py")
rc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rc)
rc.DEST = Path("/tmp/opencode/val-hs-fumaca")   # build de TESTE (so compilado); o oficial so existe na etapa 2
for pasta in sys.argv[1:]:
    for nome in ("manifesto.json",):
        man = json.loads((Path(pasta) / nome).read_text())
    for cid in (man["celulas"][0], man["celulas"][-1]):
        cfg = json.loads((Path(pasta) / f"{cid}.json").read_text())
        for nomej, fn in (("mvp", rc.cmd_mvp), ("obs", rc.cmd_obs)):
            cmd = fn(cfg, "/tmp/opencode/x")
            assert "-XX:hashCode=2" in cmd, "falta -XX:hashCode=2"
            assert f"--hs-signature-mode={cfg['variante']['signature_mode']}" in cmd
            assert f"--hs-view-timeout={cfg['variante']['view_timeout_s']}" in cmd
            assert (any(c.startswith("--load-record=") for c in cmd)) == (nomej == "mvp" and cfg.get("load_record_t0") is not None)
            print(cid, nomej, "OK")
            if nomej == "mvp":
                print("   ", " ".join(cmd[:12]), "...", " ".join(c for c in cmd if c.startswith(("--seed", "--load", "--duration", "--hs-leader", "--hs-signature"))))

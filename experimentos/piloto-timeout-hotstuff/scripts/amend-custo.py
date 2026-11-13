#!/usr/bin/env python3
"""Custo (tempo de parede, RSS) das celulas de medicao L=100 ja rodadas. Somente leitura. Biblioteca padrao."""
import json, glob, statistics as st, collections, sys
base = sys.argv[1]
d = collections.defaultdict(list)
for p in glob.glob(f"{base}/med_*_L100_*/resultado.json"):
    r = json.load(open(p)); t = r["id"].split("_")
    d[(t[1], t[3])].append((r["tempo_parede_mvp_s"], r["tempo_parede_observador_s"], r["max_rss_mb"]))
for k, v in sorted(d.items()):
    print(k, len(v), "mvp_s %.0f" % st.mean(x[0] for x in v), "obs_s %.0f" % st.mean(x[1] for x in v), "rss_max_mb %.0f" % max(x[2] for x in v))

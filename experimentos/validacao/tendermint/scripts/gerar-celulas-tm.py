#!/usr/bin/env python3
"""Gera (por script, nunca a mao) as celulas dos blocos va, vc e vb do Tendermint e a LISTA DE ESPERADAS (RASCUNHO; nada simulado).

Nao usa scripts/jabs/gerar-grid.py: ele descarta em silencio todo f com 3f>=n (exatamente o ponto f*=floor((n-1)/3)+1 de V-B) e nao carrega
os parametros --tm-*. A semente segue a MESMA convencao do gerar-grid: semente = semente_base + n*1000 + replicacao; o ObservadorTm usa
--run-seed = semente e o MVPComparison (so V-A e V-C) usa --seed = semente - n*1000 (o trial 0 soma n*1000 de volta: runSeed = baseSeed + n*1000 + trial).
Bases (disjuntas de TODAS as ja usadas, conferido por checar-sementes-tm.py): va 620345; vb 820345; vc 920345.

Uso:
  gerar-celulas-tm.py esperadas                          grava celulas-esperadas.json (va, vc, vb)
  gerar-celulas-tm.py gerar <va|vc|vb> --saida DIR --simulador-commit HASH
  gerar-celulas-tm.py conferir-manifesto <va|vc|vb> DIR/manifesto.json
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
RAIZ = VAL.parents[2]
CFG = {b: VAL / "config" / f"fatores-{b}.json" for b in ("va", "vc", "vb")}
SAIDA_ESP = VAL / "celulas-esperadas.json"
FLAG_FORA = "--allow-beyond-bft-threshold=true"


def fmax(n):
    return (n - 1) // 3


def carregar(b):
    return json.loads(CFG[b].read_text(encoding="utf-8"))


def esperadas_bloco(b):
    f = carregar(b)
    cel = []
    for n in f["n"]:
        fs = list(range(0, fmax(n) + 2)) if b == "vb" else [0]
        for byz in fs:
            for r in range(1, int(f["sementes"]) + 1):
                cid = (f"{f['prefixo']}_n{n}_f{byz:02d}_s{r:02d}" if b == "vb" else f"{f['prefixo']}_n{n}_s{r:02d}")
                c = {"id": cid, "bloco": b, "n": n, "f": byz, "replicacao": r, "semente": f["semente_base"] + n * 1000 + r,
                     "duracao_s": f["duracao_s"], "L": f["L"], "timeouts_s": f["tm"]["timeouts_s"]}
                if b == "vb":
                    c["fora_da_faixa"] = byz > fmax(n)
                cel.append(c)
    return cel


def todas():
    return {b: esperadas_bloco(b) for b in ("va", "vc", "vb")}


def gravar_esperadas():
    t = todas()
    doc = {"n_por_bloco": {b: len(v) for b, v in t.items()}, "n_total": sum(len(v) for v in t.values()),
           "observacao": "RASCUNHO da etapa 1; vb listado para pre-registro; NAO executar antes do pino e do commit do pre-registro", "celulas": t}
    SAIDA_ESP.write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"esperadas: {doc['n_por_bloco']} total={doc['n_total']} -> {SAIDA_ESP.name}")


def cfg_de(b, f, c, commit, agora):
    byz = c["f"]
    t = f["tm"]["timeouts_s"]
    return {"id": c["id"], "protocolo": "TENDERMINT", "n": c["n"], "carga": "estacionaria", "byzantinos": byz,
            "semente": c["semente"], "replicacao": c["replicacao"], "duracao_s": c["duracao_s"], "simulador_commit": commit,
            "gerado_em": agora, "bloco": b, "grupo": f"t{b}_n{c['n']}" + (f"_f{byz:02d}" if b == "vb" else ""),
            "jvm_flags": f["jvm_flags"], "fatores_origem": str(CFG[b].relative_to(RAIZ)),
            "variante": {"L": f["L"], "s_tx": f["tm"]["s_tx"], "sig_voto_B": f["tm"]["assinatura_voto_B"],
                         "sig_proponente_B": f["tm"]["assinatura_proponente_B"], "overhead_msg_B": f["tm"]["overhead_msg_B"],
                         "tm_propose": t["propose"], "tm_propose_delta": t["propose_delta"], "tm_prevote": t["prevote"],
                         "tm_prevote_delta": t["prevote_delta"], "tm_precommit": t["precommit"],
                         "tm_precommit_delta": t["precommit_delta"],
                         "attack": f["attack"] if byz >= 1 else None},
            "t0_transiente_s": f.get("t0_transiente_s"),
            "fora_da_faixa": byz > fmax(c["n"]), "flags_extras": [FLAG_FORA] if byz > fmax(c["n"]) else []}


def gerar(b, saida, commit):
    f = carregar(b)
    saida = Path(saida)
    saida.mkdir(parents=True, exist_ok=True)
    agora = datetime.now(timezone.utc).isoformat(timespec="seconds")
    ids = []
    for c in esperadas_bloco(b):
        (saida / f"{c['id']}.json").write_text(json.dumps(cfg_de(b, f, c, commit, agora), indent=1, ensure_ascii=False))
        ids.append(c["id"])
    (saida / "manifesto.json").write_text(json.dumps({"n_celulas": len(ids), "celulas": ids, "fatores": f}, indent=1, ensure_ascii=False))
    print(f"{len(ids)} celula(s) de {b} em {saida}/")


def conferir(b, man_path):
    esp = json.loads(SAIDA_ESP.read_text(encoding="utf-8"))["celulas"][b]
    man = json.loads(Path(man_path).read_text(encoding="utf-8"))
    ok = man["celulas"] == [c["id"] for c in esp] and man["n_celulas"] == len(esp)
    d = Path(man_path).parent
    if ok:
        for c in esp:
            g = json.loads((d / f"{c['id']}.json").read_text())
            esp_att = "EQUIVOCATION" if (b == "vb" and c["f"] >= 1) else None
            esp_fl = [FLAG_FORA] if c["f"] > fmax(c["n"]) else []
            if ((g["semente"], g["n"], g["byzantinos"], g["duracao_s"], g["variante"]["attack"], g["flags_extras"], "-XX:hashCode=2" in g["jvm_flags"])
                    != (c["semente"], c["n"], c["f"], c["duracao_s"], esp_att, esp_fl, True)):
                print("diverge em", c["id"])
                ok = False
                break
    print(f"manifesto == especificacao pre-registrada ({len(esp)} celulas)" if ok else "DIVERGE da especificacao pre-registrada")
    sys.exit(0 if ok else 1)


def main():
    a = sys.argv[1:]
    if a[:1] == ["esperadas"]:
        gravar_esperadas()
    elif a[:1] == ["gerar"] and a[1] in CFG and "--saida" in a:
        gerar(a[1], a[a.index("--saida") + 1], a[a.index("--simulador-commit") + 1] if "--simulador-commit" in a else "NAO_INFORMADO")
    elif a[:1] == ["conferir-manifesto"] and a[1] in CFG:
        conferir(a[1], a[2])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()

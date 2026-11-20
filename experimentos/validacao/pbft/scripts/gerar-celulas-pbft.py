#!/usr/bin/env python3
"""Gera (por script, nunca a mao) as celulas do bloco va do V-ABC do PBFT puro e a LISTA DE ESPERADAS. RASCUNHO: nada simulado aqui.
Semente = semente_base + n*1000 + replicacao; o ObservadorPbft usa --run-seed = semente e o MVPComparison usa --seed = semente - n*1000
(o trial 0 soma n*1000 de volta: runSeed = baseSeed + n*1000 + trial).

Uso:
  gerar-celulas-pbft.py esperadas                          grava celulas-esperadas.json
  gerar-celulas-pbft.py gerar va --saida DIR --simulador-commit HASH
  gerar-celulas-pbft.py conferir-manifesto va DIR/manifesto.json
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
RAIZ = VAL.parents[2]
CFG = {"va": VAL / "config" / "fatores-va.json"}
SAIDA_ESP = VAL / "celulas-esperadas.json"


def carregar(b):
    return json.loads(CFG[b].read_text(encoding="utf-8"))


def esperadas_bloco(b):
    f = carregar(b)
    cel = []
    for n in f["n"]:
        for r in range(1, int(f["sementes"]) + 1):
            cel.append({"id": f"{f['prefixo']}_n{n}_s{r:02d}", "bloco": b, "n": n, "f": 0, "replicacao": r,
                        "semente": f["semente_base"] + n * 1000 + r, "duracao_s": f["duracao_s"]})
    return cel


def gravar_esperadas():
    t = {b: esperadas_bloco(b) for b in CFG}
    doc = {"n_por_bloco": {b: len(v) for b, v in t.items()}, "n_total": sum(len(v) for v in t.values()),
           "observacao": "RASCUNHO; NAO executar antes do pino, do commit e do hash do pre-registro", "celulas": t}
    SAIDA_ESP.write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"esperadas: {doc['n_por_bloco']} total={doc['n_total']} -> {SAIDA_ESP.name}")


def cfg_de(b, f, c, commit, agora):
    p = f["pbft"]
    return {"id": c["id"], "protocolo": "PBFT", "n": c["n"], "carga": "sem_gerador_de_transacoes", "byzantinos": 0,
            "semente": c["semente"], "replicacao": c["replicacao"], "duracao_s": c["duracao_s"], "simulador_commit": commit,
            "gerado_em": agora, "bloco": b, "grupo": f"p{b}_n{c['n']}", "jvm_flags": f["jvm_flags"],
            "fatores_origem": str(CFG[b].relative_to(RAIZ)).replace("\\", "/"),
            "variante": {"max_future_horizon": p["max_future_horizon"], "leader_policy": p["leader_policy"], "attack": None},
            "min_alturas_regime": f["min_alturas_regime"], "flags_extras": []}


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
            if ((g["semente"], g["n"], g["byzantinos"], g["duracao_s"], g["variante"]["attack"], g["flags_extras"], "-XX:hashCode=2" in g["jvm_flags"],
                 g["variante"]["leader_policy"], g["variante"]["max_future_horizon"])
                    != (c["semente"], c["n"], 0, c["duracao_s"], None, [], True, "STABLE", 1000)):
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

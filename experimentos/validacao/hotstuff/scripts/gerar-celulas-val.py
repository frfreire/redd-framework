#!/usr/bin/env python3
"""Gera (por script, nunca a mao) as celulas dos blocos va, vano e vc e a LISTA DE ESPERADAS de todos (va, vano, vb, vc).

Usa scripts/jabs/gerar-grid.py (HOTSTUFF, 1 carga, f declarado) por grupo, como o piloto do timeout.
  semente = semente_base + n*1000 + replicacao   (gerar-grid.py). MVPComparison recebe --seed = semente - n*1000.
  A MESMA semente vale para os dois modos de assinatura (numeros aleatorios comuns, pareado) e, em V-B, para todo f.

Uso:
  gerar-celulas-val.py esperadas                       grava celulas-esperadas.json (va, vano, vb; sem gerar celula)
  gerar-celulas-val.py gerar <va|vano|vc> --saida DIR [--simulador-commit HASH]
  gerar-celulas-val.py conferir-manifesto <va|vano|vc> <manifesto.json>
V-B (vb) NUNCA e gerado aqui: o ponto f=floor((n-1)/3)+1 exige o modo 'fora da faixa' (tarefa BYZ-fora-da-faixa-hotstuff),
e gerar-grid.py DESCARTA em silencio todo f com 3f>=n (exatamente esse ponto). Ver PREREGISTRO.md.
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
RAIZ = VAL.parents[2]
GRID = RAIZ / "scripts" / "jabs" / "gerar-grid.py"
CFG = {b: VAL / "config" / f"fatores-{b.replace('vano', 'va-no')}.json" for b in ("va", "vano", "vb", "vc")}
SAIDA_ESP = VAL / "celulas-esperadas.json"


def fmax(n):
    return (n - 1) // 3


def carregar(b):
    return json.loads(CFG[b].read_text(encoding="utf-8"))


def lista_L(f):
    return f["L"] if isinstance(f["L"], list) else [f["L"]]


def timeout(f, L, n):
    if "view_timeout_por_L_n" in f:
        return f["view_timeout_por_L_n"][str(L)][str(n)]
    return f["view_timeout_por_n"][str(n)]


def esperadas_bloco(b):
    f = carregar(b)
    cel = []
    for L, modo, n in [(L, m, n) for L in lista_L(f) for m in f["modos"] for n in f["n"]]:
        if True:
            fs = [0] if b != "vb" else list(range(0, fmax(n) + 2))
            for byz in fs:
                for r in range(1, int(f["sementes"]) + 1):
                    cid = (f"{f['prefixo']}_{modo.lower()}_n{n}_f{byz:02d}_s{r:02d}" if b == "vb"
                           else f"{f['prefixo']}_{modo.lower()}_L{L}_n{n}_s{r:02d}" if b == "vc"
                           else f"{f['prefixo']}_{modo.lower()}_n{n}_s{r:02d}")
                    c = {"id": cid, "bloco": b, "modo": modo, "n": n, "f": byz, "replicacao": r,
                         "semente": f["semente_base"] + n * 1000 + r, "duracao_s": f["duracao_s"],
                         "view_timeout_s": timeout(f, L, n), "politica": f["politica"], "L": L}
                    if b == "vb":
                        c["fora_da_faixa"] = byz > fmax(n)
                    cel.append(c)
    return cel


def todas():
    out = {}
    for b in ("va", "vano", "vb", "vc"):
        out[b] = esperadas_bloco(b)
    return out


def gravar_esperadas():
    t = todas()
    doc = {"n_por_bloco": {b: len(v) for b, v in t.items()}, "n_total": sum(len(v) for v in t.values()),
           "observacao": "vb listado so para pre-registro; NAO executar (aguarda BYZ-fora-da-faixa-hotstuff)",
           "celulas": t}
    SAIDA_ESP.write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"esperadas: {doc['n_por_bloco']} total={doc['n_total']} -> {SAIDA_ESP.name}")


def gerar(b, saida, commit):
    f = carregar(b)
    saida = Path(saida)
    saida.mkdir(parents=True, exist_ok=True)
    ids = []
    for L, modo, n in [(L, m, n) for L in lista_L(f) for m in f["modos"] for n in f["n"]]:
        if True:
            with tempfile.TemporaryDirectory() as td:
                fat = Path(td) / "fatores.json"
                fat.write_text(json.dumps({"protocolos": ["HOTSTUFF"], "n": [n], "carga": ["estacionaria"], "f": [0],
                                           "sementes": f["sementes"], "semente_base": f["semente_base"],
                                           "duracao_s": f["duracao_s"]}))
                out = Path(td) / "g"
                r = subprocess.run([sys.executable, str(GRID), str(fat), "--saida", str(out),
                                    "--simulador-commit", commit], capture_output=True, text=True)
                if r.returncode != 0:
                    sys.exit(f"gerar-grid.py falhou: {r.stderr}{r.stdout}")
                man = json.loads((out / "manifesto.json").read_text())
                if man["n_celulas"] != f["sementes"]:
                    sys.exit(f"gerar-grid.py omitiu celulas (n={n}): {man['n_celulas']} != {f['sementes']}")
                for nome in man["celulas"]:
                    cfg = json.loads((out / f"{nome}.json").read_text())
                    rr = cfg["replicacao"]
                    cid = (f"{f['prefixo']}_{modo.lower()}_L{L}_n{n}_s{rr:02d}" if b == "vc"
                           else f"{f['prefixo']}_{modo.lower()}_n{n}_s{rr:02d}")
                    v = dict(f["variante_fixa"])
                    v.update({"view_timeout_s": timeout(f, L, n), "L": L,
                              "leader_policy": f["politica"], "attack": None, "signature_mode": modo})
                    cfg.update({"id": cid, "variante": v, "grupo": f"{b}_{modo}_L{L}_n{n}", "bloco": b,
                                "jvm_flags": f["jvm_flags"], "load_record_t0": f.get("load_record_t0"),
                                "fatores_origem": str(CFG[b].relative_to(RAIZ))})
                    (saida / f"{cid}.json").write_text(json.dumps(cfg, indent=1, ensure_ascii=False))
                    ids.append(cid)
    (saida / "manifesto.json").write_text(json.dumps({"n_celulas": len(ids), "celulas": ids, "fatores": f},
                                                     indent=1, ensure_ascii=False))
    print(f"{len(ids)} celula(s) em {saida}/ (manifesto.json lista todas as esperadas)")


def conferir(b, man_path):
    esp = json.loads(SAIDA_ESP.read_text(encoding="utf-8"))
    man = json.loads(Path(man_path).read_text(encoding="utf-8"))
    ok = man["celulas"] == [c["id"] for c in esp["celulas"][b]] and man["n_celulas"] == len(esp["celulas"][b])
    # sementes e T dos cfg gerados tem de coincidir com a lista pre-registrada
    d = Path(man_path).parent
    if ok:
        for c in esp["celulas"][b]:
            g = json.loads((d / f"{c['id']}.json").read_text())
            if (g["semente"], g["n"], g["duracao_s"], g["variante"]["view_timeout_s"], g["variante"]["signature_mode"],
                    g["variante"]["L"]) != (c["semente"], c["n"], c["duracao_s"], c["view_timeout_s"], c["modo"], c["L"]):
                ok = False
                break
    print("manifesto == especificacao pre-registrada" if ok else "DIVERGE da especificacao pre-registrada")
    sys.exit(0 if ok else 1)


def main():
    a = sys.argv[1:]
    if a[:1] == ["esperadas"]:
        gravar_esperadas()
    elif a[:1] == ["gerar"] and a[1] in ("va", "vano", "vc"):
        saida = a[a.index("--saida") + 1]
        commit = a[a.index("--simulador-commit") + 1] if "--simulador-commit" in a else "NAO_INFORMADO"
        gerar(a[1], saida, commit)
    elif a[:1] == ["conferir-manifesto"] and a[1] in ("va", "vano", "vc"):
        conferir(a[1], a[2])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()

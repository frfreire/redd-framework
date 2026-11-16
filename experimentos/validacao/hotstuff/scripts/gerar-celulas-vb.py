#!/usr/bin/env python3
"""V-B (EMENDA E7, 16/11/2026): gera por script as 180 celulas de V-B e confere contra a lista pre-registrada.
NAO simula. Arquivo NOVO: nao altera gerar-celulas-val.py (hash da Emenda 1b, usado nos dados de V-A/V-C ja commitados).

Por que nao usa gerar-grid.py: ele DESCARTA em silencio todo f com 3f >= n (exatamente o ponto f* = f_max+1). A lista de celulas vem
da MESMA funcao que gerou celulas-esperadas.json (gerar-celulas-val.esperadas_bloco('vb'), importada, nao copiada).

Uso:
  gerar-celulas-vb.py gerar --saida DIR --simulador-commit HASH     (exige HASH = 76932b925719a2b83d6165b5a84d30f5501896ee)
  gerar-celulas-vb.py conferir-manifesto DIR/manifesto.json
Regras do cfg (consumidas por rodar_celula.py, inalterado):
  f = 0  -> attack None (controle honesto; MVPComparison recusa --byzantine-count=0 com ataque)
  f >= 1 -> attack EQUIVOCATION, byzantinos = f
  f > floor((n-1)/3) -> flags_extras = ["--allow-beyond-bft-threshold=true"] (so nos pontos f = f*; dentro da faixa a flag NAO e passada)
"""
import importlib.util
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
COMMIT = "76932b925719a2b83d6165b5a84d30f5501896ee"
FLAG = "--allow-beyond-bft-threshold=true"

spec = importlib.util.spec_from_file_location("gcv", VAL / "scripts" / "gerar-celulas-val.py")
gcv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gcv)


def fmax(n):
    return (n - 1) // 3


def gerar(saida, commit):
    if commit != COMMIT:
        sys.exit(f"ERRO: simulador-commit tem de ser {COMMIT} (pre-registro, E5/E7); recebi {commit}")
    f = gcv.carregar("vb")
    esp = gcv.esperadas_bloco("vb")
    saida = Path(saida)
    saida.mkdir(parents=True, exist_ok=True)
    agora = datetime.now(timezone.utc).isoformat(timespec="seconds")
    ids = []
    for c in esp:
        n, byz = c["n"], c["f"]
        v = dict(f["variante_fixa"])
        v.update({"view_timeout_s": c["view_timeout_s"], "L": c["L"], "leader_policy": f["politica"],
                  "attack": f["attack"] if byz >= 1 else None, "signature_mode": c["modo"]})
        cfg = {"id": c["id"], "protocolo": "HOTSTUFF", "n": n, "carga": "estacionaria", "byzantinos": byz,
               "semente": c["semente"], "replicacao": c["replicacao"], "duracao_s": c["duracao_s"],
               "simulador_commit": commit, "gerado_em": agora, "variante": v,
               "grupo": f"vb_{c['modo']}_n{n}_f{byz:02d}", "bloco": "vb", "jvm_flags": f["jvm_flags"],
               "load_record_t0": f.get("load_record_t0"), "fatores_origem": str(gcv.CFG["vb"].relative_to(gcv.RAIZ)),
               "fora_da_faixa": byz > fmax(n), "flags_extras": [FLAG] if byz > fmax(n) else []}
        (saida / f"{c['id']}.json").write_text(json.dumps(cfg, indent=1, ensure_ascii=False))
        ids.append(c["id"])
    (saida / "manifesto.json").write_text(json.dumps({"n_celulas": len(ids), "celulas": ids, "fatores": f},
                                                     indent=1, ensure_ascii=False))
    print(f"{len(ids)} celula(s) de V-B em {saida}/")


def conferir(man_path):
    esp = json.loads(gcv.SAIDA_ESP.read_text(encoding="utf-8"))["celulas"]["vb"]
    man = json.loads(Path(man_path).read_text(encoding="utf-8"))
    ok = man["celulas"] == [c["id"] for c in esp] and man["n_celulas"] == len(esp) == 180
    d = Path(man_path).parent
    if ok:
        for c in esp:
            g = json.loads((d / f"{c['id']}.json").read_text())
            esperado_att = "EQUIVOCATION" if c["f"] >= 1 else None
            esperado_fl = [FLAG] if c["f"] > fmax(c["n"]) else []
            if ((g["semente"], g["n"], g["byzantinos"], g["duracao_s"], g["variante"]["view_timeout_s"],
                 g["variante"]["signature_mode"], g["variante"]["L"], g["variante"]["attack"], g["flags_extras"],
                 g["simulador_commit"], "-XX:hashCode=2" in g["jvm_flags"])
                    != (c["semente"], c["n"], c["f"], c["duracao_s"], c["view_timeout_s"], c["modo"], c["L"],
                        esperado_att, esperado_fl, COMMIT, True)):
                print("diverge em", c["id"])
                ok = False
                break
    print("manifesto == especificacao pre-registrada (180 celulas)" if ok else "DIVERGE da especificacao pre-registrada")
    sys.exit(0 if ok else 1)


def main():
    a = sys.argv[1:]
    if a[:1] == ["gerar"] and "--saida" in a and "--simulador-commit" in a:
        gerar(a[a.index("--saida") + 1], a[a.index("--simulador-commit") + 1])
    elif a[:1] == ["conferir-manifesto"] and len(a) == 2:
        conferir(a[1])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Registro de rodadas do Gauntlet Loop e aplicacao do orcamento de iteracoes.

O orcamento e os gates sao aplicados AQUI, nao pela memoria do modelo.

Gravar uma rodada:
  registrar-rodada.py --loop loop1-chaincode --tarefa emitir-credito \
      --papel critic --veredicto REJEITADO_COM_GAP --criterio 5 --gap "texto"

Verificar gate (antes de instalar chaincode / rodar baterias):
  registrar-rodada.py --verificar-gate loop1-chaincode [--tarefa ID]

Resumo:
  registrar-rodada.py --status [--loop ID]

Codigos de saida:
  0   ok / gate liberado
  1   uso incorreto ou gate nao liberado (ha tarefa sem fechamento)
  20  orcamento de iteracoes esgotado: PARAR e pedir decisao humana
  30  gate sem nenhum registro (nada foi revisado)
"""
import argparse
import datetime
import json
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
DIR = RAIZ / ".ia" / "rodadas"
CFG = RAIZ / "gauntlet-loop.config.json"

PAPEIS = {"builder", "critic", "arbiter", "humano"}
VEREDICTOS = {
    "ENTREGUE", "APROVADO", "REJEITADO_COM_GAP",
    "GAP_BLOQUEANTE", "GAP_NAO_BLOQUEANTE",
    "CONFIRMADO", "DEFEITO_ENCONTRADO", "INCONCLUSIVO", "PENDENTE_RETESTE",
    "PENDENTE_REVISAO_HUMANA", "DECISAO_HUMANA_ACEITA",
}
FECHAM = {"APROVADO", "GAP_NAO_BLOQUEANTE", "CONFIRMADO", "DECISAO_HUMANA_ACEITA"}
LOOPS_COM_REENVIO = {"loop1-chaincode", "loop2-jabs-codigo"}


def carregar_cfg():
    try:
        return json.loads(CFG.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        sys.exit(f"ERRO: nao consegui ler {CFG}: {e}")


def loops_validos(cfg):
    return {l["id"]: l for l in cfg["loops"]}


def arquivo(loop):
    return DIR / f"{loop}.jsonl"


def ler(loop):
    f = arquivo(loop)
    if not f.exists():
        return []
    regs = []
    for i, linha in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if not linha.strip():
            continue
        try:
            regs.append(json.loads(linha))
        except json.JSONDecodeError:
            sys.exit(f"ERRO: {f}:{i} nao e JSON valido (trilha corrompida)")
    return regs


def estado_da_tarefa(regs, tarefa):
    """Devolve (ultimo_veredicto_decisivo, rejeicoes_desde_ultimo_fechamento)."""
    rej = 0
    ultimo = None
    for r in regs:
        if r["tarefa"] != tarefa:
            continue
        v = r["veredicto"]
        if v in FECHAM:
            rej = 0
            ultimo = v
        elif v == "REJEITADO_COM_GAP" and r["papel"] == "critic":
            rej += 1
            ultimo = v
        elif r["papel"] in ("critic", "arbiter", "humano"):
            ultimo = v
        elif v == "ENTREGUE":
            ultimo = v if ultimo in (None, "ENTREGUE") else ultimo
    return ultimo, rej


def tarefas(regs):
    vistos = []
    for r in regs:
        if r["tarefa"] not in vistos:
            vistos.append(r["tarefa"])
    return vistos


def gravar(args, cfg):
    loops = loops_validos(cfg)
    if args.loop not in loops:
        sys.exit(f"ERRO: loop '{args.loop}' inexistente. Validos: {sorted(loops)}")
    if not (args.tarefa and args.papel and args.veredicto):
        sys.exit("ERRO: --tarefa, --papel e --veredicto sao obrigatorios para gravar")
    if args.papel not in PAPEIS:
        sys.exit(f"ERRO: --papel deve ser um de {sorted(PAPEIS)}")
    if args.veredicto not in VEREDICTOS:
        sys.exit(f"ERRO: --veredicto deve ser um de {sorted(VEREDICTOS)}")
    if args.veredicto == "REJEITADO_COM_GAP" and not (args.gap and args.criterio):
        sys.exit("ERRO: REJEITADO_COM_GAP exige --gap e --criterio (um unico gap)")
    if args.criterio is not None:
        ordens = {b["ordem"] for b in loops[args.loop].get("bar", [])}
        if ordens and args.criterio not in ordens:
            sys.exit(f"ERRO: criterio {args.criterio} nao existe na bar de {args.loop} ({sorted(ordens)})")

    DIR.mkdir(parents=True, exist_ok=True)
    reg = {
        "em": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "loop": args.loop,
        "tarefa": args.tarefa,
        "papel": args.papel,
        "veredicto": args.veredicto,
        "criterio": args.criterio,
        "gap": args.gap,
        "versao": args.versao,
        "nota": args.nota,
    }
    with arquivo(args.loop).open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(reg, ensure_ascii=False) + "\n")

    if args.loop in LOOPS_COM_REENVIO:
        orcamento = loops[args.loop].get("orcamentoDeIteracoes", 3)
        _, rej = estado_da_tarefa(ler(args.loop), args.tarefa)
        restante = orcamento - rej
        if args.veredicto == "REJEITADO_COM_GAP":
            if rej >= orcamento:
                print(f"ORCAMENTO ESGOTADO: {rej}/{orcamento} rejeicoes na tarefa '{args.tarefa}'.")
                print("PARAR e solicitar decisao humana. Nao ha quarta rodada.")
                return 20
            print(f"registrado. rejeicoes {rej}/{orcamento}; restam {restante} rodada(s) de reenvio.")
            return 0
    print("registrado.")
    return 0


def gate(args, cfg):
    loops = loops_validos(cfg)
    if args.verificar_gate not in loops:
        sys.exit(f"ERRO: loop '{args.verificar_gate}' inexistente")
    regs = ler(args.verificar_gate)
    if not regs:
        print(f"GATE FECHADO: nenhuma rodada registrada em {args.verificar_gate}. Nada foi revisado.")
        return 30
    alvo = [args.tarefa] if args.tarefa else tarefas(regs)
    abertas = []
    for t in alvo:
        ult, _ = estado_da_tarefa(regs, t)
        if ult not in FECHAM:
            abertas.append((t, ult))
    if abertas:
        print(f"GATE FECHADO em {args.verificar_gate}. Tarefas sem fechamento:")
        for t, u in abertas:
            print(f"  - {t}: ultimo estado = {u}")
        return 1
    print(f"GATE LIBERADO em {args.verificar_gate}: {len(alvo)} tarefa(s) fechada(s).")
    return 0


def status(args, cfg):
    loops = loops_validos(cfg)
    ids = [args.loop] if args.loop else list(loops)
    for lid in ids:
        if lid not in loops:
            sys.exit(f"ERRO: loop '{lid}' inexistente")
        regs = ler(lid)
        orc = loops[lid].get("orcamentoDeIteracoes")
        print(f"== {lid}" + (f" (orcamento {orc})" if orc else ""))
        if not regs:
            print("   sem rodadas")
            continue
        for t in tarefas(regs):
            ult, rej = estado_da_tarefa(regs, t)
            print(f"   {t:40s} {ult or '-':22s} rejeicoes={rej}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--loop")
    ap.add_argument("--tarefa")
    ap.add_argument("--papel")
    ap.add_argument("--veredicto")
    ap.add_argument("--criterio", type=int)
    ap.add_argument("--gap")
    ap.add_argument("--versao", help="commit/tag do alvo revisado")
    ap.add_argument("--nota")
    ap.add_argument("--verificar-gate", metavar="LOOP")
    ap.add_argument("--status", action="store_true")
    args = ap.parse_args()
    cfg = carregar_cfg()
    if args.verificar_gate:
        return gate(args, cfg)
    if args.status:
        return status(args, cfg)
    if not args.loop:
        ap.print_help()
        return 1
    return gravar(args, cfg)


if __name__ == "__main__":
    sys.exit(main())

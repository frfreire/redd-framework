#!/usr/bin/env python3
"""Valida a coerencia do enxame. Sai com 1 se houver erro.

Confere:
  - JSON valido de opencode.json, enxame.config.json, gauntlet-loop.config.json
  - cada agente de enxame.config.json tem arquivo em .opencode/agents/ e skill em .agents/skills/
  - modelo declarado no enxame == modelo do frontmatter do agente; modelo existe no provider
    (provider `anthropic` e nativo do OpenCode: anthropic/claude-* vale sem lista `models`;
    provider com `models` explicito exige o modelo listado; prefixo desconhecido falha)
  - skill carregada pelo agente (texto "skill `x`") existe
  - descricao e nome das skills (nome == pasta)
  - projeto so-Claude: builder, critic e arbiter da familia anthropic; tier do arbiter
    (opus/sonnet/haiku) diferente do tier do builder; critic e arbiter com `contextoLimpo`
    declarado em enxame.config.json
  - bars com ordens 1..N sem buraco; loops citados pelos agentes existem
  - criticos e arbiter sem permissao de edicao
  - escopos de escrita dos agentes dentro de 'alvos'
  - default_agent existe e e primary
  - permission.edit dos agentes sem caminho absoluto de outra maquina (/home/..., /Users/...)
  - scripts/enxame/memoria.py presente
"""
import json
import re
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
erros, avisos = [], []


def err(m):
    erros.append(m)


def warn(m):
    avisos.append(m)


def js(nome):
    p = RAIZ / nome
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except FileNotFoundError:
        err(f"{nome}: arquivo ausente")
    except json.JSONDecodeError as e:
        err(f"{nome}: JSON invalido ({e})")
    return None


def frontmatter(path):
    txt = path.read_text(encoding="utf-8")
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", txt, re.S)
    if not m:
        return None, txt
    try:
        import yaml
        return yaml.safe_load(m.group(1)) or {}, m.group(2)
    except ImportError:
        d = {}
        for ln in m.group(1).splitlines():
            if re.match(r"^[A-Za-z_]+:\s", ln):
                k, v = ln.split(":", 1)
                d[k.strip()] = v.strip()
        return d, m.group(2)
    except Exception as e:  # noqa: BLE001
        err(f"{path}: frontmatter YAML invalido ({e})")
        return None, m.group(2)


def chaves_edit_absolutas(path):
    """Chaves do bloco `permission.edit` do frontmatter que comecam por /home/ ou /Users/.

    Leitura por texto (independe de PyYAML): pega as linhas indentadas sob `  edit:`."""
    txt = path.read_text(encoding="utf-8")
    m = re.match(r"^---\n(.*?)\n---\n", txt, re.S)
    if not m:
        return []
    achadas, dentro = [], False
    for ln in m.group(1).splitlines():
        if re.match(r"^  edit:\s*(#.*)?$", ln):
            dentro = True
            continue
        if dentro:
            if ln.strip() and not re.match(r"^    \S", ln):
                dentro = False  # saiu do bloco edit (outra chave de nivel 2 ou de nivel 1)
                continue
            k = re.match(r"^    [\"']?([^\"':]+)", ln)
            if k and re.match(r"^(/home/|/Users/)", k.group(1).strip()):
                achadas.append(k.group(1).strip())
    return achadas


FAMILIA = [
    (r"claude|anthropic", "anthropic"),
    (r"gpt|openai(?!\.gpt-oss)", "openai"),
    (r"glm|zai", "zhipu"),
    (r"qwen", "alibaba"),
    (r"gemini", "google"),
]


def familia(modelo):
    nome = modelo.split("/", 1)[-1].lower()
    for pad, fam in FAMILIA:
        if re.search(pad, nome):
            return fam
    return nome


def tier(modelo):
    nome = modelo.split("/", 1)[-1].lower()
    for t in ("opus", "sonnet", "haiku"):
        if t in nome:
            return t
    return None


def modelo_valido(modelo, prov_models, prov_declarados):
    """Provider com `models` explicito: o modelo precisa estar listado.
    Provider `anthropic` (nativo do OpenCode): anthropic/claude-* vale sem lista.
    Qualquer outro prefixo sem declaracao e invalido."""
    if not isinstance(modelo, str) or "/" not in modelo:
        return False
    prefixo, resto = modelo.split("/", 1)
    if prefixo in prov_declarados:
        return modelo in prov_models
    if prefixo == "anthropic":
        return resto.startswith("claude-")
    return False


def main():
    oc = js("opencode.json")
    en = js("enxame.config.json")
    gl = js("gauntlet-loop.config.json")
    if not (oc and en and gl):
        return report()

    # provider/modelos
    prov_models = set()
    prov_declarados = set()  # providers com lista `models` explicita
    for pid, p in oc.get("provider", {}).items():
        for m in p.get("models", {}):
            prov_models.add(f"{pid}/{m}")
            prov_declarados.add(pid)
    for chave in ("model", "small_model"):
        if oc.get(chave) and not modelo_valido(oc[chave], prov_models, prov_declarados):
            err(f"opencode.json: {chave}='{oc[chave]}' nao e valido (provider sem declaracao ou fora de provider.*.models)")

    # agentes
    agentes_dir = RAIZ / ".opencode" / "agents"
    skills_dir = RAIZ / ".agents" / "skills"
    skills = {}
    for d in sorted(skills_dir.glob("*/SKILL.md")):
        fm, _ = frontmatter(d)
        if not fm:
            err(f"{d.relative_to(RAIZ)}: sem frontmatter")
            continue
        if fm.get("name") != d.parent.name:
            err(f"{d.relative_to(RAIZ)}: name='{fm.get('name')}' difere da pasta '{d.parent.name}'")
        if not fm.get("description"):
            err(f"{d.relative_to(RAIZ)}: sem description (a skill nao seria listada)")
        elif len(str(fm["description"])) > 1024:
            warn(f"{d.relative_to(RAIZ)}: description com {len(str(fm['description']))} caracteres")
        skills[d.parent.name] = fm

    arq_agentes = {}
    for f in sorted(agentes_dir.glob("*.md")):
        fm, corpo = frontmatter(f)
        if fm is None:
            err(f"{f.relative_to(RAIZ)}: frontmatter ausente/invalido")
            continue
        arq_agentes[f.stem] = (fm, corpo)
        m = fm.get("model")
        if m and not modelo_valido(m, prov_models, prov_declarados):
            err(f"{f.name}: model '{m}' nao existe no provider")
        for sk in re.findall(r"skill `([a-z0-9-]+)`", corpo):
            if sk not in skills:
                err(f"{f.name}: carrega skill '{sk}' que nao existe")
        if "description" not in fm:
            err(f"{f.name}: sem description")
        for chave in chaves_edit_absolutas(f):
            err(f"{f.name}: permission.edit com caminho absoluto de outra maquina ({chave}); use caminho relativo a raiz")

    if not (RAIZ / "scripts" / "enxame" / "memoria.py").is_file():
        err("scripts/enxame/memoria.py ausente (a memoria Ruflo no Windows depende dele; memoria.sh e so um shim)")

    ids_cfg = {}
    for a in en["agentes"]:
        ids_cfg[a["id"]] = a
        oa = a["opencodeAgent"]
        if oa not in arq_agentes:
            err(f"enxame: agente '{a['id']}' aponta opencodeAgent '{oa}' sem arquivo em .opencode/agents/")
            continue
        fm, corpo = arq_agentes[oa]
        if fm.get("model") != a.get("modelo"):
            err(f"enxame: '{a['id']}' modelo '{a.get('modelo')}' difere do frontmatter '{fm.get('model')}'")
        if a["skill"] not in skills:
            err(f"enxame: '{a['id']}' skill '{a['skill']}' nao existe")
        elif f"skill `{a['skill']}`" not in corpo:
            warn(f"{oa}.md: nao menciona 'skill `{a['skill']}`' no prompt")
        if a["papel"] in ("critic", "arbiter"):
            ed = (fm.get("permission") or {}).get("edit")
            if ed != "deny":
                err(f"{oa}.md: {a['papel']} deve ter permission.edit=deny (tem {ed!r})")
        if a["papel"] in ("builder", "worker"):
            ed = (fm.get("permission") or {}).get("edit")
            if isinstance(ed, dict):
                for pat, acao in ed.items():
                    if acao != "allow":
                        continue
                    base = pat.split("/")[0].rstrip("*")
                    alvos = {v["caminho"] for v in en.get("alvos", {}).values() if isinstance(v, dict)}
                    extra = {"scripts", ".ia", "docs", "especificacoes", "experimentos", "rede-fabric", "pom.xml", "src"}
                    if base and base not in alvos | extra and not any(base.startswith(x) for x in alvos | extra):
                        warn(f"{oa}.md: escopo de escrita '{pat}' fora de 'alvos'")
            elif a["papel"] == "builder":
                err(f"{oa}.md: builder precisa de permission.edit com escopo (objeto), nao {ed!r}")
    for nome in arq_agentes:
        if nome not in {a['opencodeAgent'] for a in en['agentes']}:
            warn(f".opencode/agents/{nome}.md nao esta listado em enxame.config.json")
    for nome in skills:
        if nome not in {a['skill'] for a in en['agentes']}:
            warn(f"skill '{nome}' nao e usada por nenhum agente do enxame")

    # default_agent
    da = oc.get("default_agent")
    if da:
        if da not in arq_agentes:
            err(f"opencode.json: default_agent '{da}' sem arquivo")
        elif arq_agentes[da][0].get("mode") not in ("primary", "all"):
            err(f"opencode.json: default_agent '{da}' deve ter mode primary")

    # loops
    loops = {l["id"]: l for l in gl["loops"]}
    for lid, l in loops.items():
        bar = l.get("bar", [])
        ordens = [b["ordem"] for b in bar]
        if ordens != list(range(1, len(ordens) + 1)):
            err(f"gauntlet: {lid}: ordens da bar devem ser 1..N sem buraco (tem {ordens})")
        for papel in ("builder", "critic", "arbiter"):
            ag = l.get(papel, {})
            if ag.get("agente") and ag["agente"] not in ids_cfg:
                err(f"gauntlet: {lid}.{papel}.agente '{ag['agente']}' nao existe em enxame.config.json")
            if ag.get("skill") and ag["skill"] not in skills:
                err(f"gauntlet: {lid}.{papel}.skill '{ag['skill']}' nao existe")
        if l.get("reenvioAoBuilder") and not isinstance(l.get("orcamentoDeIteracoes"), int):
            err(f"gauntlet: {lid}: reenvio sem orcamentoDeIteracoes inteiro")
    for a in en["agentes"]:
        gl_ref = a.get("gauntletLoop", {}).get("loop")
        refs = gl_ref if isinstance(gl_ref, list) else ([gl_ref] if gl_ref else [])
        for r in refs:
            if r not in loops:
                err(f"enxame: '{a['id']}' referencia loop '{r}' inexistente")

    # projeto so-Claude: independencia por contexto limpo + tier distinto
    mp = gl.get("modelosPorPapel", {})
    fams, mods = {}, {}
    for papel in ("builder", "critic", "arbiter"):
        mod = mp.get(papel, {}).get("modelo")
        if not mod:
            err(f"gauntlet: modelosPorPapel.{papel}.modelo ausente")
            continue
        mods[papel] = mod
        fams[papel] = familia(mod)
        if not modelo_valido(mod, prov_models, prov_declarados):
            err(f"gauntlet: modelo '{mod}' ({papel}) nao esta no provider")
        if fams[papel] != "anthropic":
            err(f"gauntlet: {papel} usa familia '{fams[papel]}' ({mod}); o projeto e so-Claude (anthropic)")
    if "builder" in mods and "arbiter" in mods:
        tb, ta = tier(mods["builder"]), tier(mods["arbiter"])
        if tb is None or ta is None:
            err(f"gauntlet: nao foi possivel deduzir o tier (builder={tb}, arbiter={ta}); esperado opus/sonnet/haiku no nome do modelo")
        elif tb == ta:
            err(f"gauntlet: arbiter deve ter tier diferente do builder (ambos '{tb}')")
    for a in en["agentes"]:
        if a["papel"] in fams and familia(a["modelo"]) != fams[a["papel"]]:
            err(f"enxame: '{a['id']}' ({a['papel']}) usa familia {familia(a['modelo'])}, esperado {fams[a['papel']]}")
        if a["papel"] in ("critic", "arbiter") and not (a.get("gauntletLoop") or {}).get("contextoLimpo"):
            err(f"enxame: '{a['id']}' ({a['papel']}) sem gauntletLoop.contextoLimpo declarado")

    # INV citadas em bar existem na especificacao (se ela ja existe)
    espec = RAIZ / "especificacoes" / "dominio-redd.md"
    texto_bars = json.dumps(gl, ensure_ascii=False)
    citadas = set(re.findall(r"INV-\d+", texto_bars))
    if citadas:
        if espec.exists():
            definidas = set(re.findall(r"INV-\d+", espec.read_text(encoding="utf-8")))
            for c in sorted(citadas - definidas):
                err(f"gauntlet cita {c} que nao esta definida em especificacoes/dominio-redd.md")
        else:
            warn("especificacoes/dominio-redd.md ainda nao existe (fase 0: acionar redd-dominio)")

    return report()


def report():
    for a in avisos:
        print(f"AVISO: {a}")
    for e in erros:
        print(f"ERRO:  {e}")
    if erros:
        print(f"\nFALHOU: {len(erros)} erro(s), {len(avisos)} aviso(s)")
        return 1
    print(f"OK: configuracao coerente ({len(avisos)} aviso(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())

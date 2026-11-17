# ERRATA 1 de CRITERIOS-congelados.md (2026-11-13)

O arquivo `CRITERIOS-congelados.md` (SHA-256 em `CRITERIOS-congelados.sha256`) **não foi alterado**. Esta errata é um
arquivo separado e não muda nenhum critério, limiar, N alvo, T nem regra de decisão.

- **Onde**: §2, "Aviso sobre o poder do critério".
- **Texto congelado**: "com N ≥ 72 246 o terceiro [maior valor é o UB]".
- **Correto**: **N ≥ 72 245**. Aritmética binomial (`scripts/analisar-confirmacao.py`, auto-checagem executada
  antes de ler dado): e_sup(72244) = 1 e e_sup(72245) = 2. Os outros dois limiares do aviso (36 887 e 55 715) estão
  corretos (e_sup(36886) = −1, e_sup(36887) = 0, e_sup(55714) = 0, e_sup(55715) = 1).
- **Efeito**: nenhum. Com N ≈ 43 mil (§4), e_sup = 0 e UB = máximo do grupo, como o texto congelado já diz.
- **Como foi achado**: a auto-checagem do script de análise reprovou o limiar digitado à mão; o erro foi de digitação
  no texto, não do cálculo.

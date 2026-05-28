# ADR #1 — Fluxo de pagamento das reservas: Split direto vs. Custódia na plataforma

- **Status:** Proposed
- **Date:** 2026-05-27
- **Deciders:** Cleiton + sócio
- **Project:** Locarya

---

## Context

Locarya é um SaaS multi-tenant: locadores brasileiros de equipamentos para festas/eventos recebem reservas pelas suas storefronts. Cada reserva resulta em uma cobrança ao cliente final.

A receita da Locarya pelo SaaS em si (plano anual R$ 600) é uma **cobrança direta** Locarya↔locador, completamente fora desta discussão. Esta ADR trata **apenas do fluxo de dinheiro da reserva** entre cliente final e locador.

Duas arquiteturas viáveis:

- **Opção A — Split direto via Asaas:** o cliente paga, o Asaas divide na liquidação, o locador recebe direto na sua carteira Asaas, a Locarya recebe apenas a comissão (se houver). Locarya nunca toca o principal.
- **Opção B — Custódia na plataforma:** o cliente paga em uma conta Locarya, Locarya retém por algum período (ex.: até confirmação de entrega), depois repassa ao locador, descontando a comissão.

Ambas resolvem o problema técnico de pagamento. A diferença está em quem é o **dono jurídico do dinheiro** entre o momento da cobrança e o momento do repasse.

---

## Decision

**Adotar Opção A — Split direto via Asaas.**

A justificativa, em uma linha: a Opção B transforma Locarya em **instituição de pagamento regulada pelo BACEN**, e a Opção A não. Para um MVP com 0–5 clientes, essa diferença é existencial.

---

## Decision drivers (critérios)

Os mesmos seis critérios aplicados a cada opção:

1. Enquadramento regulatório (BACEN)
2. Tributação
3. Risco financeiro e operacional
4. Complexidade de implementação
5. Experiência do locador
6. Flexibilidade de negócio (caução, retenção condicional, reembolsos)

---

## Análise comparativa

### 1. Enquadramento regulatório (BACEN) — **decisivo**

**Opção A (Split direto):**
O dinheiro nunca entra em conta Locarya. Pela leitura consolidada da Circular BACEN 3.682/2013, Circular 3.815/2018 e Resolução BCB 150/2021, plataformas que **não tocam o fluxo financeiro** descaracterizam-se como facilitadoras de pagamento. Locarya permanece classificada como SaaS puro. Sem obrigações regulatórias adicionais.

**Opção B (Custódia):**
Receber dinheiro de terceiros e repassar é a **definição literal** de facilitador de pagamentos / subadquirente sob a regulação BACEN. Implica:

- Registro junto ao Banco Central
- Capital mínimo regulatório
- Liquidação centralizada via CIP (grade única)
- Programa de PLD/FT (prevenção à lavagem de dinheiro)
- Auditorias periódicas
- Reportes contínuos ao BACEN
- Custos estimados de R$ 50k–200k+ em estruturação inicial (advocacia regulatória + adequação técnica)
- Meses de processo antes de poder operar legalmente

**Vencedor:** A, por uma margem que sozinha já decide a ADR.

---

### 2. Tributação

**Opção A:**
Cada parte recolhe imposto apenas sobre o que efetivamente recebe.
- Locador: receita = valor do aluguel
- Locarya: receita = comissão (se houver) + assinatura SaaS

Compatível por design com o split payment de IBS/CBS introduzido pela LC 214/2025 (Reforma Tributária).

**Opção B:**
Risco de a Receita interpretar que **toda a entrada bruta é receita da Locarya**, com o repasse sendo despesa. Resultado prático: bitributação (Locarya paga sobre R$ 500, locador paga sobre R$ 450, mesmo dinheiro). É possível defender contabilmente uma conta de "passagem", mas exige estrutura jurídica/contábil que MVP não tem.

**Vencedor:** A.

---

### 3. Risco financeiro e operacional

**Opção A:**
- Chargeback recai sobre o locador (recebedor real), não sobre Locarya.
- Sem exposição a problemas de fluxo de caixa da Locarya afetando dinheiro de terceiros.
- Conciliação contábil limpa.

**Opção B:**
- Locarya carrega risco de chargeback enquanto detém o dinheiro.
- Se Locarya tiver dificuldade financeira, dinheiro de locadores fica exposto (risco reputacional e jurídico).
- Atrasos de repasse geram passivos cíveis com locadores.
- Conciliação contábil complexa — receita "de passagem" precisa entrar e sair com rastreabilidade auditável.

**Vencedor:** A.

---

### 4. Complexidade de implementação

**Opção A:**
- Integração API Asaas com split.
- Onboarding cria subconta Asaas para o locador (passo extra, KYC do Asaas).
- Split é **API-only no Asaas** — não há painel administrativo nativo, tudo via código.
- Webhooks para confirmação, estorno, divergência de split.

**Opção B:**
- Cobrança normal via Asaas (sem split).
- Sistema interno de fila de repasses (cron, ledger, reconciliação).
- Lógica de comissão calculada internamente.
- Sistema de transferências para conta dos locadores.
- Auditoria de saldos retidos.
- Implementação técnica **mais simples no curto prazo**, mas com débito técnico crescente.

**Vencedor:** B vence no dia 1, A vence em qualquer horizonte >3 meses. Não decisivo.

---

### 5. Experiência do locador

**Opção A:**
Locador brasileiro habituado a "WhatsApp + Pix direto na conta" recebe argumento direto: "o cliente paga e o dinheiro cai na sua conta na hora, não passa por ninguém". Alinha-se com a expectativa do público-alvo.

Fricção: precisa criar/conectar subconta Asaas no onboarding.

**Opção B:**
Locador entrega o controle do próprio dinheiro a uma plataforma desconhecida, em estágio MVP, sem track record. Para esse perfil (cost-conscious, vindo de WhatsApp), é uma fricção comercial significativa.

**Vencedor:** A.

---

### 6. Flexibilidade de negócio

**Opção A:**
- ✅ Pix, boleto, cartão suportados nativamente.
- ✅ Estorno automático e simétrico (Asaas reverte o split).
- ⚠️ Retenção condicional ("só libera após entrega") só é possível via feature de **bloqueio temporário de split** do Asaas, com prazos limitados.
- ⚠️ Caução exige cobrança separada (segunda transação, ou Pix manual, ou bloqueio de split com prazo).
- ⚠️ Cobrança em cartão com split **não pode ser usada como garantia em operações de crédito** (limitação documentada do Asaas).
- ⚠️ Lock-in no Asaas — mitigável com camada de abstração `PaymentService`.

**Opção B:**
- ✅ Locarya controla 100% do timing e das regras de repasse.
- ✅ Caução é trivial — basta não repassar a parcela correspondente.
- ✅ Retenção condicional é trivial.
- ✅ Reembolso parcial é trivial.

**Vencedor:** B vence em flexibilidade, mas só importa se o critério 1 não fosse decisivo.

---

## Scorecard

| Critério                          | Peso     | Opção A (Split) | Opção B (Custódia) |
|-----------------------------------|----------|-----------------|--------------------|
| 1. Regulatório BACEN              | Crítico  | ✅ Fora do escopo | ❌ Vira instituição de pagamento |
| 2. Tributação                     | Alto     | ✅ Limpa         | ⚠️ Risco de bitributação |
| 3. Risco financeiro/operacional   | Alto     | ✅ Baixo         | ⚠️ Alto |
| 4. Complexidade de implementação  | Médio    | ⚠️ Maior dia 1   | ✅ Menor dia 1 |
| 5. Experiência do locador         | Alto     | ✅ Aderente      | ⚠️ Fricção comercial |
| 6. Flexibilidade de negócio       | Médio    | ⚠️ Limitada      | ✅ Total |

O critério 1 sozinho seria suficiente. Os critérios 2, 3 e 5 reforçam. Os critérios 4 e 6 favorecem B, mas não compensam.

---

## Consequences

### Positivas

- ✅ Locarya permanece fora do escopo regulatório BACEN como instituição de pagamento.
- ✅ Tributação correta para cada parte, sem bitributação.
- ✅ Argumento de venda forte para o locador.
- ✅ Estornos automáticos e simétricos.
- ✅ Contabilidade limpa — Locarya registra apenas sua receita real.
- ✅ Reduz risco de chargeback recair na plataforma.
- ✅ Pronta para evoluir conforme split payment de IBS/CBS da LC 214/2025.

### Negativas / Tradeoffs aceitos

- ⚠️ Split é API-only no Asaas — exige integração técnica completa.
- ⚠️ Onboarding tem etapa extra de criação/vinculação de subconta Asaas.
- ⚠️ Locarya não controla livremente o timing do repasse — depende de features do Asaas (retenção temporária de split tem prazos limitados).
- ⚠️ Caução/security deposit exige design separado (segunda cobrança, ou bloqueio de split com prazo, ou Pix manual).
- ⚠️ Cobrança em cartão com split não pode ser usada como garantia em operações de crédito.
- ⚠️ Lock-in técnico no Asaas. **Mitigação:** camada de abstração `PaymentService` com interface única.

### Decisões adiadas (futuras ADRs)

- 📋 Estratégia de KYC (Asaas faz, ou Locarya replica)
- 📋 Design da caução / security deposit
- 📋 Fluxo de cancelamento e reembolso parcial
- 📋 Emissão de NF (locador emite NF do aluguel; Locarya emite NF da comissão e da assinatura)
- 📋 Política de retenção condicional (split bloqueado até entrega, ou liberação imediata)

---

## Alternatives considered

### Alt 1 — Pix manual direto locador↔cliente, sem gateway

**Aceita apenas como ponte para os primeiros 5 clientes**, conforme o plano fásico de pagamentos já documentado. Não escala: sem confirmação automática, sem rastreabilidade, sem cartão, sem boleto, sem comissão automatizável. Ponte, não destino.

### Alt 2 — Mercado Pago Split / Pagar.me Marketplace

**Funcionalmente equivalente à Opção A.** Resolveriam igualmente o problema regulatório. Asaas escolhido por:

- Tarifas competitivas para volume baixo/médio brasileiro.
- Onboarding simples para subcontas.
- API documentada em português.
- Suporte nativo a Pix + split (algumas concorrentes ainda têm fricção aqui).
- Já alinhado com o stack do projeto.

A camada de abstração `PaymentService` deve isolar o gateway para permitir troca futura sem reescrever o domínio.

### Alt 3 — Não cobrar comissão por reserva, apenas SaaS

**Ortogonal à decisão de split.** Locarya ainda precisa processar pagamentos pelas storefronts (é parte do produto). Se a comissão for zero, o split fica trivial — 100% para o locador, 0% para a Locarya — ou pode-se nem usar split, criando a cobrança direto na conta do locador via API. Vale considerar para o tier free e/ou primeiros clientes.

---

## Implementation notes (não-vinculantes, para o roadmap)

1. Onboarding cria subconta Asaas via API, persiste `walletId` em `PaymentSettings` do Provider.
2. `PaymentService` abstrai o gateway — interface única: `createCharge`, `refund`, `getStatus`, `setupSplit`. Implementação Asaas isolada atrás.
3. Webhook handler tipado para os eventos relevantes do Asaas: `PAYMENT_CONFIRMED`, `PAYMENT_REFUNDED`, `PAYMENT_SPLIT_DIVERGENCE_BLOCK`, `PAYMENT_SPLIT_DIVERGENCE_BLOCK_FINISHED`.
4. Tabela `payment_splits` separada da `bookings.payment` JSONB, para auditoria histórica de splits aplicados.
5. Para caução: avaliar split com bloqueio temporal vs. cobrança separada estornável — decisão em ADR futura.
6. Manter `PaymentMethod.PIX_MANUAL` como fallback registrado para os 5 primeiros clientes.

---

## References

- BACEN Circular 3.682/2013 — define arranjos de pagamento integrantes do SPB
- BACEN Circular 3.815/2018 — estende obrigações de facilitadores a marketplaces
- Resolução BCB 150/2021 — atualiza enquadramento de instituições de pagamento
- LC 214/2025 — Reforma Tributária; formaliza split payment para recolhimento de IBS/CBS
- Asaas Docs — Split de Pagamentos (`docs.asaas.com/docs/split-de-pagamentos`)
- Baptista Luz — FAQ sobre regulação BACEN para marketplaces (referência jurídica sobre "sair do fluxo financeiro" via split como mecanismo de descaracterização)

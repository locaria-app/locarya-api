# ADR #2 — Scala com Typelevel ecosystem em vez de Node.js

- **Status:** Proposed
- **Date:** 2026-05-27
- **Deciders:** Cleiton
- **Project:** Locarya

---

## Context

O MVP inicial da Locarya foi desenvolvido em **Node.js**. Durante o desenvolvimento, o projeto cresceu em complexidade — modelo de dados, fluxos de pagamento, contratos, disponibilidade, multi-tenancy — e a experiência revelou duas fragilidades:

1. **Falta de type safety forte** — bugs em runtime que type checkers mais rigorosos teriam detectado em compile time
2. **Complexidade de testes** — testar efeitos colaterais (IO, database, APIs externas) sem abstrações funcionais adequadas gerou código de teste verboso e frágil

Paralelamente, o desenvolvedor principal (Cleiton) trabalhou recentemente em APIs pequenas mas funcionais usando **Scala + ecossistema Typelevel** (Cats Effect, http4s, doobie, etc.) e identificou ganhos significativos em:

- **Testabilidade** — uso de `IO` e traits para isolar efeitos e criar mocks/stubs trivialmente
- **Refatorabilidade** — o compilador detecta quebras de contrato imediatamente
- **Expressividade funcional** — combinadores, for-comprehensions, pattern matching para modelar domínio de maneira mais direta

A decisão: reescrever a Locarya API do zero em **Scala 3 + Typelevel**, em vez de continuar no Node.js existente ou migrar incrementalmente.

---

## Decision

**Adotar Scala 3 com o ecossistema Typelevel (Cats Effect 3, http4s, doobie, circe) como stack backend.**

Justificativa em uma linha: para um SaaS multi-tenant com lógica de negócio não-trivial (disponibilidade temporal, split payments, contratos condicionais), **type safety + programação funcional > tamanho do ecossistema**, especialmente quando assistência de IA mitiga a fricção da curva de aprendizado.

---

## Decision drivers

1. **Type safety e correção em compile time**
2. **Testabilidade e isolamento de efeitos**
3. **Produtividade do desenvolvedor principal**
4. **Manutenibilidade a longo prazo**
5. **Tamanho do ecossistema e facilidade de contratação** (contra-indicador)
6. **Assistência de IA como mitigador de fricção técnica**

---

## Análise comparativa

### 1. Type safety e correção em compile time

**Node.js (TypeScript):**
- Type erasure em runtime — tipos desaparecem após compilação
- Tipos estruturais permitem falsos positivos (`any`, `as`, tipos parciais)
- Runtime errors comuns: `undefined is not a function`, `cannot read property of null`
- Validação de dados externos (req.body, DB rows) exige bibliotecas runtime (zod, io-ts)

**Scala + Typelevel:**
- Sistema de tipos forte com inferência (Hindley-Milner estendido)
- ADTs (Algebraic Data Types) para modelar estados impossíveis como não-compiláveis
- `IO[A]` torna efeitos explícitos no tipo — função que retorna `IO[User]` declara que vai fazer IO, não esconde
- Validação via `Decoder` (circe) falha em compile time se tipos não batem
- Pattern matching exhaustivo — compilador força você a tratar todos os casos de um ADT

**Vencedor:** Scala, por margem significativa.

---

### 2. Testabilidade e isolamento de efeitos

**Node.js:**
- Efeitos (DB, HTTP, filesystem) misturados com lógica de negócio
- Testes unitários exigem mocking pesado (sinon, jest.mock) — frágil e verboso
- Async/await esconde IO, dificulta raciocínio sobre quando efeitos acontecem
- Testes de integração são lentos e setup é complexo

**Scala + Typelevel:**
- `IO[A]` separa **descrição** do efeito da **execução** — compose IO values sem executar nada
- Traits + injeção de dependência via implicits/given = mock trivial
- `IO` é um monad — pode trocar implementação (test vs prod) sem mudar lógica
- Propriedades testáveis via ScalaCheck (property-based testing nativo)
- Exemplo: `PaymentService[F[_]]` pode rodar com `F = IO` (prod) ou `F = Id` (testes síncronos puros)

**Vencedor:** Scala. A diferença não é de grau, é de categoria — efeitos reificados vs. efeitos implícitos.

---

### 3. Produtividade do desenvolvedor principal

**Node.js:**
- Familiar para a maioria dos devs brasileiros
- Ecossistema gigante (npm)
- Cleiton trabalhou com isso no MVP, mas sentiu limitações

**Scala + Typelevel:**
- Cleiton já trabalhou em projetos recentes com Scala + Typelevel e **gostou** da experiência
- Curva de aprendizado vencida — já entende FP, IO, traits, for-comprehensions
- Produtividade pessoal maior quando trabalha com ferramentas que se alinham com seu modelo mental

**Vencedor:** Scala, **para o desenvolvedor atual**. Esse critério inverteria se o dev principal fosse outro.

---

### 4. Manutenibilidade a longo prazo

**Node.js:**
- Refatorações grandes = risco alto de quebrar runtime
- Dependências npm voláteis — breaking changes frequentes, supply chain attacks
- JavaScript é dinâmico por natureza — manutenção de código legado é dolorosa

**Scala + Typelevel:**
- Compilador detecta 70-80% dos erros de refatoração antes de rodar
- Ecossistema Typelevel tem política de compatibilidade binária rigorosa (MiMa)
- Código funcional imutável é mais fácil de raciocinar 6 meses depois
- Tipos como documentação viva — assinatura de função diz o que ela faz

**Vencedor:** Scala.

---

### 5. Tamanho do ecossistema e facilidade de contratação

**Node.js:**
- Ecossistema gigantesco — qualquer necessidade tem 5 bibliotecas npm
- Pool de desenvolvedores Node.js no Brasil é enorme
- Onboarding de novos devs é rápido

**Scala + Typelevel:**
- Ecossistema menor — algumas necessidades exigem escrever do zero ou usar libs Java
- Pool de desenvolvedores Scala no Brasil é **pequeno**
- Desenvolvedores Scala + FP são ainda mais raros e caros
- Curva de aprendizado íngreme para quem não conhece FP

**Vencedor:** Node.js, sem discussão.

---

### 6. Assistência de IA como mitigador de fricção técnica

**Contexto 2026:**
Claude Code, GitHub Copilot, Cursor e outros assistentes de IA alcançaram competência em Scala/Typelevel comparável ao Node.js. Desenvolvedores júnior ou mid-level podem ser produtivos em Scala com assistência contínua de IA — o modelo corrige erros de tipos, sugere combinadores, gera boilerplate, explica conceitos de FP em contexto.

**Impacto:**
A barreira de entrada (#5) diminui significativamente. Um dev que conhece programação mas não FP pode ramp up em Scala + Typelevel em **semanas** com IA, não meses. Isso não elimina o trade-off, mas muda o peso.

**Consequência:** O critério #5 (contra-indicador) é **parcialmente mitigado** pela realidade de 2026.

---

## Scorecard

| Critério                          | Peso     | Scala + Typelevel | Node.js (TypeScript) |
|-----------------------------------|----------|-------------------|----------------------|
| 1. Type safety                    | Crítico  | ✅ Forte           | ⚠️ Estrutural/erasure |
| 2. Testabilidade                  | Alto     | ✅ IO + traits     | ⚠️ Mocking pesado     |
| 3. Produtividade do dev principal | Alto     | ✅ Preferência      | ⚠️ Limitações sentidas |
| 4. Manutenibilidade               | Alto     | ✅ Compilador ajuda | ⚠️ Runtime fragile    |
| 5. Ecossistema e contratação      | Alto     | ❌ Pequeno         | ✅ Enorme             |
| 6. Mitigação via IA               | Médio    | ✅ Reduz barreira  | N/A                  |

**Resultado:** Scala vence nos critérios técnicos (1-4), perde no critério social (5), mas #6 mitiga parcialmente. Para um projeto early-stage com **1 desenvolvedor principal que já domina Scala/FP**, a escolha faz sentido.

---

## Consequences

### Positivas

- ✅ Bugs detectados em compile time, não em produção
- ✅ Testes mais simples, rápidos e confiáveis (IO + traits)
- ✅ Refatorações seguras — compilador guia o processo
- ✅ Expressividade funcional para modelar regras de negócio complexas (disponibilidade temporal, políticas de pagamento)
- ✅ Developer experience alinhado com preferências do time atual
- ✅ Código imutável por padrão reduz bugs de estado compartilhado (crítico para multi-tenancy)
- ✅ Performance competitiva (JVM otimizada, GC moderno)

### Negativas / Tradeoffs aceitos

- ⚠️ **Pool de contratação reduzido** — se o time crescer, encontrar devs Scala/FP no Brasil será desafiador e caro. **Mitigação:** contratar devs sólidos em qualquer linguagem funcional (Haskell, Elixir, F#) e onboarding com IA; ou contratar júniors motivados e investir em educação.
- ⚠️ **Ecossistema menor** — algumas integrações (ex: libs específicas de gateways de pagamento) podem não ter SDK Scala, exigindo usar SDKs Java (compatível, mas com API menos idiomática) ou escrever cliente HTTP direto.
- ⚠️ **Compile times maiores** — Scala compila mais devagar que TypeScript. Mitigação: usar sbt com Bloop, zinc incremental, CI paralela.
- ⚠️ **Curva de aprendizado para novos colaboradores** — mesmo com IA, onboarding leva mais tempo. Mitigação: documentação de padrões FP usados no projeto, pair programming, code reviews educativos.
- ⚠️ **Menor familiaridade no mercado brasileiro** — investidores/stakeholders técnicos podem questionar a escolha. Mitigação: demonstrar ganhos de qualidade e velocidade com métricas (cobertura de testes, bugs em produção, tempo de refatoração).

---

## Alternatives considered

### Alt 1 — Continuar com Node.js (TypeScript) e melhorar arquitetura

**Rejeitado.**
TypeScript + fp-ts (functional programming lib) poderia trazer alguns benefícios funcionais, mas:

- Type erasure permanece — bugs de runtime não eliminados
- fp-ts tem sintaxe verbosa (sem HKT nativo em TS) e menor adoção
- O problema não era só arquitetura, era limitação da linguagem

Tentar "fazer Scala em TypeScript" resultaria em código menos idiomático e mais complexo que aceitar as limitações do TS.

### Alt 2 — Java com Spring Boot

**Considerado seriamente.**
Java 21+ com Records, Pattern Matching, Virtual Threads, Spring Boot 3 é uma stack sólida:

- Ecossistema gigante (maior que Scala)
- Pool de contratação enorme no Brasil
- Type safety forte (não tanto quanto Scala, mas muito melhor que TS)
- Performance excelente

**Por que não foi escolhido:**

- Spring é imperativo/OOP por natureza — programação funcional é possível mas não idiomática
- Boilerplate maior que Scala (getters, setters, builders, annotations)
- Testabilidade melhor que Node.js, mas pior que Scala + IO
- Virtual Threads são green threads, mas não substituem IO abstrato — efeitos ainda implícitos
- Cleiton **já domina Scala** — trocar para Java seria aprender outra stack sem ganho claro sobre Scala

Java seria a escolha **se contratação fosse critério #1**. Como o projeto é early-stage (1-2 devs), Scala vence.

### Alt 3 — Kotlin + Arrow (FP lib)

**Não considerado profundamente.**
Kotlin tem boa ergonomia e null-safety, mas:

- Arrow ainda é imaturo comparado ao ecossistema Typelevel
- Comunidade FP em Kotlin é pequena
- Cleiton já tem experiência em Scala, não em Kotlin — não há vantagem em trocar

### Alt 4 — Haskell / PureScript / OCaml

**Rejeitado.**
FP mais puro que Scala, mas:

- Ecossistema **ainda menor** para aplicações web comerciais
- Praticamente zero pool de contratação no Brasil
- Curva de aprendizado extrema (lazy evaluation, monads everywhere, etc.)

Scala é o "sweet spot" — FP suficientemente forte, mas pragmático (permite escape hatches, interop com Java, etc.).

---

## Implementation notes

### Stack escolhido

- **Scala 3** (nova sintaxe, melhor inferência de tipos, Union types)
- **Cats Effect 3** (IO monad, runtime, concurrent primitives)
- **http4s** (servidor HTTP funcional, client HTTP)
- **doobie** (database access funcional com Cats Effect)
- **circe** (JSON encoding/decoding via type classes)
- **refined** (validação de tipos via tipos literais — ex: `PositiveInt`, `NonEmptyString`)
- **testcontainers-scala** (testes de integração com Docker)
- **scalatest** / **munit** + **ScalaCheck** (testes unitários + property-based)

### Estrutura de projeto

```
locarya-api/
├── core/          # Domain models, ADTs, business logic (pure, no effects)
├── services/      # Service layer (effects via F[_], traits para DI)
├── infrastructure/# DB, HTTP clients, external APIs (implementações concretas)
├── http/          # http4s routes, endpoints, DTOs
└── app/           # Main (composição, wiring, IOApp)
```

### Padrões

- **Tagless Final** para abstração de efeitos (`trait PaymentService[F[_]]`)
- **ADTs para estados** — `BookingStatus` como sealed trait, pattern matching exhaustivo
- **Newtype pattern** para IDs (`BookingId`, `ProviderId`) — type safety sem overhead runtime
- **Smart constructors** para validação (ex: `Email.fromString(str): Either[ValidationError, Email]`)
- **Repository pattern** com `F[_]` — `trait BookingRepo[F[_]]`

### Mitigações

- **Documentação interna** — guia de FP patterns usados no projeto (onboarding de novos devs)
- **CI rigoroso** — compile, testes, scalafmt, scalafix, wartremover (linter FP)
- **Abstrações via libs Java** quando SDK Scala não existe (ex: Asaas client via Java + wrapper funcional)
- **AI-assisted development** — Claude Code, Copilot configurado para Scala/Typelevel

---

## References

- **Typelevel ecosystem:** https://typelevel.org/
- **Cats Effect docs:** https://typelevel.org/cats-effect/
- **http4s:** https://http4s.org/
- **doobie:** https://tpolecat.github.io/doobie/
- **Scala 3 docs:** https://docs.scala-lang.org/scala3/
- **Functional Programming in Scala (Red Book)** — Chiusano & Bjarnason
- **Real World Haskell** (conceitos transferíveis para Scala FP)

---

## Notas finais

Esta decisão é **reversível**, mas custosa — reescrever em outra linguagem levaria semanas. Portanto, embora tecnicamente reversível, na prática é **semi-permanente** para os próximos 1-2 anos.

A aposta: **qualidade do código + velocidade de refatoração > tamanho do ecossistema**, especialmente com IA reduzindo fricção. Se o projeto crescer para 5+ devs e contratação virar gargalo, pode-se reavaliar (mas nesse ponto, o custo de reescrever será ainda maior — então a decisão se auto-reforça).

Para um SaaS de nicho (locação de brinquedos) onde **confiabilidade > time-to-market**, essa escolha prioriza corretamente.

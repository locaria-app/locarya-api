# ADR #3 — Observability & Structured Logging

- **Status:** Proposed
- **Date:** 2026-05-27
- **Deciders:** Cleiton
- **Project:** Locarya

---

## Context

Locarya é um SaaS multi-tenant que processa transações financeiras críticas (via Asaas split payment), gerencia disponibilidade de inventário (evitando double-booking), e orquestra fluxos de negócio com múltiplos estados (Bookings, Payments, Webhooks).

**Cenários que exigem debugging rápido:**
- Webhook do Asaas não chega ou falha no processamento
- Booking foi criado mas estoque não foi descontado corretamente
- Customer reclama que pagamento não foi registrado
- Provider reporta double-booking (dois Bookings confirmados para o mesmo Item/data)
- Investigação de fraude (tentativa de bypass de validação de disponibilidade)

Sem observabilidade adequada desde o MVP, investigar esses problemas exige acesso direto ao banco de dados (perigoso), replicação manual do bug (pode ser impossível), ou pior — não conseguir investigar.

**Questão:** Qual estratégia de logging e observabilidade adotar desde o início, balanceando simplicidade (MVP) com utilidade (produção)?

---

## Decision

**Adotar logging estruturado (JSON) com correlation tracking e eventos de negócio explícitos.**

Implementar desde o MVP usando **log4cats** (Typelevel) + **Logback** com JSON encoder. No MVP, logs vão para stdout/Docker logs. Em produção (Phase 2), integrar com Datadog, CloudWatch ou ELK stack sem mudança de código.

---

## Decision drivers

1. **Debugabilidade de fluxos multi-tenant**
2. **Rastreabilidade de eventos de negócio críticos**
3. **Conformidade e auditoria (pagamentos, webhooks)**
4. **Compatibilidade com stack funcional (Cats Effect)**
5. **Custo e complexidade no MVP**

---

## Análise comparativa

### 1. Debugabilidade de fluxos multi-tenant

**Logging simples (println, logs planos):**
- Difícil filtrar por `providerId` quando múltiplos Providers usam o sistema simultaneamente
- Impossível rastrear um request através de múltiplos services sem parsing manual
- Correlacionar eventos (booking criado → availability checada → payment confirmado) exige olhar timestamps e "adivinhar"

**Logging estruturado + Correlation ID:**
- Cada request HTTP recebe um `correlationId` (UUID)
- Todos os logs daquele request incluem o mesmo ID — filtrar no agregador de logs mostra toda a jornada
- Contexto de negócio (`providerId`, `bookingId`, `customerId`) em cada log permite filtrar por tenant
- Exemplo: "mostrar todos os logs do Provider X nas últimas 24h" é uma query simples

**Vencedor:** Estruturado.

---

### 2. Rastreabilidade de eventos de negócio críticos

**Logging ad-hoc (cada dev loga o que lembra):**
- Inconsistente — alguns eventos são logados, outros não
- Difícil saber "quando o Booking foi confirmado?" se não há log explícito
- Investigar "quantos webhooks inválidos recebemos ontem?" exige grep complexo

**Eventos de negócio explícitos (structured logging):**
- Lista canônica de eventos críticos:
  - `BookingCreated`, `BookingStatusChanged`, `PaymentConfirmed`, `WebhookReceived`, `AvailabilityChecked`, etc.
- Cada evento é um log `INFO` com campos estruturados
- Possível criar métricas/dashboards em cima dos logs (ex: "quantos Bookings/hora?")

**Vencedor:** Explícito.

---

### 3. Conformidade e auditoria (pagamentos, webhooks)

**Sem logging de webhooks/pagamentos:**
- Impossível provar que um webhook foi recebido (ou não)
- Disputas de pagamento ("eu paguei mas vocês não registraram") viram ele-disse-ela-disse

**Logging auditável:**
- Todo webhook recebido é logado (válido ou inválido, com IP de origem, headers, body)
- Todo pagamento manual registrado é logado (quem registrou, quando, valor)
- Logs estruturados podem ser exportados para compliance/auditoria

**Vencedor:** Auditável. Crítico para proteção legal.

---

### 4. Compatibilidade com stack funcional (Cats Effect)

**Bibliotecas imperativas (slf4j direto, println):**
- Quebra a pureza funcional — side effects não rastreados no tipo
- Difícil testar — logs não são mockáveis/injetáveis
- Não se integra com `IO` suspend/async

**log4cats (Typelevel):**
- Logging como effect — `Logger[F].info("msg")` retorna `F[Unit]`
- Testável — pode usar `TestingLogger[F]` que acumula logs em memória
- Integra nativamente com Cats Effect, http4s, doobie

**Vencedor:** log4cats (alinhado com ADR #2 — Scala + Typelevel).

---

### 5. Custo e complexidade no MVP

**Estruturado desde o início:**
- Setup inicial: configurar Logback JSON encoder (~30 linhas XML)
- Cada log precisa de contexto estruturado (mais verboso que `println`)
- Mas evita reescrever logging depois — "pay now or pay (mais caro) later"

**Simples no MVP, estruturado depois:**
- MVP rápido (println, logs planos)
- Mas migrar depois exige:
  - Reescrever centenas de log statements
  - Quebra compatibilidade com dashboards/alertas já configurados
  - Risco de esquecer eventos críticos

**Vencedor:** Estruturado desde o início. Custo upfront baixo, evita débito técnico.

---

## Scorecard

| Critério                          | Peso     | Estruturado + Correlation | Logging simples |
|-----------------------------------|----------|---------------------------|-----------------|
| 1. Debugabilidade multi-tenant    | Crítico  | ✅ Filtros fáceis         | ❌ Grep manual   |
| 2. Rastreabilidade de eventos     | Alto     | ✅ Eventos explícitos     | ⚠️ Ad-hoc        |
| 3. Conformidade/auditoria         | Alto     | ✅ Auditável              | ❌ Sem prova     |
| 4. Compatibilidade com stack FP   | Médio    | ✅ log4cats               | ⚠️ Imperativo    |
| 5. Custo/complexidade MVP         | Médio    | ⚠️ Setup inicial          | ✅ Mais rápido   |

Estruturado vence nos critérios críticos. Complexidade extra no MVP é aceitável.

---

## Implementation

### Stack de Logging

**Biblioteca:** `log4cats-slf4j` (abstração) + `logback-classic` (backend)

**Formato:** JSON estruturado via `logstash-logback-encoder`

**Níveis:**
- `ERROR`: falhas que exigem ação imediata (webhook rejeitado, DB down, payment failed irrecoverable)
- `WARN`: situações anormais mas recuperáveis (retry de webhook, stock baixo, login failed)
- `INFO`: eventos de negócio importantes (ver lista abaixo)
- `DEBUG`: detalhes técnicos (SQL queries, HTTP calls, availability calculations) — apenas dev/staging

### Correlation Tracking

**Implementação:**
1. Middleware http4s gera `X-Correlation-ID` (UUID) para cada request
2. Armazena no contexto via `Ref[F]` ou `IOLocal`
3. `Logger[F]` inclui automaticamente o correlation ID em todos os logs daquele request

**Exemplo de log:**
```json
{
  "timestamp": "2026-05-27T14:32:01.234Z",
  "level": "INFO",
  "correlationId": "a3f2b1c4-5678-...",
  "providerId": "prov_abc123",
  "bookingId": "book_xyz789",
  "event": "BookingCreated",
  "message": "Booking created via Storefront",
  "metadata": {
    "itemIds": ["item_001", "item_002"],
    "date": "2026-06-15",
    "createdBy": "customer"
  }
}
```

### Eventos Críticos para Logar (INFO level)

**Bookings:**
- `BookingCreated` — incluir `providerId`, `customerId`, `bookingId`, `itemIds[]`, `date`, `createdBy` (customer | provider)
- `BookingStatusChanged` — incluir `bookingId`, `fromStatus`, `toStatus`, `reason` (se cancelado)
- `BookingAvailabilityCheckFailed` — tentou criar Booking mas Item indisponível (pode indicar race condition)

**Payments:**
- `PaymentRecorded` — incluir `bookingId`, `paymentId`, `amount`, `method` (asaas_split | PIX_MANUAL)
- `PaymentConfirmed` — payment status mudou para confirmed
- `PaymentRefunded` — incluir `refundAmount`, `reason`

**Webhooks (Asaas):**
- `WebhookReceived` — incluir `paymentId`, `status`, `sourceIP`, `signatureValid` (true/false)
- `WebhookRejected` — incluir `reason` (signature invalid, IP not allowed, rate limited)
- `WebhookProcessed` — sucesso no processamento
- `WebhookFailed` — falha no processamento (incluir exception)

**Items/Combos:**
- `ItemCreated`, `ItemDeactivated`
- `ComboCreated`
- `ComboEditBlocked` — tentou editar Combo com Bookings existentes (validação funcionou)

**Auth:**
- `LoginSuccessful` — incluir `providerId`, `email`
- `LoginFailed` — incluir `email`, `reason` (invalid password, account locked)

**Availability:**
- `AvailabilityChecked` — incluir `itemIds[]`, `date`, `result` (available | unavailable), `reason` (se unavailable: "stock depleted" | "combo item missing")

### Configuração Logback (MVP)

**logback.xml:**
```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeContext>false</includeContext>
      <fieldNames>
        <timestamp>timestamp</timestamp>
        <message>message</message>
        <logger>logger</logger>
        <level>level</level>
      </fieldNames>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="STDOUT" />
  </root>

  <!-- DEBUG em dev -->
  <logger name="doobie" level="DEBUG" />
  <logger name="org.http4s" level="DEBUG" />
</configuration>
```

**Em produção (Phase 2):** adicionar appender para Datadog/CloudWatch, mas código não muda.

### Exemplo de Uso no Código

```scala
trait BookingService[F[_]] {
  def create(request: CreateBookingRequest): F[Booking]
}

object BookingService {
  def impl[F[_]: Async: Logger](
    repo: BookingRepo[F],
    availability: AvailabilityService[F]
  ): BookingService[F] = new BookingService[F] {

    def create(req: CreateBookingRequest): F[Booking] = for {
      // Check availability
      avail <- availability.check(req.items, req.date)
      _ <- Logger[F].info(
        s"Availability checked for booking",
        Map(
          "event" -> "AvailabilityChecked",
          "itemIds" -> req.items.map(_.id).mkString(","),
          "date" -> req.date.toString,
          "result" -> (if (avail.isAvailable) "available" else "unavailable")
        )
      )
      _ <- Async[F].raiseError(UnavailableError).whenA(!avail.isAvailable)

      // Create booking
      booking <- repo.create(req.toBooking)
      _ <- Logger[F].info(
        s"Booking created",
        Map(
          "event" -> "BookingCreated",
          "bookingId" -> booking.id.value,
          "providerId" -> booking.providerId.value,
          "customerId" -> booking.customerId.value,
          "createdBy" -> "customer"
        )
      )
    } yield booking
  }
}
```

---

## Consequences

### Positivas

- ✅ **Debugging rápido:** filtrar logs por `correlationId` mostra jornada completa de um request
- ✅ **Multi-tenancy:** filtrar por `providerId` isola logs de um único tenant
- ✅ **Auditoria:** logs estruturados provam que eventos aconteceram (webhooks recebidos, payments confirmados)
- ✅ **Métricas de negócio:** possível criar dashboards em cima dos eventos (`BookingCreated/hour`, `PaymentConfirmed/day`)
- ✅ **Compatível com FP:** log4cats integra com `F[_]`, testável
- ✅ **Sem vendor lock-in:** logs vão para stdout, fácil integrar com qualquer agregador depois

### Negativas / Tradeoffs aceitos

- ⚠️ **Verbosidade:** logs estruturados são mais verbosos que `println("criou booking")`
- ⚠️ **Setup inicial:** configurar Logback JSON encoder + correlation middleware (custo: ~2h de dev)
- ⚠️ **Performance:** logging estruturado é ~10-20% mais lento que logs simples (mas ainda <1ms per log, aceitável)
- ⚠️ **Disciplina:** devs precisam lembrar de logar eventos críticos (mitigação: code reviews + checklist)

### Decisões adiadas (futuras ADRs)

- 📋 Integração com agregador de logs (Datadog vs CloudWatch vs ELK) — Phase 2
- 📋 Métricas (Prometheus/Grafana) vs apenas logs — Phase 2
- 📋 Distributed tracing (OpenTelemetry) — Phase 3 (se arquitetura virar microservices)
- 📋 Retenção de logs (quanto tempo guardar? onde armazenar?) — Phase 2

---

## Alternatives considered

### Alt 1 — Println / logs planos (sem estrutura)

**Rejeitado.**
Impossível debugar multi-tenancy. Investigar "o que aconteceu com o Booking X?" exigia grep manual, adivinhação de timestamps, sorte. Não serve para produção.

### Alt 2 — Structured logging mas sem correlation ID

**Rejeitado.**
Logs estruturados ajudam, mas sem correlation tracking ainda é difícil rastrear um request através de múltiplos services. Se `BookingService` chama `AvailabilityService` que chama `ItemRepo`, como correlacionar os logs? Correlation ID resolve isso trivialmente.

### Alt 3 — Distributed tracing (OpenTelemetry) desde o MVP

**Rejeitado no MVP, considerado para Phase 3.**
OpenTelemetry é o padrão gold para observabilidade, mas:
- Setup muito mais complexo (agent, collector, backend como Jaeger/Tempo)
- Overkill para monolito (útil para microservices)
- Logs estruturados + correlation ID cobrem 90% das necessidades do MVP

Se o sistema virar microservices (futuro), OpenTelemetry vira necessário. Mas hoje é over-engineering.

### Alt 4 — Apenas métricas (Prometheus), sem logs estruturados

**Rejeitado.**
Métricas mostram *que* algo aconteceu (ex: "5 bookings criados na última hora"), mas não *por quê* ou *como*. Para debugar um bug específico ("por que o Booking X falhou?"), logs são essenciais. Métricas complementam logs, não substituem.

---

## References

- **log4cats:** https://typelevel.org/log4cats/
- **Logback:** https://logback.qos.ch/
- **logstash-logback-encoder:** https://github.com/logfellow/logstash-logback-encoder
- **Cats Effect IOLocal (correlation context):** https://typelevel.org/cats-effect/docs/core/io-local
- **Twelve-Factor App: Logs as event streams:** https://12factor.net/logs

---

## Notas finais

Esta decisão prioriza **qualidade de produção desde o MVP**. O custo de setup (~2h) é baixo comparado ao custo de debugar problemas em produção sem logs adequados (horas/dias).

A escolha de log4cats + Logback JSON é reversível (pode trocar backend), mas a decisão de **logar eventos de negócio explicitamente** é arquitetural — define quais informações o sistema considera importantes o suficiente para persistir.

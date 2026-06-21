# ADR #9 — Documentação da API: Tapir + OpenAPI + versionamento via prefixo no router

- **Status:** Accepted
- **Date:** 2026-06-20
- **Deciders:** Cleiton

---

## Context

A API Locarya é http4s puro com rotas definidas por recurso (`ItemRoutes`, `BookingRoutes`, etc.). Nenhuma biblioteca de documentação está integrada. O projeto está em fase pré-consumo — nenhum frontend ou cliente externo consome as rotas ainda. Dois projetos separados (dashboard e storefront) serão desenvolvidos em seguida e precisarão de um contrato de API claro.

Público-alvo da documentação: desenvolvedores internos (referência + onboarding).

Decisões em aberto:
1. Como gerar a especificação OpenAPI?
2. Como versionar a API?
3. Como servir o Swagger UI?
4. MkDocs entra agora?

---

## Decisão

### 1. Fonte da verdade: Tapir (code-first)

Migrar as definições de rota de http4s puro para **Tapir**. O spec OpenAPI é gerado automaticamente a partir das definições Tapir — sem YAML manual, sem risco de drift entre código e documentação.

Tapir gera `HttpRoutes[F]` nativamente, portanto a arquitetura Tagless Final e a camada hexagonal não mudam. A mudança é apenas na *forma* de definir endpoints, não na estrutura do projeto.

**Alternativa rejeitada — OpenAPI YAML manual:** custo zero de migração inicial, mas drift inevitável à medida que as rotas evoluem. Com o projeto em fase inicial, o custo de migração para Tapir é baixo e os benefícios compostos.

### 2. Versionamento: prefixo `/api/v1` aplicado no router em `Main`

As rotas permanecem agnósticas à versão. O prefixo `/api/v1` é aplicado na composição em `Main.scala` via `Router`:

```scala
Router(
  "/api/v1" -> itemRoutes,
  "/api/v1" -> bookingRoutes,
  // ...
)
```

Quando v2 chegar, novos endpoints são montados em `/api/v2` sem modificar as definições v1.

**Alternativas rejeitadas:**
- Prefixo por endpoint: verboso, propenso a typo, duplicação.
- Prefixo compartilhado por variável no arquivo de rota: acopla os arquivos de rota ao esquema de versão.

### 3. Swagger UI: embutido no servidor, desabilitado em produção

Uma rota `/docs` serve o Swagger UI diretamente via Tapir + http4s. Controlado por variável de ambiente:

```
SWAGGER_ENABLED=true   # dev / staging
SWAGGER_ENABLED=false  # produção (rota /docs não é registrada)
```

Em produção, o spec YAML pode ser exportado no build e publicado separadamente se necessário.

### 4. MkDocs: adiado, estrutura preservada

`docs/adr/` e `CONTEXT.md` já existem com a estrutura correta para MkDocs. Nenhuma reorganização será necessária quando MkDocs for adicionado. A decisão é adiada até que o time cresça ou onboarding frequente justifique um portal navegável.

---

## Consequências

- Todas as rotas existentes precisam ser migradas de `HttpRoutes.of[F]` puro para definições Tapir antes de os projetos de frontend começarem.
- A migration é uma breaking change de URL (`/dashboard/items` → `/api/v1/dashboard/items`) — sem impacto pois nenhum consumidor existe ainda.
- O spec OpenAPI estará sempre em sincronia com o código por construção.
- Expor `/docs` em staging permite que os times de dashboard e storefront consultem o contrato da API sem acesso ao código-fonte.

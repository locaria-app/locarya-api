# ADR 0010 — Image Storage Approach (Cloudflare R2, Presigned Upload)

**Status:** Accepted  
**Date:** 2026-07-06

## Context

Item and Combo photos today are `imageUrls: List[String]` — the API only accepts URLs that already exist somewhere; there's no way for a Locador to turn a file picked in the dashboard's 5-slot image grid into a hosted URL. `CreateItemBody`/`UpdateItemBody` require `imageUrls` at creation time, meaning uploads must complete *before* the Item (or Combo) exists. There is no outbound storage port in `domain.ports` and no adapter in `adapters.external`. The closest precedent is `AsaasGateway` (port) + `AsaasClientLive` (real adapter) + `AsaasGatewayStub` (test stub).

The Coolify deployment currently only runs Postgres (see `docker-compose.yml`) — no object storage is provisioned.

## Decision

**Provider:** Cloudflare R2, chosen fresh (no existing bucket/self-hosted alternative to migrate from). Zero egress fees matter here because Item/Combo images are served to every storefront visitor; R2 also avoids adding a stateful service (MinIO/Garage) to the Coolify box to operate and back up.

**Upload pattern:** presigned PUT. The API issues a short-lived signed URL; the dashboard uploads the file directly to R2. This avoids buffering large file bodies inside the http4s/Cats Effect process — an architectural concern independent of which storage provider sits behind the port. Consequences: the upload is a two-step flow (`presign` → client `PUT`s directly to R2) rather than a single proxy endpoint, and the bucket needs CORS configured to accept the browser's direct `PUT` from the dashboard's origin (a proxy-upload endpoint wouldn't need this, since the browser would never talk to R2 directly).

**Object key scoping:** `providers/{providerId}/uploads/{uuid}.{ext}`, generated entirely server-side. Since the Item/Combo doesn't exist yet at upload time, the key can't be scoped by `itemId`/`comboId` — it's scoped by the authenticated `providerId` from the JWT instead. The API returns both the presigned upload URL and the final public URL in the same response.

**Public access:** a custom domain already managed on Cloudflare (e.g. `cdn.locarya.com.br`) is bound to the bucket, rather than the `*.r2.dev` public subdomain — Cloudflare itself documents `r2.dev` as unsuitable for production (no SLA, no guaranteed cache, undocumented rate limits). The resulting URLs (`https://cdn.locarya.com.br/providers/...`) already satisfy `URL.fromString`'s existing `https?://` pattern — no change needed to `URL` or `ItemImage.create`.

**Validation:** enforced only at presign time, not after upload. The presign request carries a `contentType`; the API rejects anything outside an allowlist (`jpeg`/`png`/`webp`) and embeds `Content-Type` and a max `Content-Length-Range` as conditions on the signed PUT. This doesn't guarantee the client didn't lie, but it covers the normal case without needing an async post-upload verification step.

**Config:** a new `R2Config` case class added to `AppConfig`, sourced through `application.conf` with `${?ENV_VAR}` overrides — the same pattern as `database`/`http`/`jwt`. This is a deliberate deviation from `AsaasConfig`/`AsaasClientLive`, which read `sys.env` directly and bypass `AppConfig` entirely; that shortcut is an existing inconsistency, not a convention to repeat.

**Port shape** (stub only, no implementation — unblocks issue #141):

```scala
package com.locarya.domain.ports

trait ImageStorageGateway[F[_]]:
  def presignUpload(providerId: ProviderId, contentType: String): F[PresignedUpload]
```

`PresignedUpload` carries the object key, the short-lived upload URL, the final public URL, and the expiry. No `delete` method: replaced or abandoned uploads are left as orphaned objects in R2 in the MVP, consistent with the repository convention of no hard deletes ([ADR 0008](./0008-soft-delete-policy.md)) and with accepting abandoned-upload cleanup as out of scope for now. Storage cost at MVP scale doesn't justify the extra orchestration in `ItemService`/`ComboService`.

## Consequences

- Issue #141 can implement `S3ImageStorageLive` (real adapter, R2's S3-compatible API) and `ImageStorageGatewayStub` (test double) against this port immediately, following the `AsaasGateway` shape.
- Orphaned objects accumulate in R2 with no automated cleanup; acceptable at MVP scale, revisit if storage cost or clutter becomes a problem.
- The custom domain must be bound to the bucket as a prerequisite for #141 (already available on Cloudflare — just needs the binding configured).
- CORS must be configured on the R2 bucket to allow `PUT` from the dashboard's origin — a one-time infra setup, not recurring complexity, but a prerequisite for #141.
- `AsaasConfig`'s direct-`sys.env` pattern remains as legacy inconsistency; new integrations should follow `R2Config`'s `AppConfig`-routed pattern instead.

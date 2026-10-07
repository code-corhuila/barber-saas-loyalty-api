# barber-saas-loyalty-api

> loyalty bounded context: service API

Part of the **Barber Saas** distributed system — team `barber-saas`, Grupo 2.
Governance and documentation live in [`barber-saas-docs`](https://github.com/code-corhuila/barber-saas-docs).

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `barber-saas-docs`.

---

## BarberSaaS — what this repository is

The loyalty service: each barbershop's sticker program, its clients' cards and their history, and
the coupons a redemption issues (`07-api/contracts/openapi/loyalty-service.yaml`, HU-LOY-001 #9).
Hexagonal, three Maven modules (ADR-012, annex C): `loyalty-core` (domain and use cases, no
Spring), `loyalty-adapters` (HTTP in and out, JDBC, RS256 validation) and `loyalty-app`
(composition root). It never migrates its schema: that is `barber-saas-loyalty-db`.

| Operation | Who |
|---|---|
| `GET /api/v1/loyalty/config` · `PUT …/config` | read: staff and clients · set: `ADMIN_BARBERSHOP` |
| `GET /api/v1/loyalty/cards?clientId&canRedeem`, `GET …/cards/{id}`, `GET …/cards/{id}/transactions?type` | staff; a client only their own card |
| `GET /api/v1/loyalty/cards/me` | `CLIENT` |
| `POST /api/v1/loyalty/stickers` · `POST …/redemptions` (`Idempotency-Key` required) | `ADMIN_BARBERSHOP`, `BARBER` |
| `GET /api/v1/loyalty/coupons?clientId&status`, `GET …/coupons/{id}`, `POST …/coupons/{id}/use` | staff; a client only their own |
| `GET /internal/v1/outbox-events`, `POST …/{id}/published`, `POST …/{id}/failed` (internal network) | the service token of `barber-saas-worker` only |
| `GET /health` | liveness, no token |

Rules: a sticker adds one and the card is created with the first (DEC-LOY-03); a manual sticker may
name an appointment, checked through appointment-api, and an appointment gets one sticker at most
(`uq_loyalty_transaction_sticker_per_appointment`). A redemption needs an **active** rule and at
least its threshold of stickers, and in one transaction subtracts them, records the transaction and
issues one `ACTIVE` coupon (DEC-LOY-02). A coupon is used once, on an appointment of the same
client. Counts change by deltas, so concurrent requests never lose a sticker nor redeem twice. The
tenant comes **only** from the token: another barbershop's card or coupon answers `404`.

**Events (DEC-LOY-04, ADR-016):** every sticker writes `StickerGranted` and every redemption
`RewardRedeemed` to `loyalty.outbox_event` in the same transaction; `barber-saas-worker` reads them
through the internal operations and delivers them to notifications.

### How to start it

As part of the platform: `./scripts/up.sh dev` in `barber-saas-infra-postgres`. Alone, without a
database (in-memory repository):

```bash
mvn -B -DskipTests package
JWT_PUBLIC_KEY="$(cat ../barber-saas-infra-postgres/keys/jwt-public.pem)" \
APPOINTMENT_API_URL=http://localhost:8083 \
  java -jar loyalty-app/target/loyalty-app-0.1.0.jar
```

### Where the data is

Schema `loyalty` of the shared PostgreSQL instance, as `loyalty_app` (`DATABASE_URL`,
`DATABASE_USER`, `DATABASE_PASSWORD`; see `.env.example`), never as the administrator.

### How it is tested

`mvn -B verify` (no Docker needed): the card and the coupon, the use cases with fake ports, the
appointment-api client against a stub HTTP server, and the HTTP contract over the whole service
(annex C) with a stand-in for appointment-api, including cross-barbershop tests. The JDBC repository
is also tested against a database migrated by `barber-saas-loyalty-db` when `TEST_DATABASE_URL`,
`TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD` are set.

### What is missing

- **The automatic sticker** (`POST /internal/v1/events` with `AppointmentCompleted`, DEC-LOY-01):
  waiting for who `grantedByUserId` is (barber-saas-docs#88).
- `RewardRedeemed` carries `couponId` but no `couponCode` (#88). A client's existence is not
  checked, and the booking flow's service token cannot use a coupon yet (#88).

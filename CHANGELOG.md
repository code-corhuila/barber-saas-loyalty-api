# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2026-10-08

User stories: code-corhuila/barber-saas-docs#4, code-corhuila/barber-saas-docs#9, code-corhuila/barber-saas-docs#59

### Added

- domain: add the domain errors and the text length rules
- domain: add the barbershop's reward rule
- domain: add the coupon that is used only once
- domain: add the card that grants stickers and redeems atomically
- application: declare the caller, its tenant and the errors of the use cases
- application: declare the clock, ids, idempotency keys and dependency failures
- application: declare the loyalty use cases, their repository and the outbox event
- application: ask appointment-api for an appointment and its client
- application: write StickerGranted and RewardRedeemed with their payloads
- application: configure the reward, grant stickers, redeem and use coupons
- persistence: add pages, idempotency keys, the clock and the ids
- persistence: store cards, stickers, redemptions and coupons in postgresql by deltas
- persistence: keep the loyalty data in memory when there is no database
- http: answer every error with the shared envelope and its trace id
- http: validate the rs256 token and take the tenant only from it
- http: check bodies, paging and idempotency keys against the contract
- http: ask appointment-api for an appointment and its client
- app: compose the repository, the appointment-api client and the use cases
- http: answer cards, transactions and coupons with the contract's schemas
- http: expose the reward rule, the cards and their history
- http: expose stickers, redemptions and coupons
- application: hand loyalty's outbox to the worker as event envelopes
- persistence: read pending loyalty events and record their delivery or failure
- http: expose loyalty's outbox relay to barber-saas-worker on the internal network
- deploy: build the service image and compose it with its own database user
- application: grant the sticker of a completed appointment once, in the name of who completed it
- persistence: record each processed event with its sticker in one transaction
- http: receive the worker's events on the internal network
- events: use the coupon applied at booking from AppointmentCreated
- persistence: use the coupon and record the event in one transaction

### Documentation

- readme: point the header to Barber Saas and barber-saas-docs
- readme: explain the operations, the rules, how to start it and test it
- readme: explain the automatic sticker and its outcomes

### Tests

- ci: build and test every pull request with java 21
- app: start the whole service against an appointment-api stub
- persistence: refuse a redelivered coupon event the same way in both adapters

### Maintenance

- build: ignore build output, ide files, env files and keys
- github: add the pull request template
- github: track the story environment on the board
- build: add the maven parent on spring boot 3.5 and java 21
- build: add the core module without any framework dependency
- build: add the adapters module on spring web and jdbc
- build: add the app module that composes the service
- config: list the environment variables without any value

[2.0.0]: https://github.com/code-corhuila/barber-saas-loyalty-api/releases/tag/v2.0.0

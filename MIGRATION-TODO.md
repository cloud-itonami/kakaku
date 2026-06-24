# Migration TODO

**Status**: 🔄 TRANSFORM — seed copied 2026-05-21, codemod pending.

**Codemod required**: commerce — Stripe→USDC+TitheRouter, SBT carve-out

## Substrate-boundary checks

This actor SDK was copied verbatim from `etzhayyim-root/20-actors/kakaku`.
Following must be remediated:

- [ ] Replace direct `@atproto/api` / `viem` / IPFS / Signal client imports with `@etzhayyim/sdk`.
- [ ] Strip RisingWave / Postgres / Kysely → AT MST + IPFS + Base L2 anchor.
- [ ] Strip Stripe / PayPal / fiat → USDC + ERC-4337 + `etzhayyim-tithe-router`.
- [ ] Remove 3rd-party ad / GA4 / Meta Pixel.
- [ ] DID-bind authentication (did:web:etzhayyim.com + did:plc + WebAuthn + Adherent SBT).
- [ ] Verify against Charter Rider v2.0 §2(a)-(h).

## Reference

- ADR-2605192100 / 2605192115 / 2605192200
- `/CLAUDE.md` § Substrate boundary

# kakaku repository guidance

This is an independent west-managed price-observation actor. Keep EDN canonical,
external JSON/BPMN under `wire/`, runtime code in `src/kakaku`, and tests in
`test/kakaku`. Preserve buyer-transparency, non-speculation, provenance, passive
ingest, and Murakumo-only gates. Do not restore Python parity, Go/TinyGo, wasm
builds, JSON-LD manifests, or shell runners. Verify with `kbb -M:test` and audits.

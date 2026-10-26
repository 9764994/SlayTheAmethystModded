# AI patch catalogue network verification

`AgentPatchCatalogueNetworkTest` connects the production catalogue fetch/parser/cache code to
the production Watt resolver and acceleration interceptor over local MockWebServer sockets.
The production entry point and test seam use the same fetch implementation; only the client,
cache location and URL are injected. No public service is required to run these tests.

## Covered scenarios

- Prefer the better probed forward node or official origin.
- Under `BEST_PATH`, restore the HTTP-confirmed route in a fresh client without refetching rules;
  do not persist a rejected HTTP 200 node as the successful path.
- Exercise the production-default `RMBGAME_FIRST` strategy with the real GitHub user-content
  profile, which has no bundled hop and therefore still discovers rules.
- Recover from connection refusal, delayed headers, delayed body, and incomplete response body.
- Reject HTTP 200 HTML, malformed JSON, unsupported schema, missing `mods`, empty responses,
  and an index exceeding 4 MiB; try another candidate instead of poisoning the cache.
- Recover from HTTP 403/429/502/503 and from both forward nodes failing before official fallback.
- Recover through a forward node when the preferred official origin returns invalid content.
- Use the official origin if the rule service returns malformed data.
- On total failure, preserve an existing index byte-for-byte, leave no temporary index file,
  create no index if there was no cache, and allow a subsequent request to recover.

## Fix exercised

Previously, routing marked HTTP 200 as successful before catalogue JSON was read/validated.
A proxy serving an HTML error page, malformed JSON or a stalled body could therefore be selected
as healthy without trying another route. Catalogue requests now carry an opt-in
`AcceleratedResponseValidator`: the interceptor validates a bounded body peek before confirming
the candidate, closes rejected responses, and follows the existing fallback path. Other requests
without this tag retain their previous behavior. Parsing/validation still precedes atomic cache
replacement. Redirects are followed before applying JSON validation.

## Run

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.mods.AgentPatchCatalogueNetworkTest' \
  --tests 'io.stamethyst.backend.github.*' \
  --tests 'io.stamethyst.backend.steamcloud.AccelerationStrategyRouteResolutionTest' \
  --tests 'io.stamethyst.backend.mods.AgentPatchCatalogueServiceTest' \
  --tests 'io.stamethyst.backend.mods.CatalogueRequestStateMachineTest' \
  --tests 'io.stamethyst.backend.network.*'
```

This suite contains 57 tests, including 13 new catalogue network tests. Initial verification:
57 passed, no failures or errors. Probe results are deterministic injections; the HTTP requests,
timeouts, response parsing and filesystem cache writes are real. Loopback HTTP is intentional:
this does not claim to validate live Watt/GitHub availability, DNS, TLS certificates, or on-device
Android client wiring. Existing transport tests cover HTTPS-only enforcement separately.

`RMBGAME_FIRST` currently bypasses successful-path persistence even for profiles without bundled
hops; persistence assertions deliberately use `BEST_PATH` rather than claiming both strategies
have identical cache behavior.

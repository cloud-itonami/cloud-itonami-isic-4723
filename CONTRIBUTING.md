# Contributing to cloud-itonami-isic-4723

Contributions should preserve the actor's scope: specialized tobacco
retail back-office coordination only, with CRITICAL exclusions of
age-verification-override finalization, direct age-verification/
ID-scanning terminal actuation, and tobacco-retail-license suspension/
revocation (see README.md).

- All code must be `.cljc` (portable Clojure, no JVM-only constructs).
- Tests must pass: `clojure -M:test`
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Finalize an age-verification override or otherwise stand in for an
  age-verification authority.
- Directly operate or control an age-verification/ID-scanning terminal
  (e.g. override an ID check, disable an age gate).
- Suspend or revoke a tobacco retail license.
- Perform tobacco-retail-licensing-authority enforcement (inspection
  clearance, excise/customs enforcement, compliance enforcement).

Contributions that cross these boundaries will be rejected.

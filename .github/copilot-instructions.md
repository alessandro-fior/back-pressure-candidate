# Candidate Repository Instructions

This repository is intentionally under-documented. Before making changes, inspect the running system and understand how the current services interact.

You should first use the running application to understand the domain problem it is demonstrating: producer / consumer flow, bounded capacity, backpressure, saturation, and recovery.

## Working style

- prefer small, reviewable changes
- preserve the existing runtime topology unless a change is clearly justified
- keep the build and run workflow reproducible
- update tests when behavior changes
- update documentation when behavior, runtime expectations, or dependencies change

## Priorities

- correctness over speed
- clear contracts over hidden coupling
- minimal local-only solutions over platform expansion
- explicit error handling over silent fallbacks

## AI usage guidance

- use AI to inspect the codebase, trace behavior, and propose bounded changes
- verify generated changes before keeping them
- do not accept speculative rewrites without understanding the impact
- use AI to help reconstruct architecture and runtime flow, not only to generate code

## Scope guardrails

- do not introduce cloud services
- do not add unrelated infrastructure
- do not rewrite the system into a different architecture just to add OCR

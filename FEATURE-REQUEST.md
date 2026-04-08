# Feature Request: Replace the Dummy Decoder with Real OCR

## Context

The current decoder worker simulates the recognition step. It returns realistic-looking results and timing metadata, but it does not use a real OCR library to read the generated PNG payloads.

The next feature is to replace that dummy recognition step with a real OCR-backed implementation while keeping the system small, local, and understandable.

## Goal

Update the decoder worker so that it performs real OCR on the generated numbered images and returns the decoded number based on actual image recognition.

## Assignment emphasis

This request is intentionally not only about "making OCR work".

A strong submission should show that you can:

- inspect the current system and understand how it behaves
- identify the correct integration point for the change
- preserve the surrounding runtime behavior and contracts
- document what you discovered and why you changed what you changed

Reverse engineering and disciplined integration are more important than squeezing out maximum OCR sophistication.

## Requirements

Your implementation must:

- keep the existing local three-service topology unless you provide a strong justification for changing it
- preserve the current HTTP contracts unless a change is clearly documented and justified
- keep the system runnable through the repository-managed Docker-first workflow
- continue to expose recent results, confidence, and processing metadata
- keep backpressure behavior visible in the running system
- include tests for the new OCR-backed behavior
- include documentation that explains your design decisions, limitations, and operational changes

## Constraints

Please keep the solution intentionally minimal:

- local only
- no cloud OCR services
- no external managed infrastructure
- no database unless absolutely necessary
- no broad architecture rewrite

## What is acceptable

A successful solution does not need perfect OCR accuracy.

It is acceptable if:

- accuracy is good enough for the generated number images
- confidence values are derived or mapped in a reasonable way
- failures are surfaced explicitly and handled consistently
- the Dockerized workflow remains usable by another developer

## What will be evaluated

Your submission will be evaluated on:

- understanding of the current codebase
- quality of the reverse engineering you document
- correctness and safety of the integration
- preservation of contracts and runtime behavior
- clarity of the changes
- test quality
- documentation quality
- practical and disciplined use of AI assistance

## Suggested questions to answer in your documentation

- Where did you integrate the OCR library?
- What did you learn about the current system before changing it?
- How does the decoder map OCR output into the existing result contract?
- How did you keep the Docker workflow reproducible?
- What trade-offs did you make around accuracy, latency, and complexity?
- What limitations remain?

# Back Pressure Candidate Assignment

This repository contains a small local system that demonstrates a classic producer / consumer problem under load.

The system continuously creates small image-based jobs and sends them to a downstream decoder. When the decoder cannot keep up, the system is expected to show visible pressure and rebalance over time.

At a high level, the application is about:

- workload production
- workload consumption
- bounded processing capacity
- feedback from a slower consumer to a faster producer
- observable backpressure and recovery

You are not being given a full architecture guide on purpose. Part of the exercise is to inspect the codebase, run the system, observe the behavior, and reconstruct how the current implementation works.

## What the application does

The running application lets you observe how throughput, queueing, rejection, and recovery behave when a producer generates more work than a consumer can process.

In practical terms:

- the system produces numbered image jobs
- the system attempts to decode those jobs downstream
- the dashboard shows live metrics and recent results
- different scenarios make the system behave as low-pressure, balanced, or saturated
- the UI lets you reconfigure the running scenario and observe how the system settles again

Your task is to understand this behavior first, then implement the feature request described in `FEATURE-REQUEST.md`.

## What this assignment is evaluating

This assignment puts significant weight on:

- reverse engineering an unfamiliar codebase
- understanding runtime behavior from code and observation
- using AI effectively but critically
- making a bounded change without breaking the existing system
- writing useful documentation and tests

The feature implementation matters, but it is not the only thing being evaluated.

## What you can do from the UI

After the local stack is running, open:

- `http://localhost:8080`

The dashboard lets you:

- view the current scenario
- start and stop the workload
- switch between draft presets
- apply changes while the system is already running
- watch generator and decoder metrics update live
- inspect recent decode results

You should use the UI first before reading too much code. It is the fastest way to build intuition about what the system is trying to demonstrate.

## Recommended quick walkthrough

1. Start the local stack as described in `DEVELOPMENT.md`.
2. Open the dashboard at `http://localhost:8080`.
3. Load the `Saturation demo` preset.
4. Click `Apply & Start`.
5. Watch queue depth, rejected count, and saturation state change.
6. Switch back to a lighter scenario and apply it live.
7. Stop the scenario.

While doing this, pay attention to:

- requested rate versus accepted/completed rate
- queue depth
- rejection counts
- the saturation state shown in the dashboard
- how the system behaves when pressure is increased and then reduced

## What you are expected to learn before changing code

Before implementing the feature request, you should be able to explain in your own words:

- what problem the application is demonstrating
- what the dashboard is measuring
- how the system shows overload and recovery
- where the current dummy decoding behavior lives
- what must remain stable while you add the requested feature

## Expected assignment workflow

You are expected to:

- reverse engineer the current implementation
- understand the application domain and runtime behavior
- understand the runtime flow and service responsibilities
- use AI assistance where useful
- implement the requested feature
- add or improve tests
- produce supporting documentation for your changes

## Deliverables

At minimum, your submission should include:

- the requested feature implementation
- tests for the changed behavior
- documentation that explains:
  - what you discovered about the current system
  - how you implemented the feature
  - what trade-offs and limitations remain

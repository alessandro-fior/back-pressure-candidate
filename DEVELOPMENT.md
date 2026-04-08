# Development Guide

This project is intended to run locally with Docker as the main host prerequisite.

The easiest way to succeed in this assignment is:

1. bring the system up locally,
2. use the dashboard to understand what it does,
3. confirm you can stop and restart the scenario,
4. only then begin deeper code inspection.

## Host prerequisites

Install:

- Docker Desktop or a compatible local Docker runtime
- Git

You do not need a permanent local installation of Scala, Node.js, Pulumi, or TypeScript if you use the Docker-first workflow below.

## Preferred workflow

Use the repository-managed toolchain:

```bash
tooling/scripts/doctor
tooling/scripts/dev build
tooling/scripts/dev infraUp
```

When you are done:

```bash
tooling/scripts/dev infraDown
```

If `tooling/scripts/doctor` reports a problem, fix that first before going further.

Important:

- the first `tooling/scripts/dev infraUp` run may take several minutes
- this is expected, because the workflow may need to build images, download dependencies, and materialize local tooling state
- slower machines may take noticeably longer than faster ones
- do not assume the command is stuck unless it exits with an error

## Bring-up checklist

Use this sequence when you run the project for the first time:

```bash
tooling/scripts/doctor
tooling/scripts/dev build
tooling/scripts/dev infraUp
```

Then verify:

- `http://localhost:8080/actuator/health` returns `UP`
- `http://localhost:8080` opens the dashboard
- the dashboard can start and stop a scenario

When you finish experimenting:

```bash
tooling/scripts/dev infraDown
```

## Alternative local workflow

If you already have a compatible host setup, you can also use:

```bash
./gradlew --no-daemon build
./gradlew --no-daemon infraUp
./gradlew --no-daemon infraDown
```

## Useful commands

Build and test:

```bash
tooling/scripts/dev build
tooling/scripts/dev test
```

Start the local stack:

```bash
tooling/scripts/dev infraUp
```

Stop and remove the local stack:

```bash
tooling/scripts/dev infraDown
```

Create a candidate delivery bundle from the maintainer repository:

```bash
tooling/scripts/create-candidate-bundle
```

## Local endpoints

Once the stack is running:

- control plane dashboard: `http://localhost:8080`
- workload generator: `http://localhost:8081`
- decoder worker: `http://localhost:8082`

## First-use walkthrough

Once the stack is running:

1. Open `http://localhost:8080`.
2. Read the live scenario summary.
3. Load the `Saturation demo` preset.
4. Click `Apply & Start`.
5. Wait a few seconds and observe queue depth, recent results, and saturation state.
6. Load a lighter preset or edit the draft manually.
7. Click `Apply Live` and observe the system recover.
8. Click `Stop`.

This is the intended starting point for understanding the assignment domain.

## Troubleshooting

If the stack does not start:

- make sure Docker Desktop is running
- rerun `tooling/scripts/doctor`
- retry `tooling/scripts/dev infraUp`

If the dashboard does not load:

- confirm `http://localhost:8080/actuator/health` returns `UP`
- check whether the `infraUp` command completed successfully

If you change code and want a clean local rerun:

```bash
tooling/scripts/dev infraDown
tooling/scripts/dev build
tooling/scripts/dev infraUp
```

## Notes for implementation

- Keep the runtime local-only.
- Preserve the existing service topology unless you have a strong reason to change it.
- Prefer small, reviewable changes.
- Keep the build and runtime reproducible through the repository-managed workflow.
- If you add native OCR dependencies, make sure they work through the Dockerized path, not only on your host machine.

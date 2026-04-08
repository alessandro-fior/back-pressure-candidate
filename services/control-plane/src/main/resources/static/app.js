const POLL_INTERVAL_MS = 2000;

const DEFAULT_SCENARIO = {
    scenarioId: "happy-path-demo",
    targetJobsPerSecond: 6.0,
    numberRange: { min: 1, max: 999 },
    imageWidthPx: 96,
    imageHeightPx: 48,
    maxInFlight: 8,
    workerQueueCapacity: 24,
    workerParallelism: 4,
    baseDecodeLatencyMs: 90,
    latencyJitterMs: 20,
};

const PRESETS = {
    default: {
        scenarioId: "happy-path-demo",
        targetJobsPerSecond: 6.0,
        maxInFlight: 8,
        workerQueueCapacity: 24,
        workerParallelism: 4,
        baseDecodeLatencyMs: 90,
        latencyJitterMs: 20,
        numberRange: { min: 1, max: 999 },
    },
    lightPressure: {
        scenarioId: "light-pressure-demo",
        targetJobsPerSecond: 10.0,
        maxInFlight: 10,
        workerQueueCapacity: 12,
        workerParallelism: 2,
        baseDecodeLatencyMs: 220,
        latencyJitterMs: 30,
        numberRange: { min: 1, max: 999 },
    },
    saturationDemo: {
        scenarioId: "saturation-demo",
        targetJobsPerSecond: 24.0,
        maxInFlight: 16,
        workerQueueCapacity: 8,
        workerParallelism: 1,
        baseDecodeLatencyMs: 350,
        latencyJitterMs: 50,
        numberRange: { min: 1, max: 999 },
    },
};

let pollInterval = null;
let currentOverview = null;
let currentScenario = null;
let draftScenario = null;
let isDraftDirty = false;
let isActionInFlight = false;

document.addEventListener("DOMContentLoaded", () => {
    document.getElementById("refresh-interval").textContent = String(POLL_INTERVAL_MS / 1000);
    bindScenarioForm();
    startPolling();
});

function bindScenarioForm() {
    const form = document.getElementById("scenario-form");
    form.addEventListener("submit", (event) => event.preventDefault());
    form.querySelectorAll("input").forEach((input) => {
        input.addEventListener("input", onDraftInputChanged);
    });
}

function startPolling() {
    if (pollInterval !== null) {
        return;
    }
    fetchOverview();
    pollInterval = window.setInterval(fetchOverview, POLL_INTERVAL_MS);
}

async function fetchOverview() {
    try {
        const response = await fetch("/api/v1/overview");
        if (!response.ok) {
            throw new Error(await readErrorMessage(response, `Overview request failed with HTTP ${response.status}`));
        }
        const data = await response.json();
        currentOverview = data;
        currentScenario = normalizeScenario(data.scenario);
        updateDashboard(data);
        updateLastUpdateTime();
        clearScenarioMessageIfLoading();
    } catch (error) {
        console.error("Failed to fetch overview", error);
        setScenarioMessage(
            "Unable to refresh the dashboard. Check whether the control plane and downstream services are reachable.",
            "error",
        );
        const indicator = document.getElementById("status-indicator");
        indicator.className = "status-badge error";
        indicator.textContent = "ERROR";
    }
}

function updateDashboard(data) {
    updateStatus(data.status);
    updateLiveScenario(data.scenario);
    if (!isDraftDirty || draftScenario === null) {
        setDraftScenario(data.scenario, false);
    } else {
        updateDraftIndicators();
        updateDraftHint();
    }
    updateGeneratorMetrics(data.generatorMetrics);
    updateDecoderMetrics(data.workerMetrics);
    updateResults(data.recentResults);
}

function updateStatus(status) {
    const indicator = document.getElementById("status-indicator");
    indicator.className = `status-badge ${status.toLowerCase()}`;
    indicator.textContent = status.toUpperCase();

    const idle = status === "idle";
    document.getElementById("start-btn").disabled = !idle || isActionInFlight;
    document.getElementById("stop-btn").disabled = idle || isActionInFlight;
    document.getElementById("apply-btn").disabled = isActionInFlight;
    document.getElementById("reset-draft-btn").disabled = isActionInFlight || currentScenario === null;

    const applyStartButton = document.getElementById("apply-start-btn");
    applyStartButton.disabled = isActionInFlight;
    applyStartButton.innerHTML = idle
        ? '<span class="btn-icon">↻</span> Apply &amp; Start'
        : '<span class="btn-icon">↻</span> Apply Live';
}

function updateLiveScenario(sourceScenario) {
    const scenario = normalizeScenario(sourceScenario);
    document.getElementById("scenario-id").textContent = scenario.scenarioId;
    document.getElementById("target-rate").textContent = formatNumber(scenario.targetJobsPerSecond, 1);
    document.getElementById("max-inflight").textContent = String(scenario.maxInFlight);
    document.getElementById("queue-capacity").textContent = String(scenario.workerQueueCapacity);
    document.getElementById("worker-parallelism").textContent = String(scenario.workerParallelism);
    document.getElementById("base-latency").textContent = String(scenario.baseDecodeLatencyMs);
    document.getElementById("latency-jitter").textContent = String(scenario.latencyJitterMs);
    document.getElementById("number-range-min").textContent = String(scenario.numberRange.min);
    document.getElementById("number-range-max").textContent = String(scenario.numberRange.max);

    const liveHint = calculatePressureHint(scenario);
    document.getElementById("live-capacity").textContent = formatNumber(liveHint.capacity, 1);
    document.getElementById("live-pressure").textContent = formatNumber(liveHint.ratio, 2);
    document.getElementById("live-pressure-story").textContent = liveHint.story;
}

function updateGeneratorMetrics(metrics) {
    document.getElementById("gen-requested").textContent = formatNumber(metrics.requestedRate, 1);
    document.getElementById("gen-accepted").textContent = formatNumber(metrics.acceptedRate, 1);
    document.getElementById("gen-completed").textContent = formatNumber(metrics.completedRate, 1);
    document.getElementById("gen-inflight").textContent = String(metrics.inFlight ?? 0);
    document.getElementById("gen-rejected").textContent = String(metrics.rejectedCount ?? 0);
    applyStateBadge(document.getElementById("gen-state"), metrics.saturationState);
}

function updateDecoderMetrics(metrics) {
    document.getElementById("dec-accepted").textContent = formatNumber(metrics.acceptedRate, 1);
    document.getElementById("dec-completed").textContent = formatNumber(metrics.completedRate, 1);
    document.getElementById("dec-queue").textContent = String(metrics.queueDepth ?? 0);
    document.getElementById("dec-inflight").textContent = String(metrics.inFlight ?? 0);
    document.getElementById("dec-latency").textContent = formatNumber(metrics.p95LatencyMs, 1);
    document.getElementById("dec-rejected").textContent = String(metrics.rejectedCount ?? 0);
    applyStateBadge(document.getElementById("dec-state"), metrics.saturationState);
}

function applyStateBadge(element, state) {
    const value = (state || "unknown").toLowerCase();
    element.textContent = value;
    element.className = `metric-value state-badge state-${value}`;
}

function updateResults(results) {
    const tbody = document.getElementById("results-body");
    if (!Array.isArray(results) || results.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="no-data">No results yet. Start a scenario to see results.</td></tr>';
        return;
    }

    tbody.innerHTML = results.slice(0, 15).map((result) => {
        const decodedNumber = result.decodedNumber === null || result.decodedNumber === undefined
            ? "-"
            : result.decodedNumber;
        const matches = result.decodedNumber !== null && result.decodedNumber === result.expectedNumber;
        const decodedClass = result.decodedNumber === null || result.decodedNumber === undefined
            ? "missing"
            : matches
                ? "match"
                : "mismatch";
        const confidence = `${Math.round((result.confidence || 0) * 100)}%`;
        const completedAt = result.processing?.completedAt
            ? new Date(result.processing.completedAt).toLocaleTimeString()
            : "-";
        const totalLatency = result.processing?.totalLatencyMs ?? "-";
        return `
            <tr>
                <td class="job-id">${shortJobId(result.jobId)}</td>
                <td>${result.expectedNumber}</td>
                <td class="${decodedClass}">${decodedNumber}</td>
                <td>${confidence}</td>
                <td class="${result.status === "decoded" ? "status-decoded" : "status-failed"}">${result.status}</td>
                <td>${totalLatency === "-" ? "-" : `${totalLatency}ms`}</td>
                <td class="timestamp">${completedAt}</td>
            </tr>
        `;
    }).join("");
}

function setDraftScenario(sourceScenario, dirty) {
    draftScenario = normalizeScenario(sourceScenario);
    syncDraftForm(draftScenario);
    isDraftDirty = dirty;
    updateDraftIndicators();
    updateDraftHint();
}

function syncDraftForm(scenario) {
    document.getElementById("form-scenario-id").value = scenario.scenarioId;
    document.getElementById("form-target-rate").value = scenario.targetJobsPerSecond;
    document.getElementById("form-max-inflight").value = scenario.maxInFlight;
    document.getElementById("form-queue-capacity").value = scenario.workerQueueCapacity;
    document.getElementById("form-worker-parallelism").value = scenario.workerParallelism;
    document.getElementById("form-base-latency").value = scenario.baseDecodeLatencyMs;
    document.getElementById("form-latency-jitter").value = scenario.latencyJitterMs;
    document.getElementById("form-number-range-min").value = scenario.numberRange.min;
    document.getElementById("form-number-range-max").value = scenario.numberRange.max;
}

function onDraftInputChanged() {
    draftScenario = readDraftFromForm();
    isDraftDirty = true;
    updateDraftIndicators();
    updateDraftHint();
}

function readDraftFromForm() {
    const base = normalizeScenario(draftScenario || currentScenario || DEFAULT_SCENARIO);
    return normalizeScenario({
        scenarioId: document.getElementById("form-scenario-id").value.trim(),
        targetJobsPerSecond: toNumber(document.getElementById("form-target-rate").value, base.targetJobsPerSecond),
        imageWidthPx: base.imageWidthPx,
        imageHeightPx: base.imageHeightPx,
        maxInFlight: toInteger(document.getElementById("form-max-inflight").value, base.maxInFlight),
        workerQueueCapacity: toInteger(document.getElementById("form-queue-capacity").value, base.workerQueueCapacity),
        workerParallelism: toInteger(document.getElementById("form-worker-parallelism").value, base.workerParallelism),
        baseDecodeLatencyMs: toInteger(document.getElementById("form-base-latency").value, base.baseDecodeLatencyMs),
        latencyJitterMs: toInteger(document.getElementById("form-latency-jitter").value, base.latencyJitterMs),
        numberRange: {
            min: toInteger(document.getElementById("form-number-range-min").value, base.numberRange.min),
            max: toInteger(document.getElementById("form-number-range-max").value, base.numberRange.max),
        },
    });
}

function updateDraftIndicators() {
    const draftState = document.getElementById("draft-state");
    if (!draftScenario) {
        draftState.className = "draft-state";
        draftState.textContent = "Waiting for live scenario...";
        return;
    }

    const matchesLive = currentScenario !== null && scenariosEqual(draftScenario, currentScenario);
    if (!isDraftDirty || matchesLive) {
        draftState.className = "draft-state clean";
        draftState.textContent = "Draft matches the live scenario";
    } else {
        draftState.className = "draft-state dirty";
        draftState.textContent = "Draft has unapplied changes";
    }
}

function updateDraftHint() {
    const scenario = normalizeScenario(draftScenario || currentScenario || DEFAULT_SCENARIO);
    const hint = calculatePressureHint(scenario);
    document.getElementById("draft-capacity").textContent = `${formatNumber(hint.capacity, 1)} jobs/s`;
    document.getElementById("draft-pressure").textContent = `${formatNumber(hint.ratio, 2)}x`;
    document.getElementById("draft-pressure-story").textContent = hint.story;
}

function applyPreset(presetName) {
    const base = normalizeScenario(currentScenario || DEFAULT_SCENARIO);
    const preset = PRESETS[presetName];
    if (!preset) {
        return;
    }

    setDraftScenario({
        ...base,
        ...preset,
        numberRange: {
            min: preset.numberRange.min,
            max: preset.numberRange.max,
        },
    }, true);

    highlightPreset(presetName);
    setScenarioMessage(
        `${readablePresetName(presetName)} preset loaded. Apply it to send the new pressure profile downstream.`,
        "info",
    );
}

function highlightPreset(activePresetName) {
    document.querySelectorAll(".preset-buttons .btn-chip").forEach((button) => {
        button.classList.toggle("active", button.id === `preset-${toKebabCase(activePresetName)}`);
    });
}

async function applyScenario() {
    await withActionLock(async () => {
        await applyScenarioInternal(true);
        await fetchOverview();
    });
}

async function applyAndStartScenario() {
    await withActionLock(async () => {
        const wasIdle = (currentOverview?.status || "idle") === "idle";
        await applyScenarioInternal(false);
        if (wasIdle) {
            await startScenarioInternal(false);
            setScenarioMessage("Draft applied and the scenario started. Watch the metrics settle into a new balance.", "success");
        } else {
            setScenarioMessage("Draft applied live while the scenario was already running. Watch queue depth and throughput rebalance below.", "success");
        }
        await fetchOverview();
    });
}

async function startScenario() {
    await withActionLock(async () => {
        await startScenarioInternal(true);
        await fetchOverview();
    });
}

async function stopScenario() {
    await withActionLock(async () => {
        const response = await fetch("/api/v1/scenario/current/stop", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
        });
        if (!response.ok) {
            throw new Error(await readErrorMessage(response, "Unable to stop the current scenario."));
        }
        setScenarioMessage("Stop requested. The generator is draining in-flight work.", "success");
        await fetchOverview();
    });
}

function resetDraftToLive() {
    if (!currentScenario) {
        return;
    }
    highlightPreset("");
    setDraftScenario(currentScenario, false);
    setScenarioMessage("Draft reset to the live scenario.", "info");
}

async function applyScenarioInternal(announceSuccess) {
    const scenario = readDraftFromForm();
    validateScenarioDraft(scenario);

    const response = await fetch("/api/v1/scenario/current", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(scenario),
    });
    if (!response.ok) {
        throw new Error(await readErrorMessage(response, "Unable to apply the scenario draft."));
    }

    const updatedScenario = normalizeScenario(await response.json());
    currentScenario = updatedScenario;
    draftScenario = updatedScenario;
    isDraftDirty = false;
    syncDraftForm(updatedScenario);
    updateLiveScenario(updatedScenario);
    updateDraftIndicators();
    updateDraftHint();

    if (announceSuccess) {
        const liveMessage = (currentOverview?.status || "idle") === "running"
            ? "Draft applied live. Watch the decoder and generator re-balance without restarting the stack."
            : "Draft applied to the control plane. Use Start current when you want to run it.";
        setScenarioMessage(liveMessage, "success");
    }
}

async function startScenarioInternal(announceSuccess) {
    const response = await fetch("/api/v1/scenario/current/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
    });
    if (!response.ok) {
        throw new Error(await readErrorMessage(response, "Unable to start the current scenario."));
    }
    if (announceSuccess) {
        setScenarioMessage("Scenario started. Watch the live metrics and recent results update below.", "success");
    }
}

async function withActionLock(work) {
    if (isActionInFlight) {
        return;
    }
    isActionInFlight = true;
    updateStatus(currentOverview?.status || "idle");
    try {
        await work();
    } catch (error) {
        console.error(error);
        setScenarioMessage(error.message || "The requested dashboard action failed.", "error");
    } finally {
        isActionInFlight = false;
        updateStatus(currentOverview?.status || "idle");
    }
}

function setScenarioMessage(message, tone) {
    const element = document.getElementById("scenario-message");
    element.textContent = message;
    element.className = `scenario-message ${tone}`;
}

function clearScenarioMessageIfLoading() {
    const element = document.getElementById("scenario-message");
    if (element.textContent === "Loading current scenario...") {
        element.className = "scenario-message info";
        element.textContent = "Live polling is active. Edit the draft to push the system closer to or further from saturation.";
    }
}

function updateLastUpdateTime() {
    document.getElementById("last-update").textContent = `Last update: ${new Date().toLocaleTimeString()}`;
}

function normalizeScenario(source) {
    const base = source || DEFAULT_SCENARIO;
    const numberRange = base.numberRange || DEFAULT_SCENARIO.numberRange;
    return {
        scenarioId: (base.scenarioId || DEFAULT_SCENARIO.scenarioId).trim(),
        targetJobsPerSecond: toNumber(base.targetJobsPerSecond, DEFAULT_SCENARIO.targetJobsPerSecond),
        numberRange: {
            min: toInteger(numberRange.min, DEFAULT_SCENARIO.numberRange.min),
            max: toInteger(numberRange.max, DEFAULT_SCENARIO.numberRange.max),
        },
        imageWidthPx: toInteger(base.imageWidthPx, DEFAULT_SCENARIO.imageWidthPx),
        imageHeightPx: toInteger(base.imageHeightPx, DEFAULT_SCENARIO.imageHeightPx),
        maxInFlight: toInteger(base.maxInFlight, DEFAULT_SCENARIO.maxInFlight),
        workerQueueCapacity: toInteger(base.workerQueueCapacity, DEFAULT_SCENARIO.workerQueueCapacity),
        workerParallelism: toInteger(base.workerParallelism, DEFAULT_SCENARIO.workerParallelism),
        baseDecodeLatencyMs: toInteger(base.baseDecodeLatencyMs, DEFAULT_SCENARIO.baseDecodeLatencyMs),
        latencyJitterMs: toInteger(base.latencyJitterMs, DEFAULT_SCENARIO.latencyJitterMs),
    };
}

function validateScenarioDraft(scenario) {
    if (!scenario.scenarioId) {
        throw new Error("Scenario ID must not be blank.");
    }
    if (scenario.targetJobsPerSecond <= 0) {
        throw new Error("Target jobs per second must be greater than zero.");
    }
    if (scenario.numberRange.min > scenario.numberRange.max) {
        throw new Error("Number range min must be less than or equal to max.");
    }
    if (scenario.maxInFlight < 1 || scenario.workerQueueCapacity < 1 || scenario.workerParallelism < 1) {
        throw new Error("In-flight, queue capacity, and worker parallelism must all be at least 1.");
    }
    if (scenario.baseDecodeLatencyMs < 1 || scenario.latencyJitterMs < 0) {
        throw new Error("Latency values must be positive, and jitter must not be negative.");
    }
}

function calculatePressureHint(scenario) {
    const capacity = scenario.workerParallelism * (1000 / Math.max(1, scenario.baseDecodeLatencyMs));
    const ratio = scenario.targetJobsPerSecond / Math.max(capacity, 0.1);
    let story = "The decoder should stay comfortably ahead of the producer.";

    if (ratio >= 1.15) {
        story = "The producer should outrun the decoder. Expect queue growth, 429 rejections, and a visible rebalance.";
    } else if (ratio >= 0.9) {
        story = "This is near equilibrium. Expect queue depth to move and throughput to settle toward the decoder limit.";
    } else if (ratio >= 0.55) {
        story = "The decoder should absorb most bursts, but small queues may appear during warm-up.";
    }

    return { capacity, ratio, story };
}

function scenariosEqual(left, right) {
    if (!left || !right) {
        return false;
    }
    return JSON.stringify(normalizeScenario(left)) === JSON.stringify(normalizeScenario(right));
}

function shortJobId(jobId) {
    if (!jobId) {
        return "-";
    }
    const parts = jobId.split("-");
    return parts.length > 2 ? `...-${parts[parts.length - 1]}` : jobId;
}

async function readErrorMessage(response, fallbackMessage) {
    const text = await response.text();
    if (!text) {
        return fallbackMessage;
    }
    try {
        const payload = JSON.parse(text);
        return payload.message || payload.error || fallbackMessage;
    } catch (_ignored) {
        return text;
    }
}

function formatNumber(value, decimals) {
    return Number.isFinite(Number(value)) ? Number(value).toFixed(decimals) : "-";
}

function toNumber(value, fallback) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : fallback;
}

function toInteger(value, fallback) {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) ? parsed : fallback;
}

function readablePresetName(name) {
    switch (name) {
        case "lightPressure":
            return "Light pressure";
        case "saturationDemo":
            return "Saturation demo";
        default:
            return "Default";
    }
}

function toKebabCase(value) {
    return value.replace(/([a-z])([A-Z])/g, "$1-$2").toLowerCase();
}

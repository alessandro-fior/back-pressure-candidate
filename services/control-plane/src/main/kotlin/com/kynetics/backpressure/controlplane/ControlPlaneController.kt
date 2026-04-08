package com.kynetics.backpressure.controlplane

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class ControlPlaneController(
    private val controlPlaneService: ControlPlaneService,
) {
    @GetMapping("/api/v1/overview")
    fun getOverview(): OverviewResponse = controlPlaneService.getOverview()

    @PutMapping("/api/v1/scenario/current")
    fun putCurrentScenario(@RequestBody payload: ScenarioConfigPayload): ScenarioConfigPayload =
        controlPlaneService.updateCurrentScenario(payload)

    @PostMapping("/api/v1/scenario/current/start")
    fun startCurrentScenario(): ResponseEntity<ScenarioActionResponse> =
        ResponseEntity.accepted().body(controlPlaneService.startCurrentScenario())

    @PostMapping("/api/v1/scenario/current/stop")
    fun stopCurrentScenario(): ResponseEntity<ScenarioActionResponse> =
        ResponseEntity.accepted().body(controlPlaneService.stopCurrentScenario())

    @GetMapping("/api/v1/results/recent")
    fun getRecentResults(): List<DecoderResultPayload> = controlPlaneService.getRecentResults()
}

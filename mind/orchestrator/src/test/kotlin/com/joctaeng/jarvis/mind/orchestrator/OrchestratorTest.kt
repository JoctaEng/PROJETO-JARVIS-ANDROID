package com.joctaeng.jarvis.mind.orchestrator

import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmProvider
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.Capability
import com.joctaeng.jarvis.core.model.DeviceContext
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.core.model.Sensitivity
import com.joctaeng.jarvis.core.model.TaskComplexity
import com.joctaeng.jarvis.core.model.ThermalLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val healthy = DeviceContext(
    totalRamMb = 12_000, availableRamMb = 6_000, lowMemory = false,
    batteryPercent = 80, charging = false, thermal = ThermalLevel.NORMAL, online = true,
)

private fun info(id: String, loc: ProviderLocation, vararg caps: Capability, available: Boolean = true) =
    ProviderInfo(id, loc, setOf(Capability.TEXT, *caps), available)

private val local = info("local", ProviderLocation.ON_DEVICE)
private val own = info("own", ProviderLocation.OWN_SERVER)
private val cloud = info("cloud", ProviderLocation.EXTERNAL_CLOUD, Capability.VISION)

class RoutingPolicyTest {
    private val policy = RoutingPolicy()
    private val all = listOf(cloud, own, local)

    @Test fun simpleTaskPrefersOnDevice() {
        assertEquals(listOf("local", "own", "cloud"), policy.plan(all, healthy, RoutingHints()).orderedProviderIds)
    }

    @Test fun complexTaskPrefersOwnServerThenCloud() {
        val plan = policy.plan(all, healthy, RoutingHints(complexity = TaskComplexity.COMPLEX))
        assertEquals(listOf("own", "cloud", "local"), plan.orderedProviderIds)
    }

    @Test fun privateModeKeepsOnlyOnDevice() {
        val plan = policy.plan(all, healthy.copy(privateMode = true), RoutingHints(complexity = TaskComplexity.COMPLEX))
        assertEquals(listOf("local"), plan.orderedProviderIds)
        assertTrue(plan.notices.any { "Privado" in it })
    }

    @Test fun offlineKeepsOnlyOnDeviceAndWarns() {
        val plan = policy.plan(all, healthy.copy(online = false), RoutingHints())
        assertEquals(listOf("local"), plan.orderedProviderIds)
        assertTrue(plan.notices.any { "offline" in it })
    }

    @Test fun sensitiveDataSkipsExternalCloudUnlessAllowed() {
        val hints = RoutingHints(sensitivity = Sensitivity.SENSITIVE, complexity = TaskComplexity.COMPLEX)
        assertEquals(listOf("own", "local"), policy.plan(all, healthy, hints).orderedProviderIds)
        val allowed = hints.copy(allowExternalCloudForSensitive = true)
        assertEquals(listOf("own", "cloud", "local"), policy.plan(all, healthy, allowed).orderedProviderIds)
    }

    @Test fun visionRequiresVisionCapability() {
        assertEquals(listOf("cloud"), policy.plan(all, healthy, RoutingHints(needsVision = true)).orderedProviderIds)
    }

    @Test fun hotDevicePushesWorkOffDevice() {
        val plan = policy.plan(all, healthy.copy(thermal = ThermalLevel.HOT), RoutingHints())
        assertEquals(listOf("own", "cloud", "local"), plan.orderedProviderIds)
    }

    @Test fun unavailableProvidersAreIgnored() {
        val plan = policy.plan(listOf(local.copy(available = false)), healthy, RoutingHints())
        assertTrue(plan.isEmpty)
    }

    @Test fun userPreferenceBreaksTiesWithinSameLocation() {
        val cloudB = info("cloudB", ProviderLocation.EXTERNAL_CLOUD)
        val plan = RoutingPolicy(userPreference = listOf("cloudB", "cloud"))
            .plan(listOf(cloud, cloudB), healthy, RoutingHints())
        assertEquals(listOf("cloudB", "cloud"), plan.orderedProviderIds)
    }
}

private class FakeProvider(
    override val id: String,
    override val location: ProviderLocation,
    private val script: suspend kotlinx.coroutines.flow.FlowCollector<LlmChunk>.() -> Unit,
) : LlmProvider {
    override val displayName = id
    override val capabilities = setOf(Capability.TEXT, Capability.STREAMING)
    override suspend fun isAvailable(context: DeviceContext) = true
    override fun generate(request: LlmRequest): Flow<LlmChunk> = flow { script() }
}

class OrchestratorTest {
    private val request = LlmRequest(systemPrompt = "", messages = emptyList())

    @Test fun fallsBackWhenProviderFailsBeforeAnyText() = runTest {
        val failing = FakeProvider("local", ProviderLocation.ON_DEVICE) { emit(LlmChunk.Error("modelo não baixado")) }
        val working = FakeProvider("own", ProviderLocation.OWN_SERVER) {
            emit(LlmChunk.Text("Oi, Joca!")); emit(LlmChunk.Done())
        }
        val events = Orchestrator(listOf(failing, working)).respond(request, healthy).toList()

        assertTrue(events.contains(OrchestratorEvent.FellBack("local", "modelo não baixado")))
        assertTrue(events.contains(OrchestratorEvent.Chunk(LlmChunk.Text("Oi, Joca!"))))
        assertTrue(events.none { it is OrchestratorEvent.Failed })
    }

    @Test fun reportsFailureInsteadOfSwitchingMidAnswer() = runTest {
        val partial = FakeProvider("local", ProviderLocation.ON_DEVICE) {
            emit(LlmChunk.Text("Começando...")); throw IllegalStateException("sem memória")
        }
        val backup = FakeProvider("own", ProviderLocation.OWN_SERVER) { emit(LlmChunk.Text("não deveria aparecer")) }
        val events = Orchestrator(listOf(partial, backup)).respond(request, healthy).toList()

        assertTrue(events.last() is OrchestratorEvent.Failed)
        assertTrue(events.none { it == OrchestratorEvent.RoutedTo("own") })
    }

    @Test fun failsHonestlyWhenNothingIsAvailable() = runTest {
        val events = Orchestrator(emptyList()).respond(request, healthy).toList()
        assertTrue(events.last() is OrchestratorEvent.Failed)
    }
}

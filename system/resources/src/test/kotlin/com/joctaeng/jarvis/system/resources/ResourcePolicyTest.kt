package com.joctaeng.jarvis.system.resources

import com.joctaeng.jarvis.core.model.DeviceContext
import com.joctaeng.jarvis.core.model.ThermalLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResourcePolicyTest {
    private val policy = ResourcePolicy()
    private val e2b = LocalModelTier("e2b", estimatedPeakRamMb = 2_000, minDeviceRamMb = 6_000)
    private val e4b = LocalModelTier("e4b", estimatedPeakRamMb = 3_000, minDeviceRamMb = 10_000)
    private val tiers = listOf(e4b, e2b)

    private fun device(total: Long, available: Long, battery: Int = 80, thermal: ThermalLevel = ThermalLevel.NORMAL) =
        DeviceContext(total, available, lowMemory = false, batteryPercent = battery, charging = false, thermal = thermal, online = true)

    @Test fun budgetFollowsRoadmapTable() {
        assertEquals(3_000, policy.processBudgetMb(8_000))
        assertEquals(4_500, policy.processBudgetMb(12_000))
    }

    @Test fun twelveGbPhoneWithFreeRamGetsLargerModel() {
        val choice = policy.chooseLocalModel(device(12_000, 5_000), tiers)
        assertEquals(e4b, (choice as ModelChoice.Load).tier)
    }

    @Test fun eightGbPhoneGetsSmallModel() {
        val choice = policy.chooseLocalModel(device(8_000, 5_000), tiers)
        assertEquals(e2b, (choice as ModelChoice.Load).tier)
    }

    @Test fun lowFreeRamFallsBackOrRefuses() {
        assertEquals(e2b, (policy.chooseLocalModel(device(12_000, 2_600), tiers) as ModelChoice.Load).tier)
        assertIs<ModelChoice.None>(policy.chooseLocalModel(device(12_000, 1_500), tiers))
    }

    @Test fun lowBatteryOrHeatPicksSmallest() {
        assertEquals(e2b, (policy.chooseLocalModel(device(12_000, 6_000, battery = 10), tiers) as ModelChoice.Load).tier)
        assertEquals(e2b, (policy.chooseLocalModel(device(12_000, 6_000, thermal = ThermalLevel.HOT), tiers) as ModelChoice.Load).tier)
    }

    @Test fun criticalHeatPausesLocalAi() {
        assertIs<ModelChoice.None>(policy.chooseLocalModel(device(12_000, 6_000, thermal = ThermalLevel.CRITICAL), tiers))
    }
}

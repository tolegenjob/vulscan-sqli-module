package io.vulscan.sqli.plan

import io.vulscan.kit.contracts.ParamLocation
import io.vulscan.kit.contracts.SqliOptions
import io.vulscan.sqli.TestJobs.endpoint
import io.vulscan.sqli.TestJobs.param
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParameterPlannerTest {
    private val planner = ParameterPlanner()
    private val both = SqliOptions(errorBased = true, booleanBased = true)

    @Test
    fun `parameter gets error and boolean checks when the budget allows`() {
        val plan = planner.plan(listOf(endpoint()), both, 600)
        assertThat(plan.parameters).hasSize(1)
        assertThat(plan.parameters.single().phases).containsExactlyInAnyOrder(Phase.ERROR, Phase.BOOLEAN)
        assertThat(plan.parameters.single().target.valueType).isEqualTo(ValueType.NUMERIC)
        assertThat(plan.skipped).isEmpty()
    }

    @Test
    fun `non idempotent endpoint is skipped with NOT_IDEMPOTENT`() {
        val unsafe = endpoint(idempotent = false)
        val plan = planner.plan(listOf(unsafe), both, 600)
        assertThat(plan.parameters).isEmpty()
        assertThat(plan.skipped.map { it.reason }).containsExactly("NOT_IDEMPOTENT")
        assertThat(plan.skipped.single().endpointId).isEqualTo(unsafe.endpointId)
    }

    @Test
    fun `error based gets the budget before boolean based`() {
        // baseline 3 + error 5 = 8 на параметр; на boolean (9) бюджета нет
        val plan = planner.plan(listOf(endpoint(), endpoint(url = "http://lab-app:8080/other")), both, 16)
        assertThat(plan.parameters).hasSize(2)
        assertThat(plan.parameters.map { it.phases }).allSatisfy { assertThat(it).containsExactly(Phase.ERROR) }
    }

    @Test
    fun `leftover budget goes to boolean based in priority order`() {
        // 2 параметра: 2·8 = 16 на error, остаётся 9 — хватает на boolean только первому
        val plan = planner.plan(listOf(endpoint(), endpoint(url = "http://lab-app:8080/other")), both, 25)
        assertThat(plan.parameters.map { it.phases.contains(Phase.BOOLEAN) }).containsExactly(true, false)
    }

    @Test
    fun `parameters beyond the budget are skipped with BUDGET and the sum never exceeds it`() {
        val endpoints = (1..5).map { endpoint(url = "http://lab-app:8080/e$it") }
        val plan = planner.plan(endpoints, both, 20)
        assertThat(plan.parameters).hasSize(2)
        assertThat(plan.skipped.map { it.reason }).containsOnly("BUDGET")
        assertThat(plan.skipped).hasSize(3)
        val cost = plan.parameters.sumOf { 3 + it.phases.sumOf { p -> if (p == Phase.ERROR) RequestCosts.ERROR else RequestCosts.BOOLEAN } }
        assertThat(cost).isLessThanOrEqualTo(20)
    }

    @Test
    fun `query parameters are preferred over path parameters when the budget is tight`() {
        val path = endpoint(url = "http://lab-app:8080/items/{id}", parameters = arrayOf(param("id", ParamLocation.PATH)))
        val query = endpoint(url = "http://lab-app:8080/search")
        val plan = planner.plan(listOf(path, query), SqliOptions(errorBased = true, booleanBased = false), 8)
        assertThat(plan.parameters.map { it.target.endpoint.endpointId }).containsExactly(query.endpointId)
        assertThat(plan.skipped.single().endpointId).isEqualTo(path.endpointId)
    }

    @Test
    fun `boolean only plan uses boolean cost and skips error`() {
        val plan = planner.plan(listOf(endpoint()), SqliOptions(errorBased = false, booleanBased = true), 600)
        assertThat(plan.parameters.single().phases).containsExactly(Phase.BOOLEAN)
        assertThat(planner.plan(listOf(endpoint()), SqliOptions(errorBased = false, booleanBased = true), 11).parameters).isEmpty()
    }

    @Test
    fun `time only plan keeps parameters without static phases`() {
        val plan = planner.plan(listOf(endpoint()), SqliOptions(errorBased = false, booleanBased = false, timeBased = true), 600)
        assertThat(plan.parameters).hasSize(1)
        assertThat(plan.parameters.single().phases).isEmpty()
    }

    @Test
    fun `unsupported locations and missing path placeholders get a reason`() {
        val header = endpoint(parameters = arrayOf(param("X-Id", ParamLocation.HEADER)))
        val path = endpoint(url = "http://lab-app:8080/items", parameters = arrayOf(param("id", ParamLocation.PATH)))
        val plan = planner.plan(listOf(header, path), both, 600)
        assertThat(plan.skipped.map { it.reason }).containsExactly("UNSUPPORTED_LOCATION", "PATH_PLACEHOLDER_MISSING")
    }

    @Test
    fun `at most maxParamsPerEndpoint parameters are planned per endpoint`() {
        val many = endpoint(parameters = (1..6).map { param("p$it") }.toTypedArray())
        val plan = ParameterPlanner(maxParamsPerEndpoint = 3).plan(listOf(many), both, 600)
        assertThat(plan.parameters).hasSize(3)
    }
}

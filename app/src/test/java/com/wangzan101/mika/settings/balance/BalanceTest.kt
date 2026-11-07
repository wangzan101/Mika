package com.wangzan101.mika.settings.balance

import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceTest {
    private fun preset(label: String) = ProviderPresets.all.single { it.label == label }

    private fun method(name: String, baseUrl: String = preset(name).baseUrl): BalanceMethod {
        val target = BalanceCatalog.resolve(name, baseUrl)
        assertTrue("$name should be queryable", target is BalanceTarget.Query)
        return (target as BalanceTarget.Query).method
    }

    @Test fun phaseOnePresetsUseTheirOwnMethods() {
        assertEquals("deepseek" to false, method("DeepSeek").let { it.id to it.experimental })
        assertEquals("kimi" to false, method("Kimi").let { it.id to it.experimental })
        assertEquals("stepfun" to false, method("阶跃星辰").let { it.id to it.experimental })
        assertEquals("openrouter" to false, method("OpenRouter").let { it.id to it.experimental })
        assertEquals("minimax-coding" to false, method("MiniMax").let { it.id to it.experimental })
        assertEquals("zhipu" to true, method("智谱").let { it.id to it.experimental })
        assertEquals("zhipu-coding" to false, method("智谱 Coding Plan").let { it.id to it.experimental })
    }

    @Test fun localPresetsAndAccountLoginsHideTheBalance() {
        assertTrue(BalanceCatalog.resolve("Ollama", "http://192.168.1.8:11434/v1") is BalanceTarget.Hidden)
        assertTrue(BalanceCatalog.resolve("LM Studio", preset("LM Studio").baseUrl) is BalanceTarget.Hidden)
        // 就算把本机预设的地址改成云端,这一家仍是本机服务,不显示余额
        assertTrue(BalanceCatalog.resolve("Ollama", "https://api.deepseek.com") is BalanceTarget.Hidden)
        assertTrue(BalanceCatalog.resolve("DeepSeek", preset("DeepSeek").baseUrl, ProviderConfig.Kind.ChatGpt) is BalanceTarget.Hidden)
        assertTrue(BalanceCatalog.resolve("xAI / Grok", "https://api.x.ai/v1", ProviderConfig.Kind.Xai) is BalanceTarget.Hidden)
    }

    @Test fun siliconFlowIsUnsupportedAndDoesNotCallTheRetiredApi() {
        val target = BalanceCatalog.resolve("硅基流动", preset("硅基流动").baseUrl) as BalanceTarget.Unsupported
        assertEquals(BalanceNote.SiliconFlowRetired, target.note)
        assertEquals(BalanceCatalog.SILICONFLOW_CONSOLE, target.consoleUrl)
        val custom = BalanceCatalog.resolve("自定义", "https://api.siliconflow.com/v1") as BalanceTarget.Unsupported
        assertEquals(BalanceNote.SiliconFlowRetired, custom.note)
        // 目录里没有任何一家会去打已停用的 /user/info
        ProviderPresets.all.forEach { p ->
            val query = BalanceCatalog.resolve(p.label, p.baseUrl).methodOrNull ?: return@forEach
            assertFalse(query.request("sk").url.encodedPath, query.request("sk").url.encodedPath.contains("user/info"))
        }
    }

    @Test fun otherBuiltInPresetsAreUnsupportedWithoutAFakeBalance() {
        val known = setOf(
            "DeepSeek", "Kimi", "阶跃星辰", "OpenRouter", "MiniMax", "智谱", "智谱 Coding Plan",
            "Ollama", "LM Studio", "硅基流动", "自定义",
        )
        ProviderPresets.all.filter { it.label !in known }.forEach { p ->
            val target = BalanceCatalog.resolve(p.label, p.baseUrl)
            assertTrue(p.label, target is BalanceTarget.Unsupported)
            assertEquals(p.label, BalanceNote.Generic, (target as BalanceTarget.Unsupported).note)
        }
        val custom = BalanceCatalog.resolve("我的中转", "https://models.example.com/v1") as BalanceTarget.Unsupported
        assertEquals(BalanceNote.Generic, custom.note)
        assertNull(custom.consoleUrl)
        // 看起来像官方域名、其实不是,不能套用那家的查询
        assertTrue(BalanceCatalog.resolve("自定义", "https://api.deepseek.com.evil.test/v1") is BalanceTarget.Unsupported)
    }

    @Test fun customOfficialHostsPickTheMatchingMethod() {
        assertEquals("deepseek", methodOf("自定义", "https://api.deepseek.com/v1").id)
        assertEquals("https://api.moonshot.ai/v1/users/me/balance", methodOf("自定义", "https://api.moonshot.ai/v1").request("k").url.toString())
        assertEquals("USD", (parseKimi("""{"code":0,"data":{"available_balance":1},"status":true}""", "USD") as ParseResult.Ok).lines.single().currency)
        assertEquals("zhipu-coding", methodOf("自定义", "https://open.bigmodel.cn/api/coding/paas/v4").id)
        assertEquals("zhipu", methodOf("自定义", "https://open.bigmodel.cn/api/paas/v4").id)
        assertTrue(methodOf("自定义", "https://api.z.ai/api/paas/v4").experimental)
        assertEquals("minimax-coding", methodOf("自定义", "https://api.minimax.io/v1").id)
    }

    @Test fun requestsUseTheDocumentedEndpointAndAuth() {
        val deepseek = method("DeepSeek").request("sk-ds")
        assertEquals("https://api.deepseek.com/user/balance", deepseek.url.toString())
        assertEquals("Bearer sk-ds", deepseek.header("Authorization"))

        val kimi = method("Kimi").request("sk-kimi")
        assertEquals("https://api.moonshot.cn/v1/users/me/balance", kimi.url.toString())
        assertEquals("Bearer sk-kimi", kimi.header("Authorization"))

        val step = method("阶跃星辰").request("sk-step")
        assertEquals("https://api.stepfun.com/v1/accounts", step.url.toString())
        assertEquals("Bearer sk-step", step.header("Authorization"))

        val router = method("OpenRouter").request("sk-or")
        assertEquals("https://openrouter.ai/api/v1/credits", router.url.toString())
        assertEquals("Bearer sk-or", router.header("Authorization"))

        // 推理在 api.minimax.cn,额度接口沿用有文档的 api.minimaxi.com
        val mini = method("MiniMax").request("sk-mm")
        assertEquals("https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains", mini.url.toString())
        assertEquals("Bearer sk-mm", mini.header("Authorization"))

        val zhipu = method("智谱").request("zhipu-key")
        assertEquals("https://open.bigmodel.cn/api/biz/account/query-customer-account-report", zhipu.url.toString())
        assertEquals("zhipu-key", zhipu.header("Authorization"))
        assertNull(zhipu.header("Authorization")?.let { it.takeIf { h -> h.startsWith("Bearer ") } })

        val plan = method("智谱 Coding Plan").request("plan-key")
        assertEquals("https://open.bigmodel.cn/api/monitor/usage/quota/limit", plan.url.toString())
        assertEquals("plan-key", plan.header("Authorization"))
    }

    @Test fun deepSeekHidesEmptyWalletsAndKeepsANegativeBalance() {
        val both = parseDeepSeek(
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"0.00"},{"currency":"USD","total_balance":"12.34"}]}""",
        ) as ParseResult.Ok
        assertEquals("USD", both.lines.single().currency)
        assertEquals(12.34, both.lines.single().amount, 0.0001)

        val none = parseDeepSeek(
            """{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"-24.96"}]}""",
        ) as ParseResult.Ok
        assertEquals(-24.96, none.lines.single().amount, 0.0)
        assertEquals("CNY", none.lines.single().currency)

        assertTrue(parseDeepSeek("""{"balance_infos":[]}""") is ParseResult.Failed)
    }

    @Test fun kimiParsesAvailableBalanceAndRejectsABusinessError() {
        val ok = parseKimi(
            """{"code":0,"data":{"available_balance":49.58894,"voucher_balance":46.58893,"cash_balance":3.00001},"scode":"0x0","status":true}""",
            "CNY",
        ) as ParseResult.Ok
        assertEquals(49.58894, ok.lines.single().amount, 0.000001)
        assertEquals("CNY", ok.lines.single().currency)
        assertTrue(parseKimi("""{"code":1,"status":false,"msg":"bad"}""", "CNY") is ParseResult.Failed)
        assertTrue(parseKimi("""{"code":0,"status":true}""", "CNY") is ParseResult.Failed)
    }

    @Test fun stepFunUsesTheAvailableBalance() {
        val ok = parseStepFun(
            """{"object":"account","type":"prepaid","balance":0.0,"total_cash_balance":0.0,"total_voucher_balance":26.0}""",
        ) as ParseResult.Ok
        assertEquals(0.0, ok.lines.single().amount, 0.0)
        assertEquals("CNY", ok.lines.single().currency)
        assertTrue(ok.lines.single().low)
        assertTrue(parseStepFun("""{"object":"account"}""") is ParseResult.Failed)
    }

    @Test fun openRouterSubtractsUsageAndDoesNotTreatZeroAsInvalid() {
        val ok = parseOpenRouter("""{"data":{"total_credits":100.0,"total_usage":25.5}}""") as ParseResult.Ok
        assertEquals(74.5, ok.lines.single().amount, 0.0001)
        assertEquals("USD", ok.lines.single().currency)
        val free = parseOpenRouter("""{"total_credits":"0","total_usage":"0"}""") as ParseResult.Ok
        assertEquals(0.0, free.lines.single().amount, 0.0)
        val over = parseOpenRouter("""{"data":{"total_credits":100,"total_usage":105}}""") as ParseResult.Ok
        assertEquals(-5.0, over.lines.single().amount, 0.0001)
        assertTrue(parseOpenRouter("""{"data":{"total_credits":100}}""") is ParseResult.Failed)
        assertTrue(parseOpenRouter("""{"data":{"total_credits":"NaN","total_usage":0}}""") is ParseResult.Failed)
    }

    @Test fun miniMaxCodingKeepsGeneralWindowsAndSkipsAnUnlimitedWeek() {
        val body = """
            {"base_resp":{"status_code":0,"status_msg":"success"},"model_remains":[
              {"model_name":"video","current_interval_remaining_percent":10},
              {"model_name":"general","current_interval_remaining_percent":80,
               "current_weekly_status":1,"current_weekly_remaining_percent":40}
            ]}
        """.trimIndent()
        val ok = parseMiniMaxCoding(body) as ParseResult.Ok
        assertEquals(BalanceLineKind.FiveHour to 80.0, ok.lines[0].kind to ok.lines[0].amount)
        assertEquals(BalanceLineKind.Weekly to 40.0, ok.lines[1].kind to ok.lines[1].amount)
        assertNull(ok.lines[0].currency)

        val noWeek = parseMiniMaxCoding(
            """{"model_remains":[{"model_name":"general","current_interval_remaining_percent":50,"current_weekly_status":3,"current_weekly_remaining_percent":100}]}""",
        ) as ParseResult.Ok
        assertEquals(1, noWeek.lines.size)

        val failed = parseMiniMaxCoding("""{"base_resp":{"status_code":1004,"status_msg":"invalid api key"}}""") as ParseResult.Failed
        assertEquals(BalanceFailure.Message("invalid api key"), failed.failure)
    }

    @Test fun zhipuAccountIsExperimentalAndUsesAvailableBalance() {
        val body = """{"code":200,"msg":"操作成功","data":{"balance":14.68002004,"availableBalance":14.68002004,"frozenBalance":0.0},"success":true}"""
        val ok = parseZhipuAccount(body, true) as ParseResult.Ok
        assertEquals(14.68002004, ok.lines.single().amount, 0.00000001)
        assertEquals("CNY", ok.lines.single().currency)
        val usd = parseZhipuAccount("""{"success":true,"data":{"balance":"3.5"}}""", false) as ParseResult.Ok
        assertEquals("USD" to 3.5, usd.lines.single().currency to usd.lines.single().amount)
        val err = parseZhipuAccount("""{"msg":"令牌已过期或验证不正确","success":false}""", true) as ParseResult.Failed
        assertTrue(err.failure is BalanceFailure.Message)
        assertTrue(parseZhipuAccount("""{"success":true,"data":{}}""", true) is ParseResult.Failed)
    }

    @Test fun zhipuCodingPlanShowsRemainingPercentAndDetectsNoPlan() {
        val body = """
            {"code":200,"msg":"操作成功","success":true,"data":{"level":"pro","limits":[
              {"type":"TIME_LIMIT","percentage":7,"unit":5},
              {"type":"TOKENS_LIMIT","percentage":44,"unit":3},
              {"type":"TOKENS_LIMIT","percentage":53,"unit":6}
            ]}}
        """.trimIndent()
        val ok = parseZhipuCoding(body) as ParseResult.Ok
        assertEquals(BalanceLineKind.FiveHour to 56.0, ok.lines[0].kind to ok.lines[0].amount)
        assertEquals(BalanceLineKind.Weekly to 47.0, ok.lines[1].kind to ok.lines[1].amount)
        assertTrue(parseZhipuCoding("""{"code":500,"msg":"当前用户不存在coding plan","success":false}""") is ParseResult.NoCodingPlan)
        val other = parseZhipuCoding("""{"msg":"服务暂不可用","success":false}""") as ParseResult.Failed
        assertEquals(BalanceFailure.Message("服务暂不可用"), other.failure)
    }

    @Test fun httpStatusMapsToAuthOrHttpAndDoesNotPassTheBodyThrough() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("nope sk-secret"))
            server.enqueue(MockResponse().setResponseCode(500).setBody("<html>"))
            server.enqueue(MockResponse().setBody("""{"balance_infos":[{"currency":"CNY","total_balance":"2"}]}"""))
            server.start()
            val lookup = HttpBalanceLookup(OkHttpClient())
            val method = object : BalanceMethod {
                override val id = "local"
                override val experimental = false
                override fun request(apiKey: String) = okhttp3.Request.Builder().url(server.url("/balance"))
                    .header("Authorization", "Bearer $apiKey").get().build()
                override fun parse(body: String) = parseDeepSeek(body)
            }
            val auth = lookup.query(method, "sk") as BalanceOutcome.Failed
            assertEquals(BalanceFailure.Auth, auth.failure)
            val http = lookup.query(method, "sk") as BalanceOutcome.Failed
            assertEquals(BalanceFailure.Http(500), http.failure)
            val ok = lookup.query(method, "sk") as BalanceOutcome.Ready
            assertEquals(2.0, ok.lines.single().amount, 0.0)
            val sent = server.takeRequest()
            assertEquals("Bearer sk", sent.getHeader("Authorization"))
        }
    }

    @Test fun zhipuCodingPlanFallsBackToTheExperimentalAccountBalance() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":500,"msg":"当前用户不存在coding plan","success":false}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"availableBalance":9.5}}"""))
            server.start()
            val account = ZhipuAccountBalance(server.url("/account").toString(), true)
            val coding = ZhipuCodingBalance(server.url("/quota").toString(), account)
            val ready = HttpBalanceLookup(OkHttpClient()).query(coding, "raw-key") as BalanceOutcome.Ready
            assertTrue(ready.experimental)
            assertEquals(9.5, ready.lines.single().amount, 0.0)
            val quota = server.takeRequest()
            val balance = server.takeRequest()
            assertEquals("/quota", quota.path)
            assertEquals("/account", balance.path)
            assertEquals("raw-key", quota.getHeader("Authorization"))
            assertEquals("raw-key", balance.getHeader("Authorization"))
        }
    }

    @Test fun amountsTrimTrailingZeros() {
        assertEquals("14.68", formatDecimal(14.68002004))
        assertEquals("-5", formatDecimal(-5.0))
        assertEquals("80", formatPercent(80.0))
        assertEquals("80.5", formatPercent(80.46))
    }

    @Test fun draftPlaceholderMatchesTheFormBeforeAResultArrives() {
        assertTrue(BalanceCatalog.preview("Ollama", "http://127.0.0.1:11434/v1", "x") is BalanceView.Hidden)
        assertTrue(BalanceCatalog.preview("DeepSeek", preset("DeepSeek").baseUrl, "") is BalanceView.NeedKey)
        val loading = BalanceCatalog.preview("智谱", preset("智谱").baseUrl, "sk") as BalanceView.Loading
        assertTrue(loading.experimental)
        val silicon = BalanceCatalog.preview("硅基流动", preset("硅基流动").baseUrl, "sk") as BalanceView.Unsupported
        assertEquals(BalanceNote.SiliconFlowRetired, silicon.note)
        val stale = BalanceSnapshot("other", BalanceView.Ready(listOf(BalanceLine(BalanceLineKind.Total, 1.0, "USD")), false))
        val shown = BalanceCatalog.shownDraft(stale, "DeepSeek", preset("DeepSeek").baseUrl, "")
        assertTrue(shown is BalanceView.NeedKey)
    }

    private fun methodOf(name: String, url: String): BalanceMethod =
        (BalanceCatalog.resolve(name, url) as BalanceTarget.Query).method
}

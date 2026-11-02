package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.uistate.Hant
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb

/**
 * 一个动作的风险。[hit] 是不可逆判定的结果(不用判或没命中为 null);[screen] 是判定所依据的那一屏,用到时才读。
 * 工具自己声明风险之后换掉实现即可,判定和审批不用改。
 */
fun interface RiskJudge {
    fun assess(verb: Verb?, hit: Irreversible?, screen: () -> ScreenSnapshot?): Risk
}

/** 按现有的不可逆判定和读屏信号分级;花不花钱按动作类别和屏上的金额认,不按 App。 */
object ClassifiedRisk : RiskJudge {
    /** 改界面上东西的动作;其余写操作(返回、滑动、打开 App、打开网址)只是在界面间移动。 */
    private val CHANGE_VERBS = setOf(Verb.Tap, Verb.TypeText, Verb.ClearText, Verb.PressKey, Verb.Upload, Verb.Interact)

    /** 这个动作会改界面上的东西(不只是在界面间移动);不可逆、对外的动作也都是这几种动作做出来的。 */
    fun changesState(verb: Verb?): Boolean = verb in CHANGE_VERBS

    /** 屏上带币种(¥、元、$)的金额:这时提交、删除一类的动作按花钱算(结算页上的「确认」「去支付」);发消息不花钱,不算。 */
    private val AMOUNT = Regex("[¥￥$＄]\\s*\\d|\\d+(\\.\\d+)?\\s*元")

    /**
     * 职位待遇,不是要付的钱:薪资区间(150-200元、¥150-200),或带计薪单位(元/天、元/月、元/小时)。
     * 只在联系、投申请的按钮上从应付金额里剔除;「提交」旁边的「30元/月」仍算花钱。
     */
    private val WAGE_UNIT = "天|日|月|年|周|週|小时|小時|时|時|单|單"
    private val SALARY = Regex(
        "[¥￥$＄]\\s*\\d+(?:\\.\\d+)?(?:\\s*[-~～－—至到]\\s*[¥￥$＄]?\\s*\\d+(?:\\.\\d+)?)?(?:\\s*元)?\\s*(?:/|／|每)\\s*(?:$WAGE_UNIT)" +
            "|[¥￥$＄]\\s*\\d+(?:\\.\\d+)?\\s*[-~～－—至到]\\s*[¥￥$＄]?\\s*\\d+(?:\\.\\d+)?" +
            "|\\d+(?:\\.\\d+)?\\s*[-~～－—至到]\\s*\\d+(?:\\.\\d+)?\\s*元(?:\\s*(?:/|／|每)\\s*(?:$WAGE_UNIT))?" +
            "|\\d+(?:\\.\\d+)?\\s*元\\s*(?:/|／|每)\\s*(?:$WAGE_UNIT)",
    )

    override fun assess(verb: Verb?, hit: Irreversible?, screen: () -> ScreenSnapshot?): Risk {
        val level = when (hit?.action) {
            null -> if (verb in CHANGE_VERBS) ActionRisk.Change else ActionRisk.Read
            IrrevAction.send, IrrevAction.submit -> ActionRisk.External
            IrrevAction.pay, IrrevAction.order, IrrevAction.delete -> ActionRisk.Irreversible
            IrrevAction.other -> ActionRisk.Unknown
        }
        if (hit == null) return Risk(level)
        val payment = hit.action == IrrevAction.pay || hit.action == IrrevAction.order ||
            hit.action != IrrevAction.send && payable(hit, screen)
        return Risk(level, payment)
    }

    /** 联系、投申请只因薪资说法不算花钱;屏上另有应付金额(合计、单价)时仍算。其余动作见到带币种的数就算。 */
    private fun payable(hit: Irreversible, screen: () -> ScreenSnapshot?): Boolean {
        val elements = screen()?.elements ?: return false
        val contact = IrreversibleDetector.isContactLabel(hit.matched)
        return elements.any { !it.editable && amountDue(it.name, contact) }
    }

    private fun amountDue(name: String, contact: Boolean): Boolean {
        if (!contact) return AMOUNT.containsMatchIn(name)
        return AMOUNT.containsMatchIn(SALARY.replace(Hant.fold(name), " "))
    }

    /** 动作落在哪个元素上:带编号的按编号找,打字落在有焦点的输入框,坐标点击取包含该点的最小元素。 */
    fun target(verb: Verb?, args: JsonObject, s: ScreenSnapshot): UiElement? {
        fun str(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
        str("ref")?.let { ref -> return s.elements.firstOrNull { it.ref == ref } }
        if (verb == Verb.TypeText || verb == Verb.ClearText) return s.elements.firstOrNull { it.focused && it.editable }
        val x = str("x")?.toDoubleOrNull() ?: return null
        val y = str("y")?.toDoubleOrNull() ?: return null
        return s.elements.filter { e -> e.bounds.size == 4 && x >= e.bounds[0] && x <= e.bounds[2] && y >= e.bounds[1] && y <= e.bounds[3] }
            .minByOrNull { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }
    }
}

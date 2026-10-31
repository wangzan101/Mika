package com.wangzan101.mika.mcp

import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.settings.McpUsbSettings

/**
 * 电脑连接的两种方式怎么合起来:局域网(用户在设置里打开,默认关)和 USB(电脑用 adb 打开)。
 * 两个开关各管各的;端口只开一个:局域网开着就绑所有网卡(回环也在内,USB 照样连得上),只有 USB 开着就只绑回环。
 * 不碰安卓接口,在电脑上测。
 */
object McpModes {
    /** 电脑经 adb 打开 USB 方式(`am start-foreground-service -a`)。 */
    const val ACTION_USB_ON = "com.wangzan101.mika.mcp.USB_ON"
    /** 电脑经 adb 关掉 USB 方式。 */
    const val ACTION_USB_OFF = "com.wangzan101.mika.mcp.USB_OFF"
    /** 通知上的「关闭」:两种都关。 */
    const val ACTION_TURN_OFF = "com.wangzan101.mika.mcp.TURN_OFF"

    enum class Listen { Off, LoopbackOnly, PrivateNetworks }

    fun listen(network: Boolean, usb: Boolean): Listen = when {
        network -> Listen.PrivateNetworks
        usb -> Listen.LoopbackOnly
        else -> Listen.Off
    }

    /** 服务收到的动作改哪个开关;别的动作(含没有动作)不改,只按开关现状来。 */
    fun apply(action: String?, network: McpSettings, usb: McpUsbSettings) {
        when (action) {
            ACTION_USB_ON -> usb.enable()
            ACTION_USB_OFF -> usb.disable()
            ACTION_TURN_OFF -> {
                network.setEnabled(false)
                usb.disable()
            }
        }
    }
}

# 电脑连接页面：真机截图

这些图片由 vivo V2502DA 真机运行本次修改后的生产页面组件并直接捕获应用窗口得到。截图使用固定示例状态；地址、端口、令牌、模型名和请求时间均为演示数据，不是设备的真实配置。窗口捕获不包含其他应用的悬浮窗、通知及系统状态栏信息，图片没有生成式改绘。

演示地址为 `192.0.2.10` / `198.51.100.20`，演示端口为 `1234`；令牌使用无效示例值，配置预览仍显示 `<token>`。本地截图宿主仅用于 QA 构建，不包含在产品提交中。真实连接另行验证：CLI 的 HTTP 连接/API 检查、MCP 握手与工具列表均通过，并观察到手机页面请求时间更新。截图不作为真实模型执行或跨 VPN 网络验收的证据。

| 改动区域 / 状态 | 截图 |
| --- | --- |
| 设置入口：连接电脑 | [查看](01-settings-entry.png) |
| CLI / Skill：USB，安装和连接两步 | [查看](02-cli-default.png) |
| CLI / Skill：Wi-Fi / VPN，显式开启网络访问 | [查看](14-cli-network-off.png) |
| CLI / Skill：网络地址选择 | [查看](15-cli-network-overview.png) |
| CLI / Skill：根地址、令牌文件命令及令牌复制 | [查看](16-cli-network-command.png) |
| MCP：USB 启动、转发和客户端设置 | [查看](03-mcp-usb.png) |
| MCP：网络访问尚未开启 | [查看](04-network-off.png) |
| MCP：网络地址和默认收起的配置 | [查看](05-network-waiting.png) |
| MCP：展开配置，令牌使用占位符 | [查看](06-config-preview.png) |
| 高级设置：访问开关、令牌管理和远程唤醒 | [查看](07-advanced-settings.png) |
| USB 开启文案，不冒充电脑已连接 | [查看](08-advanced-usb-on.png) |
| 已收到请求与时间 | [查看](09-request-received.png) |
| 启动失败状态 | [查看](10-start-failed.png) |
| 没有网络地址时禁用复制配置 | [查看](11-no-address.png) |
| 英文界面 | [查看](12-english.png) |
| 繁体中文界面 | [查看](13-traditional-chinese.png) |

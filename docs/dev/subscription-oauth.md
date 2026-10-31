# 订阅账号登录

ChatGPT 登录保持原样（`core/chatgpt/`，授权码 + PKCE，本机回调）。这里是另外两家订阅，以及两家明确不做的原因。

设计参照 a13n 的模型认证：登录、加密保存、刷新、再用各家自己的接口调用，四步分开。访问令牌、刷新令牌、设备码不进日志、不进诊断信息、不进界面上除用户码以外的文字。会话 JSON 和 API Key 放在同一处，用同一套加密。

Android 上优先设备码（RFC 8628）：手机没有合适的本机回调时，用户在浏览器里打开验证页、输入短码，App 轮询换令牌。

## 做了

### xAI / Grok

- 读 `https://auth.x.ai/.well-known/openid-configuration`。签发方必须是 `https://auth.x.ai`，设备码和令牌地址必须是 https 且主机是 `auth.x.ai`。对不上就停止，请求不发到别的主机。
- 设备码的 scope 是 `openid profile email offline_access grok-cli:access api:access`。
- 客户端是官方 Grok CLI 的公开 client_id `b1a00492-073a-47ea-816f-4c329264a828`。这不是 Mika 注册的应用。xAI 可能不支持这种用法，账号有被限制的风险。界面在按钮上面写明，不点就不发生。
- 调用走 xAI 自己的 Responses：`https://api.x.ai/v1/responses`（`ResponsesModel`）。不把请求转成 Anthropic 或别的协议。
- 刷新令牌快过期时换新的，同一时刻只有一个在换。刷新被拒且不能再用时清掉本机令牌。

### GitHub Copilot

- GitHub 设备码，scope `read:user`，只对 `github.com`（不做 GitHub Enterprise）。
- 客户端是 VS Code 里 GitHub Copilot 扩展的公开 client_id `Iv1.b507a08c87ecfe98`。同样不是 Mika 注册的。
- 换到的 GitHub 授权再去 `https://api.github.com/copilot_internal/v2/token` 换短期接口令牌。没有 Copilot 订阅（HTTP 403）不算登录成功。
- 接口根必须是 https，且主机是 `api.githubcopilot.com` 或其子域（例如 `api.individual.githubcopilot.com`）。别的主机拒绝。
- 调用走 Copilot 自己的 Chat Completions（`OpenAiCompatModel`）。该接口要求一组编辑器集成标识（`Copilot-Integration-Id: vscode-chat` 等），只加在 Copilot 的请求上。界面写明复用了这组公开标识。
- GitHub 这条设备码通常没有刷新令牌。短期接口令牌过期后，用保存的 GitHub 授权再换一张。GitHub 授权失效就得重新登录。
- 不把 Copilot 转成 Claude 或别的协议。

退出这两家只清这台手机上的令牌。对方账号里的授权要到 xAI 或 GitHub 的设置里自己断开（没有可用的公开注销，Copilot 这条也没有 client secret）。

## 不做

### Claude 官方订阅

Anthropic 写明：OAuth 只给 Claude 自己的应用（含 Claude Code）的正常使用；第三方产品要用 Claude Console 的 API Key 或云厂商凭证。不允许第三方 App 提供 Claude.ai 登录，也不允许把 Free / Pro / Max / Ultra 的订阅额度接到自己的请求上，更不允许代收、保存或转发 Claude.ai 的登录凭证。

因此没有一条给第三方 Android 助手用的正当、非反向代理的路。Mika 继续用 Anthropic 预设填 API Key。不做 Claude 订阅登录，也不做「用别的订阅去冒充 Claude 协议」。

### Google 账号（Gemini）

Google 写明：用第三方软件借用 Gemini CLI 的 OAuth 去访问它的后端，违反条款，账号可能被暂停。第三方要用 Vertex AI 或 AI Studio 的 API Key。

因此不复用 Gemini CLI 的客户端，也不做 Google 账号登录。Mika 继续用 Gemini 预设填 API Key。

## 和 ChatGPT 的差别

ChatGPT 用的是 OpenAI 给本机开源 App 的公开客户端（`dynamic_agent_client` 注册），授权码 + PKCE，有本机回调。那条路保持不动。

xAI 和 Copilot 没有同等的「给第三方 App 注册」的公开流程，所以复用官方客户端，并在界面上事先说明风险。

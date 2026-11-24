package com.wangzan101.mika.core.model

interface ChatModel {
    val capabilities: Capabilities

    /** 模型名,只用于记录。 */
    val name: String get() = ""

    /** 服务商显示名;产品模型由计量包装从模型工厂元数据带入,测试与自建模型可为空。 */
    val provider: String? get() = null

    /**
     * 是否是生产模型工厂建的托管传输。托管传输会加独立墙钟边界,即使底层 SDK 吞掉取消也能按时返回;
     * 脚本模型保持与调用方同一协程,便于确定性评测。
     */
    val managedTransport: Boolean get() = false

    /** 失败时抛 [ModelError]。 */
    suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply
}

/** 支持逐段文字的模型；不支持的实现继续走 [ChatModel.complete]。 */
interface StreamingChatModel : ChatModel {
    /** [onText] 收到的是本次请求已生成的可见文字全文；不包含模型私有思考或工具参数。 */
    suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply
}
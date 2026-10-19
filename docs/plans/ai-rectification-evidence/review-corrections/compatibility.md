# 模型兼容边界

已实测真实 provider 仅 qwen3.8-flash / OPENAI / QWEN_FAST；保留原 context=1048576/output=16384，新配置缺省仍49152/4096。AUTO 对其他模型解析 STANDARD，不构成全模型支持承诺。

QWEN_FAST 只接受 OPENAI + qwen3.8-flash。更换模型仍保留 FAST 时，设置 API 返回 ai.profile_invalid 和 analysis.model=ai.fast_model_required，UI 提示改 AUTO/STANDARD 或恢复匹配模型；拒绝时不覆盖原凭据/预算/连接。显式 STANDARD patch 可保存并按原状态机制更新目标。

STANDARD 当前拒绝非空 reasoning_content，思考轨迹 replay 不受支持；DeepSeek 思考模式可能涉及该字段，因此当前不能宣称 DeepSeek 思考/全部模型可用。本轮没有新增 DeepSeek 适配、框架或真实模型测试；保留原协议错误分类和明确可恢复提示。

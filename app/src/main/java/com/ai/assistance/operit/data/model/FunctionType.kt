package com.ai.assistance.operit.data.model

/** 表示不同功能类型，用于指定不同功能使用的AI配置 */
enum class FunctionType {
    CHAT, // 常规对话
    SUMMARY, // 对话总结
    TITLE_GENERATION, // AI总结标题
    MEMORY, // 记忆库处理
    UI_CONTROLLER, // UI自动化控制
    TRANSLATION, // 翻译功能
    ROLE_RESPONSE_PLANNER, // 角色回答顺序规划
    PERMISSION_REVIEWER, // “自动审核”档位使用的工具调用审查
    PERMISSION_RISK_SCORER, // “自动审核”快速档使用的异步风险分类器
    IMAGE_RECOGNITION, // 图像识别
    AUDIO_RECOGNITION, // 音频识别
    VIDEO_RECOGNITION // 视频识别
}

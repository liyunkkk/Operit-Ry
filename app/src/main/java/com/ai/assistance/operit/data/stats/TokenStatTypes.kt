package com.ai.assistance.operit.data.stats

/**
 * 事件业务分类（阶段 1 固定契约；统计页默认包含全部分类并允许筛选）。
 * 所有实际模型调用都应落入其中一种，包括连接测试等探测调用。
 */
enum class TokenStatCategory {
    CHAT,
    SUBAGENT,
    SUMMARY,
    TITLE,
    MEMORY,
    CHARACTER_GENERATION,
    READING_COMPANION,
    CONNECTION_TEST,
    /** The automatic review: the blocking reviewer that judges one tool call. */
    PERMISSION_REVIEWER,
    /** The automatic review: the asynchronous classifier that scores one dispatched batch. */
    PERMISSION_RISK_SCORER,
    /** Auxiliary next-user-message generation, never a foreground message total. */
    COMPOSER_PREDICTION,
    OTHER;

    companion object {
        fun fromName(name: String?): TokenStatCategory =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** 事件结束状态：正常完成、取消、超时、失败。 */
enum class TokenStatStatus {
    COMPLETED,
    CANCELLED,
    TIMEOUT,
    FAILED;

    companion object {
        fun fromName(name: String?): TokenStatStatus =
            entries.firstOrNull { it.name == name } ?: FAILED
    }
}

/** 事件价格快照的来源层级，用于解释单价的取值。 */
enum class PricingSource {
    /** 内置模型默认价（可能为 0 的“未知”缺省，见 [TokenPriceResolver]）。 */
    DEFAULT,

    /** provider/model 覆盖。 */
    PROVIDER_MODEL_OVERRIDE,

    /** 特定 API 配置覆盖。 */
    CONFIG_OVERRIDE,

    /** 旧系统（DataStore）中用户保存的 provider/model 价格（阶段 1 桥接）。 */
    LEGACY_OVERRIDE,

    /** 无法解析出定价（未知，对应成本为 null）。 */
    UNKNOWN;

    companion object {
        fun fromName(name: String?): PricingSource =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

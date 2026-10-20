package com.greatwall.callring.contracts.ai

enum class ThinkingMode { ENABLED, DISABLED }

/** 解析结论：可用、非官方网关（可用但参数可能不被执行）、不支持、未识别。 */

enum class ThinkingSupport { AVAILABLE, CUSTOM_GATEWAY, UNSUPPORTED, UNRECOGNIZED }

/** 内部派生的厂商合同，不落库、不展示。 */

package com.hanaki.ecom.context;

/**
 * 上下文交付给模型的通道。
 *
 * <p>SKILL_SCHEMA 通过 Spring AI 的工具协议交付，而不是再次复制到自然语言 Prompt；但它仍会
 * 参与 Token 预算和 Manifest 审计。该区分避免重复注入完整 Schema，同时保证预算不会漏算工具
 * 协议本身带来的输入开销。</p>
 */
public enum ContextDelivery {
    SYSTEM_MESSAGE,
    USER_MESSAGE,
    TOOL_PROTOCOL
}

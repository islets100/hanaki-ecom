package com.hanaki.ecom.agent;

/** 模型不可用、密钥无效或结构化输出不合法时统一抛出，API 会转换为 503。 */
public class ModelCallException extends RuntimeException {
    public ModelCallException(String message) { super(message); }
    public ModelCallException(String message, Throwable cause) { super(message, cause); }
}

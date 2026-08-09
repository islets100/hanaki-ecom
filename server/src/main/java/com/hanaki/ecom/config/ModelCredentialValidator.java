package com.hanaki.ecom.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动时检查密钥。项目不再静默回退到模板答案；未配置、误填示例值时立即终止启动，
 * 让开发者能够明确知道当前没有连接真实模型。
 */
@Component
public class ModelCredentialValidator {
    public ModelCredentialValidator(@Value("${AI_DASHSCOPE_API_KEY:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("your-dashscope-key")) {
            throw new IllegalStateException(
                    "缺少 AI_DASHSCOPE_API_KEY。请在本机环境变量或根目录 .env 中配置阿里云百炼 API Key。");
        }
    }
}

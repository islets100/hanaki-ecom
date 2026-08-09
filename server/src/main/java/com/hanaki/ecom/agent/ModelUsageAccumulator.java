package com.hanaki.ecom.agent;

import org.springframework.stereotype.Component;

/** 候选分支线程内累计真实模型响应 usage，最终固化到 candidate_answer 审计表。 */
@Component
public final class ModelUsageAccumulator {
    private final ThreadLocal<MutableUsage> current = new ThreadLocal<>();

    public Scope begin() {
        MutableUsage previous = current.get();
        current.set(new MutableUsage());
        return new Scope(previous);
    }

    public void add(int promptTokens, int completionTokens) {
        MutableUsage usage = current.get();
        if (usage == null) return;
        usage.prompt += Math.max(0, promptTokens);
        usage.completion += Math.max(0, completionTokens);
    }

    public Usage snapshot() {
        MutableUsage usage = current.get();
        return usage == null ? new Usage(0, 0) : new Usage(usage.prompt, usage.completion);
    }

    public final class Scope implements AutoCloseable {
        private final MutableUsage previous;
        private boolean closed;
        private Scope(MutableUsage previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) current.remove(); else current.set(previous);
        }
    }

    private static final class MutableUsage { int prompt; int completion; }
    public record Usage(int promptTokens, int completionTokens) {}
}

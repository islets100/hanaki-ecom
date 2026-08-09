package com.hanaki.ecom.agent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.UUID;

/**
 * 一个只实现本项目所需命令的 Redis RESP2 客户端。
 *
 * <p>这里刻意不把 Redis 当成“必须可用”的业务依赖：失败会由统一缓存门面降级到 L1 或真实
 * 数据源，但失败不会被吞掉，上层会记录指标。每次普通命令使用短连接，避免自己实现不可靠的
 * 连接池；订阅失效消息使用一条独立长连接并带指数退避重连。</p>
 */
@Component
public final class RedisRespClient {
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int timeoutMillis;
    private final String invalidationChannel;
    private final int circuitFailureThreshold;
    private final long circuitCooldownMillis;
    /**
     * 连续失败达到阈值后，在 cooldown 内直接快速失败，避免每个客服请求都等待一次 TCP 超时。
     * openUntil 使用单调语义不需要分布式一致；它只保护当前实例，真实数据仍由数据源提供。
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntil = new AtomicLong();
    /** 用实例标识避免发布者收到自己的消息后立即清掉刚写入的 L1。 */
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Consumer<String>> invalidationListeners = new CopyOnWriteArrayList<>();
    private volatile Thread subscriberThread;

    public RedisRespClient(
            @Value("${agent.cache.redis.enabled:false}") boolean enabled,
            @Value("${agent.cache.redis.host:localhost}") String host,
            @Value("${agent.cache.redis.port:6379}") int port,
            @Value("${agent.cache.redis.password:}") String password,
            @Value("${agent.cache.redis.database:0}") int database,
            @Value("${agent.cache.redis.timeout-millis:1500}") int timeoutMillis,
            @Value("${agent.cache.redis.invalidation-channel:hanaki:cache:invalidate}") String invalidationChannel,
            @Value("${agent.cache.redis.circuit-failure-threshold:5}") int circuitFailureThreshold,
            @Value("${agent.cache.redis.circuit-cooldown-millis:10000}") long circuitCooldownMillis) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.database = database;
        this.timeoutMillis = timeoutMillis;
        this.invalidationChannel = invalidationChannel;
        this.circuitFailureThreshold = Math.max(1, circuitFailureThreshold);
        this.circuitCooldownMillis = Math.max(1_000L, circuitCooldownMillis);
    }

    public boolean enabled() { return enabled; }

    public Optional<String> get(String key) {
        if (!enabled) return Optional.empty();
        Object value = command("GET", key);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    public void setEx(String key, Duration ttl, String value) {
        if (!enabled) return;
        long seconds = Math.max(1L, ttl.toSeconds());
        command("SET", key, value, "EX", Long.toString(seconds));
    }

    /** 值与毫秒 TTL 在同一条 SET 命令内原子提交，不产生“写成功但忘记设置过期时间”的永久键。 */
    public void setPx(String key, Duration ttl, String value) {
        if (!enabled) return;
        long millis = Math.max(1L, ttl.toMillis());
        command("SET", key, value, "PX", Long.toString(millis));
    }

    /**
     * 使用 SET key token NX PX 获取跨实例短租约。租约不是业务锁：拿不到时调用方只短暂等待并
     * 二次读取缓存，超过整体截止时间仍可回源，从而不会让 Redis 锁故障演变成客服链路死锁。
     */
    public boolean tryAcquireLease(String key, String ownerToken, Duration lease) {
        if (!enabled) return false;
        Object response = command("SET", key, ownerToken, "NX", "PX",
                Long.toString(Math.max(1L, lease.toMillis())));
        return "OK".equalsIgnoreCase(String.valueOf(response));
    }

    /**
     * 释放租约必须比较 owner token，并在 Redis 端以一段 Lua 原子执行。普通 DEL 可能删除“旧租约
     * 到期后被另一个实例重新获得”的新租约，导致多个实例同时回源。
     */
    public boolean releaseLease(String key, String ownerToken) {
        if (!enabled) return false;
        String script = "if redis.call('get',KEYS[1]) == ARGV[1] then "
                + "return redis.call('del',KEYS[1]) else return 0 end";
        Object response = command("EVAL", script, "1", key, ownerToken);
        return response instanceof Number number && number.longValue() == 1L;
    }

    public void delete(String key) {
        if (enabled) command("DEL", key);
    }

    public void publishInvalidation(String key) {
        if (enabled) command("PUBLISH", invalidationChannel, instanceId + "|" + key);
    }

    public void onInvalidation(Consumer<String> listener) {
        invalidationListeners.add(listener);
    }

    @PostConstruct
    void startSubscriber() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        subscriberThread = Thread.ofVirtual().name("redis-cache-invalidation").start(this::subscriptionLoop);
    }

    @PreDestroy
    void stopSubscriber() {
        running.set(false);
        Thread thread = subscriberThread;
        if (thread != null) thread.interrupt();
    }

    private Object command(String... args) {
        long now = System.currentTimeMillis();
        if (circuitOpenUntil.get() > now)
            throw new CacheBackendException("Redis circuit is open for command: " + args[0], null);
        try (Connection connection = connect()) {
            authenticateAndSelect(connection);
            connection.write(args);
            Object response = connection.read();
            // 任意成功命令都说明 Redis 路径恢复；半开探测成功后立即关闭当前实例熔断器。
            consecutiveFailures.set(0);
            circuitOpenUntil.set(0L);
            return response;
        } catch (IOException error) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= circuitFailureThreshold) {
                circuitOpenUntil.set(System.currentTimeMillis() + circuitCooldownMillis);
                consecutiveFailures.set(0);
            }
            throw new CacheBackendException("Redis command failed: " + args[0], error);
        }
    }

    private void subscriptionLoop() {
        long backoff = 250L;
        while (running.get()) {
            try (Connection connection = connect()) {
                authenticateAndSelect(connection);
                // 普通命令需要短超时；订阅连接在无消息时应保持阻塞，否则会每隔 1.5 秒误判超时并重连。
                connection.disableReadTimeout();
                connection.write("SUBSCRIBE", invalidationChannel);
                connection.read(); // subscription acknowledgement
                backoff = 250L;
                while (running.get()) {
                    Object frame = connection.read();
                    if (!(frame instanceof List<?> values) || values.size() < 3) continue;
                    if (!"message".equalsIgnoreCase(String.valueOf(values.get(0)))) continue;
                    String payload = String.valueOf(values.get(2));
                    int separator = payload.indexOf('|');
                    if (separator > 0 && instanceId.equals(payload.substring(0, separator))) continue;
                    String key = separator > 0 ? payload.substring(separator + 1) : payload;
                    invalidationListeners.forEach(listener -> listener.accept(key));
                }
            } catch (Exception ignored) {
                if (!running.get()) return;
                try { Thread.sleep(backoff); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                backoff = Math.min(10_000L, backoff * 2L);
            }
        }
    }

    private Connection connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setSoTimeout(timeoutMillis);
        socket.setTcpNoDelay(true);
        return new Connection(socket);
    }

    private void authenticateAndSelect(Connection connection) throws IOException {
        if (!password.isBlank()) {
            connection.write("AUTH", password);
            requireOk(connection.read(), "AUTH");
        }
        if (database != 0) {
            connection.write("SELECT", Integer.toString(database));
            requireOk(connection.read(), "SELECT");
        }
    }

    private void requireOk(Object response, String command) throws IOException {
        if (!"OK".equalsIgnoreCase(String.valueOf(response)))
            throw new IOException(command + " rejected by Redis");
    }

    static final class CacheBackendException extends RuntimeException {
        CacheBackendException(String message, Throwable cause) { super(message, cause); }
    }

    /** RESP reader/writer supporting simple strings, errors, integers, bulk strings and arrays. */
    private static final class Connection implements AutoCloseable {
        private final Socket socket;
        private final BufferedInputStream in;
        private final BufferedOutputStream out;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = new BufferedOutputStream(socket.getOutputStream());
        }

        void write(String... args) throws IOException {
            raw("*" + args.length + "\r\n");
            for (String arg : args) {
                byte[] bytes = (arg == null ? "" : arg).getBytes(StandardCharsets.UTF_8);
                raw("$" + bytes.length + "\r\n");
                out.write(bytes);
                raw("\r\n");
            }
            out.flush();
        }

        Object read() throws IOException {
            int prefix = in.read();
            if (prefix < 0) throw new EOFException("Redis closed connection");
            return switch (prefix) {
                case '+' -> line();
                case '-' -> throw new IOException("Redis error: " + line());
                case ':' -> Long.parseLong(line());
                case '$' -> bulk();
                case '*' -> array();
                default -> throw new IOException("Unknown RESP prefix: " + (char) prefix);
            };
        }

        private String bulk() throws IOException {
            int length = Integer.parseInt(line());
            if (length == -1) return null;
            byte[] bytes = in.readNBytes(length);
            if (bytes.length != length) throw new EOFException("Incomplete Redis bulk response");
            expectCrLf();
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private List<Object> array() throws IOException {
            int length = Integer.parseInt(line());
            if (length == -1) return null;
            List<Object> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) result.add(read());
            return result;
        }

        private String line() throws IOException {
            StringBuilder result = new StringBuilder();
            while (true) {
                int value = in.read();
                if (value < 0) throw new EOFException("Incomplete Redis line");
                if (value == '\r') {
                    if (in.read() != '\n') throw new IOException("Malformed RESP line");
                    return result.toString();
                }
                result.append((char) value);
            }
        }

        private void expectCrLf() throws IOException {
            if (in.read() != '\r' || in.read() != '\n') throw new IOException("Malformed RESP bulk response");
        }

        private void raw(String value) throws IOException { out.write(value.getBytes(StandardCharsets.US_ASCII)); }
        private void disableReadTimeout() throws IOException { socket.setSoTimeout(0); }
        @Override public void close() throws IOException { socket.close(); }
    }
}

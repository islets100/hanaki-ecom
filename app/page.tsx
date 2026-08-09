"use client";

import { FormEvent, useMemo, useState } from "react";

type Product = {
  id: string;
  name: string;
  subtitle: string;
  price: number;
  oldPrice: number;
  category: string;
  stock: number;
  badge: string;
  icon: string;
  tone: string;
};

type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  agent?: string;
  traceId?: string;
  evidence?: string[];
  /** 高风险写操作的短期签名令牌；只有用户点击确认后才会提交。 */
  confirmToken?: string;
  /** 与确认令牌绑定的业务任务，服务端会用它保证幂等。 */
  businessTaskId?: string;
};

type View = "home" | "products" | "orders" | "operations";

const products: Product[] = [
  { id: "P1001", name: "云感羊毛通勤大衣", subtitle: "澳洲美利奴羊毛 · 轻盈保暖", price: 1299, oldPrice: 1599, category: "服饰", stock: 36, badge: "今日上新", icon: "衣", tone: "sand" },
  { id: "P1002", name: "静听 Pro 降噪耳机", subtitle: "45dB 深度降噪 · 40h 续航", price: 899, oldPrice: 1099, category: "数码", stock: 82, badge: "热卖", icon: "音", tone: "ink" },
  { id: "P1003", name: "轻羽人体工学椅", subtitle: "动态腰托 · 12 区精细调节", price: 1680, oldPrice: 2099, category: "家居", stock: 18, badge: "会员专享", icon: "椅", tone: "sage" },
  { id: "P1004", name: "澄明无线氛围灯", subtitle: "无频闪阅读 · 智能调光", price: 329, oldPrice: 399, category: "家居", stock: 61, badge: "包邮", icon: "光", tone: "amber" },
  { id: "P1005", name: "山野轻量徒步鞋", subtitle: "防泼水 · Vibram 防滑底", price: 699, oldPrice: 829, category: "运动", stock: 43, badge: "口碑新品", icon: "行", tone: "clay" },
  { id: "P1006", name: "无界智能手表 S3", subtitle: "全天候健康监测 · 双频 GPS", price: 1499, oldPrice: 1799, category: "数码", stock: 27, badge: "限时直降", icon: "表", tone: "blue" },
  { id: "P1007", name: "植萃修护精华液", subtitle: "神经酰胺复配 · 维稳修护", price: 368, oldPrice: 428, category: "美护", stock: 104, badge: "回购榜 No.1", icon: "萃", tone: "rose" },
  { id: "P1008", name: "城市轻旅托特包", subtitle: "头层牛皮 · 15 英寸电脑位", price: 1180, oldPrice: 1390, category: "箱包", stock: 22, badge: "匠心之选", icon: "包", tone: "cream" },
];

const categories = ["全部", "服饰", "数码", "家居", "运动", "美护", "箱包"];

const quickQuestions = ["我的订单到哪了？", "这款耳机支持多设备吗？", "物流三天没更新", "我要申请退货"];

const agentNames: Record<string, string> = {
  PRE_SALE: "售前商品专家",
  IN_SALE: "售中履约专家",
  AFTER_SALE: "售后服务专家",
  COMPLAINT: "投诉与人工接管",
  UNKNOWN: "主路由 Agent",
};

function money(value: number) {
  return new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY", maximumFractionDigits: 0 }).format(value);
}

export default function Home() {
  const [view, setView] = useState<View>("home");
  const [category, setCategory] = useState("全部");
  const [query, setQuery] = useState("");
  const [cartCount, setCartCount] = useState(2);
  const [chatOpen, setChatOpen] = useState(true);
  const [chatInput, setChatInput] = useState("");
  const [sending, setSending] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([
    { id: "welcome", role: "assistant", content: "下午好，我是花木智能客服。商品参数、订单物流、退换售后都可以直接问我。", agent: "主路由 Agent" },
  ]);

  const visibleProducts = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    return products.filter((product) => {
      const categoryMatches = category === "全部" || product.category === category;
      const keywordMatches = !keyword || `${product.name}${product.subtitle}${product.category}`.toLowerCase().includes(keyword);
      return categoryMatches && keywordMatches;
    });
  }, [category, query]);

  async function sendMessage(raw?: string) {
    const content = (raw ?? chatInput).trim();
    if (!content || sending) return;
    const userMessage: ChatMessage = { id: crypto.randomUUID(), role: "user", content };
    setMessages((current) => [...current, userMessage]);
    setChatInput("");
    setSending(true);

    try {
      const response = await fetch(`${process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"}/api/v1/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Tenant-Id": "hanaki-demo", "X-User-Id": "user-1001" },
        body: JSON.stringify({
          tenantId: "hanaki-demo",
          userId: "user-1001",
          conversationId: "web-demo-conversation",
          messageId: crypto.randomUUID(),
          content,
        }),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => ({}));
        throw new Error(problem.message ?? "模型服务暂时不可用");
      }
      const payload = await response.json();
      setMessages((current) => [...current, {
        id: payload.runId ?? crypto.randomUUID(),
        role: "assistant",
        content: payload.answer,
        agent: agentNames[payload.intent] ?? payload.agentName ?? "业务 Agent",
        traceId: payload.traceId,
        evidence: payload.evidence,
        confirmToken: payload.confirmToken,
        businessTaskId: payload.businessTaskId,
      }]);
    } catch (error) {
      const reason = error instanceof Error ? error.message : "未知错误";
      setMessages((current) => [...current, {
        id: crypto.randomUUID(),
        role: "assistant",
        agent: "模型连接状态",
        content: `请求未生成客服答案：${reason}。请确认后端已启动并正确配置 AI_DASHSCOPE_API_KEY。`,
      }]);
    } finally {
      setSending(false);
    }
  }

  function submitChat(event: FormEvent) {
    event.preventDefault();
    void sendMessage();
  }

  /**
   * 确认按钮不会复用对话接口，而是进入独立的受控写操作接口。
   * 服务端会再次校验租户、用户、任务状态、签名与有效期，避免越权或重复提交。
   */
  async function confirmTask(message: ChatMessage) {
    if (!message.confirmToken || !message.businessTaskId || sending) return;
    setSending(true);
    try {
      const response = await fetch(`${process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"}/api/v1/tasks/confirm`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Tenant-Id": "hanaki-demo", "X-User-Id": "user-1001" },
        body: JSON.stringify({
          tenantId: "hanaki-demo",
          userId: "user-1001",
          businessTaskId: message.businessTaskId,
          confirmToken: message.confirmToken,
          confirmed: true,
        }),
      });
      if (!response.ok) throw new Error("confirmation failed");
      const payload = await response.json();
      setMessages((current) => current
        .map((item) => item.id === message.id ? { ...item, confirmToken: undefined } : item)
        .concat({
          id: crypto.randomUUID(),
          role: "assistant",
          agent: "售后服务专家",
          content: `任务 ${payload.businessTaskId ?? message.businessTaskId} 已提交，当前状态：${payload.status ?? "SUBMITTED"}。重复点击不会产生第二次业务操作。`,
        }));
    } catch {
      setMessages((current) => [...current, {
        id: crypto.randomUUID(),
        role: "assistant",
        agent: "售后服务专家",
        content: "确认没有成功提交，任务仍保持原状态。请检查服务端连接后重试，或转人工客服处理。",
      }]);
    } finally {
      setSending(false);
    }
  }

  return (
    <main>
      <header className="topbar">
        <div className="shell topbar-inner">
          <button className="brand" onClick={() => setView("home")} aria-label="返回花木商城首页">
            <span className="brand-mark">曜</span>
            <span><strong>花木商城</strong><small>HANAKI SELECT</small></span>
          </button>
          <nav className="main-nav" aria-label="主导航">
            {([ ["home", "首页"], ["products", "全部商品"], ["orders", "我的订单"], ["operations", "运营台"] ] as [View, string][]).map(([key, label]) => (
              <button key={key} className={view === key ? "active" : ""} onClick={() => setView(key)}>{label}</button>
            ))}
          </nav>
          <label className="search">
            <span>⌕</span>
            <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索商品、型号或问题" onFocus={() => setView("products")} />
            <kbd>Enter</kbd>
          </label>
          <div className="header-actions">
            <button aria-label="消息">消息<span className="dot" /></button>
            <button aria-label="购物车" onClick={() => setCartCount((value) => value + 1)}>购物袋 <b>{cartCount}</b></button>
            <span className="avatar">林</span>
          </div>
        </div>
      </header>

      {view === "home" && (
        <>
          <section className="hero shell">
            <div className="hero-copy">
              <span className="eyebrow">AUTUMN · CURATED 2026</span>
              <h1>把日常，<br />过成更好的样子。</h1>
              <p>严选真正改善体验的设计，从一件大衣到一副耳机；你的专属智能顾问始终在线。</p>
              <div className="hero-actions">
                <button className="primary" onClick={() => setView("products")}>探索本季精选 <span>↗</span></button>
                <button className="secondary" onClick={() => setChatOpen(true)}>问问选购顾问</button>
              </div>
              <div className="trust-row"><span>✓ 30 天价保</span><span>✓ 正品溯源</span><span>✓ 极速售后</span></div>
            </div>
            <div className="hero-stage" aria-label="本季主推商品">
              <div className="hero-orbit orbit-one" />
              <div className="hero-orbit orbit-two" />
              <div className="hero-product">
                <span className="hero-product-icon">音</span>
                <div><small>QUIET PRO</small><strong>听见世界，<br />也听见自己。</strong></div>
              </div>
              <div className="floating-card card-a"><small>实时库存</small><strong>花木黑 · 82 件</strong><span>刚刚更新</span></div>
              <div className="floating-card card-b"><i>4.9</i><span>来自 2,438 条<br />真实评价</span></div>
              <div className="hero-price"><span>限时会员价</span><strong>¥899</strong><del>¥1,099</del></div>
            </div>
          </section>

          <section className="value-strip">
            <div className="shell values">
              <div><span>01</span><p><strong>严选而非堆砌</strong><small>每件商品通过 28 项体验评估</small></p></div>
              <div><span>02</span><p><strong>智能但有边界</strong><small>真实业务校验，不让模型替你做主</small></p></div>
              <div><span>03</span><p><strong>服务全程可追溯</strong><small>从咨询到售后，每一步都有依据</small></p></div>
            </div>
          </section>

          <ProductSection products={products.slice(0, 4)} onAdd={() => setCartCount((value) => value + 1)} onMore={() => setView("products")} />

          <section className="service-story shell">
            <div className="story-card">
              <span className="eyebrow">INTELLIGENT SERVICE</span>
              <h2>不是更会聊天，<br />是更会解决问题。</h2>
              <p>主 Agent 识别你的诉求，商品、订单、售后专家各司其职；所有状态以真实系统为准，高风险操作必须再次确认。</p>
              <button onClick={() => setChatOpen(true)}>现在体验智能客服 <span>→</span></button>
            </div>
            <div className="agent-map">
              <div className="agent-center"><small>主 Agent</small><strong>理解与路由</strong><em>置信度 0.94</em></div>
              <div className="agent-pill pill-1"><i />售前 · 商品</div>
              <div className="agent-pill pill-2"><i />售中 · 履约</div>
              <div className="agent-pill pill-3"><i />售后 · 服务</div>
              <div className="agent-pill pill-4"><i />投诉 · 人工</div>
              <div className="map-caption"><span>输入风控</span><b>→</b><span>工具校验</span><b>→</b><span>输出审核</span></div>
            </div>
          </section>
        </>
      )}

      {view === "products" && (
        <section className="catalog shell page-section">
          <div className="page-heading"><div><span className="eyebrow">CURATED CATALOG</span><h1>全部商品</h1><p>找到值得长期使用的那一件。</p></div><b>{visibleProducts.length} 件精选</b></div>
          <div className="category-row">
            {categories.map((item) => <button key={item} className={category === item ? "active" : ""} onClick={() => setCategory(item)}>{item}</button>)}
          </div>
          <div className="product-grid large-grid">
            {visibleProducts.map((product) => <ProductCard key={product.id} product={product} onAdd={() => setCartCount((value) => value + 1)} />)}
          </div>
        </section>
      )}

      {view === "orders" && <OrdersPage onSupport={() => setChatOpen(true)} />}
      {view === "operations" && <OperationsPage />}

      <footer>
        <div className="shell footer-grid">
          <div className="brand footer-brand"><span className="brand-mark">曜</span><span><strong>花木商城</strong><small>把复杂留给系统，把确定交给用户。</small></span></div>
          <div><strong>购物帮助</strong><a>配送说明</a><a>支付方式</a><a>退换政策</a></div>
          <div><strong>服务支持</strong><a>订单查询</a><a>在线客服</a><a>人工接管</a></div>
          <div><strong>平台承诺</strong><a>隐私保护</a><a>正品保障</a><a>规则透明</a></div>
        </div>
        <div className="shell copyright">© 2026 花木商城 · 多智能体客服演示平台 <span>沪 ICP 备演示号</span></div>
      </footer>

      <button className={`chat-launcher ${chatOpen ? "open" : ""}`} onClick={() => setChatOpen((value) => !value)} aria-label={chatOpen ? "关闭智能客服" : "打开智能客服"}>
        {chatOpen ? "×" : "问"}<span>智能客服</span>
      </button>

      {chatOpen && (
        <aside className="chat-panel" aria-label="智能客服对话窗口">
          <div className="chat-header">
            <div className="bot-avatar">曜</div>
            <div><strong>花木智能客服</strong><span><i /> 多智能体协同服务中</span></div>
            <button onClick={() => setChatOpen(false)} aria-label="关闭">—</button>
          </div>
          <div className="agent-status"><span>主 Agent</span><b>识别意图</b><i>→</i><b>业务 Agent</b><i>→</i><b>风控审核</b></div>
          <div className="messages">
            {messages.map((message) => (
              <div key={message.id} className={`message ${message.role}`}>
                {message.role === "assistant" && <small>{message.agent}</small>}
                <p>{message.content}</p>
                {message.confirmToken && <div className="confirm-action"><button onClick={() => void confirmTask(message)}>确认并提交</button><span>15 分钟内有效 · 已绑定当前账号与任务</span></div>}
                {message.evidence && <details><summary>查看回答依据 · {message.evidence.length} 项</summary>{message.evidence.map((item) => <span key={item}>{item}</span>)}{message.traceId && <code>Trace {message.traceId}</code>}</details>}
              </div>
            ))}
            {sending && <div className="typing"><i /><i /><i /><span>业务 Agent 正在核验信息</span></div>}
          </div>
          <div className="quick-prompts">
            {quickQuestions.slice(0, 3).map((item) => <button key={item} onClick={() => void sendMessage(item)}>{item}</button>)}
          </div>
          <form className="chat-input" onSubmit={submitChat}>
            <textarea value={chatInput} onChange={(event) => setChatInput(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); void sendMessage(); } }} placeholder="输入商品、订单或售后问题…" rows={2} />
            <div><span>↵ Enter 发送</span><button type="submit" disabled={!chatInput.trim() || sending}>↑</button></div>
          </form>
          <div className="chat-footnote">关键操作需你确认 · 隐私信息已脱敏</div>
        </aside>
      )}
    </main>
  );
}

function ProductSection({ products: items, onAdd, onMore }: { products: Product[]; onAdd: () => void; onMore: () => void }) {
  return <section className="products shell"><div className="section-heading"><div><span className="eyebrow">EDITOR&apos;S PICKS</span><h2>本周精选</h2></div><button onClick={onMore}>查看全部 <span>→</span></button></div><div className="product-grid">{items.map((product) => <ProductCard key={product.id} product={product} onAdd={onAdd} />)}</div></section>;
}

function ProductCard({ product, onAdd }: { product: Product; onAdd: () => void }) {
  return <article className="product-card"><div className={`product-visual ${product.tone}`}><span className="product-badge">{product.badge}</span><b>{product.icon}</b><button className="favorite" aria-label={`收藏${product.name}`}>♡</button><small>{product.category.toUpperCase()} · SELECT</small></div><div className="product-info"><span>{product.category}</span><h3>{product.name}</h3><p>{product.subtitle}</p><div className="price-row"><div><strong>{money(product.price)}</strong><del>{money(product.oldPrice)}</del></div><button onClick={onAdd} aria-label={`将${product.name}加入购物车`}>＋</button></div></div></article>;
}

function OrdersPage({ onSupport }: { onSupport: () => void }) {
  return <section className="orders shell page-section"><div className="page-heading"><div><span className="eyebrow">ORDER CENTER</span><h1>我的订单</h1><p>订单、物流和售后状态以业务系统实时结果为准。</p></div><button className="secondary" onClick={onSupport}>咨询订单</button></div><div className="order-layout"><div className="order-card"><div className="order-top"><span>2026-07-27 · 订单尾号 4832</span><b>运输中</b></div><div className="order-body"><div className="mini-product ink">音</div><div className="order-product"><strong>静听 Pro 降噪耳机</strong><span>花木黑 · 标准版 × 1</span><small>实付 ¥899 · 顺丰速运</small></div><div className="order-actions"><button onClick={onSupport}>查看物流</button><button onClick={onSupport}>申请售后</button></div></div><div className="timeline"><div className="done"><i /><span>已支付<small>07-27 21:32</small></span></div><div className="done"><i /><span>已发货<small>07-28 17:10</small></span></div><div className="active"><i /><span>运输中<small>上海转运中心</small></span></div><div><i /><span>待收货<small>预计明日</small></span></div></div></div><aside className="order-side"><span className="eyebrow">SERVICE TASK</span><h3>售后服务进度</h3><p>暂无进行中的售后任务。</p><button onClick={onSupport}>让智能客服帮我处理</button><hr /><small>提示：退款、改址、取消订单等写操作都会在执行前再次向你确认。</small></aside></div></section>;
}

function OperationsPage() {
  const metrics = [["1,284", "今日会话", "+12.4%"], ["86.7%", "自动解决率", "+3.1%"], ["8.2%", "转人工率", "-1.8%"], ["1.42s", "平均首响", "-0.21s"]];
  return <section className="operations shell page-section"><div className="page-heading"><div><span className="eyebrow">SERVICE OPERATIONS</span><h1>客服运营台</h1><p>会话、工单、知识、规则与 Agent 运行状态统一管理。</p></div><div className="live"><i /> 实时数据</div></div><div className="metric-grid">{metrics.map(([value, label, trend]) => <div className="metric" key={label}><span>{label}</span><strong>{value}</strong><small>{trend} 较昨日</small></div>)}</div><div className="dashboard-grid"><div className="dashboard-card wide"><div className="card-title"><div><span>会话趋势</span><small>近 7 日 Agent 处理量</small></div><b>成功率 97.6%</b></div><div className="chart" aria-label="近七日会话趋势图">{[42, 56, 48, 72, 63, 82, 76].map((height, index) => <div key={index}><i style={{ height: `${height}%` }} /><span>{["一", "二", "三", "四", "五", "六", "日"][index]}</span></div>)}</div></div><div className="dashboard-card"><div className="card-title"><div><span>Agent 分布</span><small>今日路由占比</small></div></div><div className="agent-list"><p><i className="c1" />售前 Agent <b>38%</b></p><p><i className="c2" />售中 Agent <b>31%</b></p><p><i className="c3" />售后 Agent <b>24%</b></p><p><i className="c4" />投诉/人工 <b>7%</b></p></div></div><div className="dashboard-card span-two"><div className="card-title"><div><span>待处理工单</span><small>按风险和等待时长排序</small></div><button>查看全部</button></div><table><thead><tr><th>工单</th><th>类型</th><th>风险</th><th>状态</th><th>负责人</th></tr></thead><tbody><tr><td>#TK-2098</td><td>物流停滞</td><td><em className="medium">中</em></td><td>待处理</td><td>上海一组</td></tr><tr><td>#TK-2097</td><td>退款异常</td><td><em className="high">高</em></td><td>人工核验</td><td>售后二组</td></tr><tr><td>#TK-2093</td><td>商品投诉</td><td><em className="low">低</em></td><td>处理中</td><td>品牌客服</td></tr></tbody></table></div><div className="dashboard-card trace-card"><div className="card-title"><div><span>最新 Trace</span><small>可回放 Agent 执行链</small></div></div><ol><li><i />输入风控 <small>12ms</small></li><li><i />意图识别 <small>184ms</small></li><li><i />售中 SubRun <small>628ms</small></li><li><i />工具结果校验 <small>42ms</small></li><li><i />输出风控 <small>19ms</small></li></ol><code>trace_7f2a…91cd</code></div></div></section>;
}

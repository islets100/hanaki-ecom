create table if not exists product (
  id varchar(40) not null, tenant_id varchar(80) not null, name varchar(160) not null,
  subtitle varchar(255), category varchar(80), price decimal(12,2), old_price decimal(12,2),
  stock integer, badge varchar(80), attributes_json text, primary key(id, tenant_id)
);
alter table product add column if not exists store_id varchar(80);
alter table product add column if not exists active boolean default true not null;
create table if not exists merchant_store (
  id varchar(80) not null, tenant_id varchar(80) not null, name varchar(160) not null,
  logo_text varchar(12), description text, service_score decimal(3,2),
  fulfillment_score decimal(3,2), location varchar(120), created_at timestamp not null,
  primary key(id,tenant_id)
);
create table if not exists customer_order (
  id varchar(40) primary key, tenant_id varchar(80) not null, user_id varchar(80) not null,
  product_id varchar(40) not null, sku varchar(120), amount decimal(12,2), status varchar(40),
  payment_status varchar(40), logistics_status varchar(80), created_at timestamp not null
);
-- 即使演示库仍保留历史全局主键，所有业务唯一约束也显式包含 tenant_id；生产分库可直接使用该联合键。
create unique index if not exists uq_customer_order_tenant_order on customer_order(tenant_id,id);
create table if not exists purchase_request_dedup (
  tenant_id varchar(80) not null, user_id varchar(80) not null, request_id varchar(120) not null,
  request_hash varchar(64) not null, order_id varchar(40) not null, paid_amount decimal(12,2) not null,
  balance_after decimal(12,2) not null, planned_ship_at timestamp not null,
  estimated_arrival_at timestamp not null, status varchar(30) not null, created_at timestamp not null,
  primary key(tenant_id,user_id,request_id)
);
create index if not exists idx_order_owner on customer_order(tenant_id,user_id,created_at);
create table if not exists order_fulfillment (
  order_id varchar(40) primary key, tenant_id varchar(80) not null, store_id varchar(80) not null,
  planned_ship_at timestamp not null, estimated_arrival_at timestamp not null,
  shipped_at timestamp, delivered_at timestamp, status varchar(40) not null, updated_at timestamp not null
);
create table if not exists logistics_event (
  id varchar(40) primary key, tenant_id varchar(80) not null, order_id varchar(40) not null,
  event_time timestamp not null, location varchar(120), description varchar(255)
);
create table if not exists knowledge_doc (
  id varchar(40) primary key, tenant_id varchar(80) not null, domain varchar(40) not null,
  title varchar(160), content text, version varchar(40), active boolean default true
);
alter table knowledge_doc add column if not exists source_type varchar(40) default 'AUTHORITATIVE';
alter table knowledge_doc add column if not exists source_trace_id varchar(80);
alter table knowledge_doc add column if not exists content_hash varchar(64);
alter table knowledge_doc add column if not exists effective_at timestamp;
alter table knowledge_doc add column if not exists expires_at timestamp;
alter table knowledge_doc add column if not exists reviewed_by varchar(80);
alter table knowledge_doc add column if not exists reviewed_at timestamp;
alter table knowledge_doc add column if not exists rule_version varchar(80);
alter table knowledge_doc add column if not exists prompt_version varchar(80);
alter table knowledge_doc add column if not exists model_version varchar(120);
alter table knowledge_doc add column if not exists applicable_conditions varchar(255);
create table if not exists conversation_message (
  id varchar(40) primary key, tenant_id varchar(80), user_id varchar(80), conversation_id varchar(80),
  run_id varchar(80), role varchar(20), content text, created_at timestamp
);
create index if not exists idx_message_context on conversation_message(tenant_id,user_id,conversation_id,created_at);
alter table conversation_message add column if not exists idempotency_key varchar(64);
-- message_seq 是摘要 CAS 的时间轴。created_at 可能在同一毫秒相同，不能承担“摘要覆盖到哪里”的语义。
alter table conversation_message add column if not exists message_seq bigint;
alter table conversation_message add column if not exists content_hash varchar(64);
alter table conversation_message add column if not exists token_count integer default 0;
alter table conversation_message add column if not exists trust_level varchar(32) default 'USER_CLAIMED';
alter table conversation_message add column if not exists source_type varchar(32) default 'CHAT';
alter table conversation_message add column if not exists deleted_at timestamp;
create unique index if not exists uq_conversation_message_idempotency
  on conversation_message(tenant_id,user_id,idempotency_key);
create unique index if not exists uq_conversation_message_seq
  on conversation_message(tenant_id,user_id,conversation_id,message_seq);
-- 商品详情进入店铺客服时先建立 AI 会话上下文；只有后续意图识别为转人工时才创建 support_case。
create table if not exists store_ai_session (
  conversation_id varchar(80) primary key, tenant_id varchar(80) not null, user_id varchar(80) not null,
  store_id varchar(80) not null, product_id varchar(40) not null, store_name varchar(160) not null,
  product_name varchar(160) not null, created_at timestamp not null, updated_at timestamp not null
);
create unique index if not exists uq_store_ai_tenant_conversation
  on store_ai_session(tenant_id,conversation_id);
create index if not exists idx_store_ai_owner on store_ai_session(tenant_id,user_id,updated_at);
-- 官方客服同样先建立独立的 AI 会话，不与店铺会话混用 conversation_id。
create table if not exists official_ai_session (
  conversation_id varchar(80) primary key, tenant_id varchar(80) not null, user_id varchar(80) not null,
  created_at timestamp not null, updated_at timestamp not null
);
create unique index if not exists uq_official_ai_tenant_conversation
  on official_ai_session(tenant_id,conversation_id);
create index if not exists idx_official_ai_owner on official_ai_session(tenant_id,user_id,updated_at);
create table if not exists agent_checkpoint (
  id varchar(40) primary key, tenant_id varchar(80), conversation_id varchar(80), run_id varchar(80),
  node_name varchar(80), state_json text, schema_version varchar(20), created_at timestamp
);
alter table agent_checkpoint add column if not exists idempotency_key varchar(64);
alter table agent_checkpoint add column if not exists user_id varchar(80);
alter table agent_checkpoint add column if not exists state_version bigint default 1;
alter table agent_checkpoint add column if not exists graph_version varchar(80) default 'graph-v1';
alter table agent_checkpoint add column if not exists prompt_version varchar(80);
alter table agent_checkpoint add column if not exists tenant_config_version varchar(80);
alter table agent_checkpoint add column if not exists policy_version varchar(80);
alter table agent_checkpoint add column if not exists knowledge_base_version varchar(80);
alter table agent_checkpoint add column if not exists tool_schema_version varchar(80);
alter table agent_checkpoint add column if not exists routing_config_version varchar(80);
alter table agent_checkpoint add column if not exists topology_version varchar(80);
alter table agent_checkpoint add column if not exists pending_action_type varchar(80);
alter table agent_checkpoint add column if not exists business_task_id varchar(80);
alter table agent_checkpoint add column if not exists expires_at timestamp;
alter table agent_checkpoint add column if not exists status varchar(30) default 'ACTIVE';
create unique index if not exists uq_agent_checkpoint_idempotency
  on agent_checkpoint(tenant_id,idempotency_key);
create table if not exists business_task (
  id varchar(40) primary key, tenant_id varchar(80), user_id varchar(80), order_id varchar(40),
  type varchar(40), status varchar(40), rule_version varchar(40), version integer,
  expires_at timestamp, created_at timestamp, updated_at timestamp
);
alter table business_task add column if not exists expires_at timestamp;
alter table business_task add column if not exists idempotency_key varchar(64);
create unique index if not exists uq_business_task_idempotency
  on business_task(tenant_id,user_id,idempotency_key);
create table if not exists business_task_transition (
  id varchar(80) primary key, business_task_id varchar(40) not null, tenant_id varchar(80) not null,
  source_state varchar(40) not null, target_state varchar(40) not null,
  transition_source varchar(40) not null, created_at timestamp not null,
  foreign key(business_task_id) references business_task(id)
);
create index if not exists idx_business_task_transition on business_task_transition(tenant_id,business_task_id,created_at);

-- 用户确认绑定完整操作内容，而不是只保存 confirmed=true。operation_digest 在执行前必须重新计算。
create table if not exists user_confirmation_record (
  id varchar(80) primary key, tenant_id varchar(80) not null, user_id varchar(80) not null,
  business_task_id varchar(80) not null, order_id varchar(40) not null,
  operation_type varchar(80) not null, amount decimal(18,2) not null,
  operation_digest varchar(64) not null, rule_version varchar(80) not null,
  nonce varchar(80) not null, status varchar(30) not null,
  expires_at timestamp not null, confirmed_at timestamp, created_at timestamp not null,
  constraint uq_confirmation_nonce unique(tenant_id,user_id,business_task_id,nonce)
);
create index if not exists idx_confirmation_pending
  on user_confirmation_record(tenant_id,user_id,business_task_id,status,expires_at);

-- Graph Checkpoint 只保证流程可恢复；真实写操作依靠本流水处理幂等和“超时但结果未知”。
create table if not exists tool_operation_record (
  operation_id varchar(80) primary key, tenant_id varchar(80) not null,
  business_task_id varchar(80) not null, operation_type varchar(80) not null,
  request_digest varchar(64) not null, status varchar(30) not null,
  external_reference varchar(160), result_json text, last_error text,
  created_at timestamp not null, started_at timestamp, completed_at timestamp, updated_at timestamp not null,
  constraint uq_tool_operation unique(tenant_id,business_task_id,operation_type)
);
create index if not exists idx_tool_operation_recovery
  on tool_operation_record(tenant_id,status,updated_at);

-- 一次 Run 只读取一个已发布配置版本。租户只能收窄业务能力，平台白名单和安全规则不存放在此表。
create table if not exists tenant_agent_config (
  tenant_id varchar(80) not null, config_version varchar(80) not null,
  prompt_version varchar(80) not null, policy_version varchar(80) not null,
  knowledge_base_version varchar(80) not null, tool_schema_version varchar(80) not null,
  routing_config_version varchar(80) not null, topology_version varchar(80) not null,
  enabled_tools text, customer_service_queue varchar(80) not null,
  active boolean not null default false, published_at timestamp not null,
  primary key(tenant_id,config_version)
);
create index if not exists idx_tenant_agent_config_active
  on tenant_agent_config(tenant_id,active,published_at);
create table if not exists ticket (
  id varchar(40) primary key, tenant_id varchar(80), user_id varchar(80), order_id varchar(40), type varchar(40),
  summary text, risk_level varchar(20), priority varchar(20), status varchar(40), assignee varchar(80), created_at timestamp
);
create table if not exists agent_trace (
  id varchar(40) primary key, trace_id varchar(80), tenant_id varchar(80), run_id varchar(80), node_name varchar(80),
  elapsed_ms bigint, result varchar(255), created_at timestamp
);
-- Trace 回放需要保存每个节点的父子关系、输入输出快照、模型用量和异常原因。
-- 这些 ALTER 语句均可重复执行，兼容已经存在的本地 H2 数据库。
alter table agent_trace add column if not exists span_id varchar(40);
alter table agent_trace add column if not exists parent_span_id varchar(40);
alter table agent_trace add column if not exists user_id varchar(80);
alter table agent_trace add column if not exists conversation_id varchar(80);
alter table agent_trace add column if not exists span_kind varchar(30);
alter table agent_trace add column if not exists status varchar(20);
alter table agent_trace add column if not exists input_json text;
alter table agent_trace add column if not exists output_json text;
alter table agent_trace add column if not exists metadata_json text;
alter table agent_trace add column if not exists error_type varchar(160);
alter table agent_trace add column if not exists error_message text;
alter table agent_trace add column if not exists model_name varchar(120);
alter table agent_trace add column if not exists prompt_tokens integer default 0;
alter table agent_trace add column if not exists completion_tokens integer default 0;
alter table agent_trace add column if not exists total_tokens integer default 0;
alter table agent_trace add column if not exists cost_cny decimal(18,8) default 0;
alter table agent_trace add column if not exists started_at timestamp;
alter table agent_trace add column if not exists finished_at timestamp;
create index if not exists idx_agent_trace_owner on agent_trace(tenant_id,user_id,created_at);
create index if not exists idx_agent_trace_replay on agent_trace(trace_id,started_at);

-- SaaS 租户代表入驻商家。客户与平台官方账号使用 PLATFORM 作用域；商家员工、商品、
-- 商家知识和商家工单使用 MERCHANT 作用域，登录后都由服务端会话固定。
create table if not exists saas_tenant (
  tenant_id varchar(80) primary key, tenant_code varchar(40) not null,
  display_name varchar(120) not null, status varchar(20) not null,
  store_agent_invite_hash varchar(64) not null, official_agent_invite_hash varchar(64) not null,
  created_at timestamp not null, updated_at timestamp not null,
  constraint uq_saas_tenant_code unique(tenant_code)
);
alter table saas_tenant add column if not exists tenant_type varchar(20) default 'MERCHANT' not null;
alter table saas_tenant add column if not exists primary_store_id varchar(80);
create index if not exists idx_saas_tenant_status on saas_tenant(status,tenant_code);

-- 平台资金账户全平台唯一，从 0 开始；客户购买入账、退款出账，并以订单流水保证幂等。
create table if not exists platform_balance (
  tenant_id varchar(80) primary key, available_balance decimal(14,2) not null default 0,
  version integer not null default 0, updated_at timestamp not null
);
create table if not exists platform_balance_ledger (
  id varchar(40) primary key, tenant_id varchar(80) not null, entry_type varchar(30) not null,
  amount decimal(14,2) not null, balance_after decimal(14,2) not null,
  reference_id varchar(80) not null, description varchar(255), created_at timestamp not null,
  constraint uq_platform_balance_reference unique(tenant_id,entry_type,reference_id)
);
create index if not exists idx_platform_balance_ledger on platform_balance_ledger(tenant_id,created_at);

-- 客户、店铺客服与商城官方客服使用统一账号表，但角色和店铺归属由服务端固定校验。
create table if not exists app_account (
  id varchar(40) primary key, tenant_id varchar(80) not null, username varchar(64) not null,
  password_hash varchar(255) not null, display_name varchar(80) not null, role varchar(30) not null,
  store_id varchar(80), enabled boolean default true, created_at timestamp not null,
  constraint uq_account_username unique(tenant_id,username)
);
alter table app_account add column if not exists failed_login_attempts integer default 0 not null;
alter table app_account add column if not exists locked_until timestamp;
create table if not exists account_balance (
  account_id varchar(40) primary key, tenant_id varchar(80) not null,
  available_balance decimal(14,2) not null, version integer not null default 0,
  updated_at timestamp not null, foreign key(account_id) references app_account(id)
);
create table if not exists balance_ledger (
  id varchar(40) primary key, account_id varchar(40) not null, tenant_id varchar(80) not null,
  entry_type varchar(30) not null, amount decimal(14,2) not null, balance_after decimal(14,2) not null,
  reference_id varchar(80) not null, description varchar(255), created_at timestamp not null,
  constraint uq_balance_reference unique(account_id,entry_type,reference_id),
  foreign key(account_id) references app_account(id)
);
create index if not exists idx_balance_ledger on balance_ledger(account_id,created_at);
create table if not exists auth_session (
  token_hash varchar(64) primary key, account_id varchar(40) not null, expires_at timestamp not null,
  created_at timestamp not null, foreign key(account_id) references app_account(id)
);
create index if not exists idx_session_account on auth_session(account_id,expires_at);

-- 人工会话同时承载普通转人工、投诉以及高危操作审批。
create table if not exists support_case (
  id varchar(40) primary key, tenant_id varchar(80) not null, customer_id varchar(40) not null,
  store_id varchar(80), conversation_id varchar(80), business_task_id varchar(40),
  type varchar(40) not null, queue_name varchar(20) not null, summary text,
  risk_level varchar(20) not null, status varchar(30) not null, assignee_id varchar(40),
  created_at timestamp not null, updated_at timestamp not null
);
alter table support_case add column if not exists order_id varchar(40);
create index if not exists idx_case_queue on support_case(tenant_id,queue_name,status,updated_at);
create index if not exists idx_case_customer on support_case(tenant_id,customer_id,updated_at);
create table if not exists support_message (
  id varchar(40) primary key, case_id varchar(40) not null, sender_id varchar(40) not null,
  sender_role varchar(30) not null, content text not null, created_at timestamp not null,
  foreign key(case_id) references support_case(id)
);
create index if not exists idx_support_message on support_message(case_id,created_at);
create table if not exists staff_decision (
  id varchar(40) primary key, case_id varchar(40) not null, business_task_id varchar(40) not null,
  stage varchar(20) not null, decision varchar(20) not null, decided_by varchar(40) not null,
  comment text, created_at timestamp not null,
  constraint uq_task_decision_stage unique(business_task_id,stage)
);

-- 退款理由评分是业务决策快照：保留命中的规则、模型版本和降级原因，不能只保存一个分数。
create table if not exists refund_assessment (
  business_task_id varchar(40) primary key, tenant_id varchar(80) not null, user_id varchar(80) not null,
  order_id varchar(40) not null, reason_text text, score integer not null,
  policy_eligible boolean not null, decision_mode varchar(30) not null,
  summary text not null, matched_rule_ids text, missing_information text,
  rule_version varchar(80) not null, model_version varchar(120) not null,
  created_at timestamp not null, updated_at timestamp not null,
  foreign key(business_task_id) references business_task(id)
);
create index if not exists idx_refund_assessment_owner
  on refund_assessment(tenant_id,user_id,order_id,created_at);

-- 文件内容保存到受控目录，数据库仅保存不可伪造的归属、摘要和媒体元数据。
create table if not exists refund_evidence (
  id varchar(40) primary key, business_task_id varchar(40) not null,
  tenant_id varchar(80) not null, user_id varchar(80) not null,
  media_type varchar(20) not null, content_type varchar(100) not null,
  original_filename varchar(255) not null, storage_path varchar(500) not null,
  size_bytes bigint not null, sha256 varchar(64) not null, display_order integer not null default 0,
  created_at timestamp not null,
  foreign key(business_task_id) references business_task(id)
);
alter table refund_evidence add column if not exists display_order integer not null default 0;
create index if not exists idx_refund_evidence_task
  on refund_evidence(tenant_id,business_task_id,created_at);

-- Best-of-3 可恢复审计链路。动态用户事实不会进入公共知识库。
create table if not exists evaluation_batch (
  id varchar(80) primary key, tenant_id varchar(80) not null, user_id varchar(80) not null,
  conversation_id varchar(80), run_id varchar(80) not null, trace_id varchar(80) not null,
  intent varchar(40) not null, status varchar(30) not null, snapshot_json text not null,
  selected_candidate_id varchar(40), score_gap integer, needs_human_review boolean default false,
  created_at timestamp not null, updated_at timestamp not null
);
create index if not exists idx_evaluation_owner on evaluation_batch(tenant_id,user_id,created_at);
alter table evaluation_batch add column if not exists trigger_mode varchar(30) default 'PRE_GENERATION';
alter table evaluation_batch add column if not exists trigger_reason varchar(255);
alter table evaluation_batch add column if not exists evaluation_profile varchar(80) default 'best-of-three-v2';
alter table evaluation_batch add column if not exists candidate_count integer default 3;
alter table evaluation_batch add column if not exists successful_count integer default 0;
alter table evaluation_batch add column if not exists snapshot_id varchar(80);
alter table evaluation_batch add column if not exists snapshot_hash varchar(64);
alter table evaluation_batch add column if not exists winner_score integer;
alter table evaluation_batch add column if not exists quality_threshold integer default 75;
alter table evaluation_batch add column if not exists score_gap_threshold integer default 5;
alter table evaluation_batch add column if not exists scoring_version varchar(40) default 'judge-score-v2';
alter table evaluation_batch add column if not exists failure_code varchar(80);
alter table evaluation_batch add column if not exists failure_message text;
alter table evaluation_batch add column if not exists version integer default 0;
create unique index if not exists uq_evaluation_run_profile
  on evaluation_batch(tenant_id,run_id,evaluation_profile);

-- 快照独立于 Graph checkpoint 持久化。业务节点只引用 snapshot_id/hash，恢复时重新验签。
create table if not exists evaluation_snapshot (
  id varchar(80) primary key, evaluation_batch_id varchar(80) not null unique,
  tenant_id varchar(80) not null, user_id varchar(80) not null, conversation_id varchar(80),
  run_id varchar(80) not null, intent varchar(40) not null, normalized_question text not null,
  snapshot_json text not null, snapshot_hash varchar(64) not null, created_at timestamp not null,
  foreign key(evaluation_batch_id) references evaluation_batch(id)
);
create index if not exists idx_evaluation_snapshot_owner
  on evaluation_snapshot(tenant_id,user_id,run_id);

-- attempt 是物理调用；candidate_answer 是逻辑候选。重试不会覆盖历史调用记录。
create table if not exists candidate_attempt (
  id varchar(80) primary key, evaluation_batch_id varchar(80) not null,
  candidate_no integer not null, attempt_no integer not null, candidate_profile varchar(80) not null,
  candidate_run_id varchar(120) not null, status varchar(30) not null,
  model_version varchar(120), prompt_version varchar(80), snapshot_hash varchar(64) not null,
  request_hash varchar(64), response_hash varchar(64), raw_response text, parsed_response_json text,
  prompt_tokens integer default 0, completion_tokens integer default 0, elapsed_ms bigint default 0,
  error_type varchar(120), error_message text, lease_owner varchar(80), lease_expires_at timestamp,
  created_at timestamp not null, updated_at timestamp not null,
  constraint uq_candidate_attempt unique(evaluation_batch_id,candidate_no,attempt_no),
  foreign key(evaluation_batch_id) references evaluation_batch(id)
);
create index if not exists idx_candidate_attempt_recovery
  on candidate_attempt(evaluation_batch_id,candidate_no,status,attempt_no);
create table if not exists candidate_answer (
  id varchar(80) primary key, evaluation_batch_id varchar(80) not null, candidate_no integer not null,
  candidate_id varchar(40) not null, candidate_run_id varchar(80) not null, status varchar(30) not null,
  answer text, evidence_json text, tool_result_json text, validation_json text,
  prompt_tokens integer default 0, completion_tokens integer default 0, elapsed_ms bigint default 0,
  created_at timestamp not null,
  constraint uq_evaluation_candidate unique(evaluation_batch_id,candidate_no),
  foreign key(evaluation_batch_id) references evaluation_batch(id)
);
alter table candidate_answer add column if not exists error_type varchar(120);
alter table candidate_answer add column if not exists error_message text;
alter table candidate_answer add column if not exists retry_count integer default 0;
alter table candidate_answer add column if not exists safe boolean default false;
alter table candidate_answer add column if not exists completeness integer default 0;
alter table candidate_answer add column if not exists clarity integer default 0;
alter table candidate_answer add column if not exists effective_attempt_id varchar(80);
alter table candidate_answer add column if not exists candidate_profile varchar(80);
alter table candidate_answer add column if not exists answer_hash varchar(64);
alter table candidate_answer add column if not exists missing_info_json text;
alter table candidate_answer add column if not exists uncertainties_json text;
alter table candidate_answer add column if not exists proposed_actions_json text;
alter table candidate_answer add column if not exists risk_tags_json text;
alter table candidate_answer add column if not exists self_confidence double precision;
alter table candidate_answer add column if not exists quality_score integer;

create table if not exists judge_attempt (
  id varchar(80) primary key, evaluation_batch_id varchar(80) not null, attempt_no integer not null,
  status varchar(30) not null, candidate_set_hash varchar(64) not null,
  model_version varchar(120), prompt_version varchar(80), request_hash varchar(64), response_hash varchar(64),
  raw_response text, parsed_response_json text, prompt_tokens integer default 0,
  completion_tokens integer default 0, elapsed_ms bigint default 0,
  error_type varchar(120), error_message text, created_at timestamp not null, updated_at timestamp not null,
  constraint uq_judge_attempt unique(evaluation_batch_id,attempt_no),
  foreign key(evaluation_batch_id) references evaluation_batch(id)
);
create table if not exists judge_result (
  id varchar(80) primary key, evaluation_batch_id varchar(80) not null unique,
  selected_candidate_id varchar(40), scores_json text not null, score_gap integer not null,
  needs_human_review boolean not null, fallback_reason varchar(255), created_at timestamp not null,
  foreign key(evaluation_batch_id) references evaluation_batch(id)
);
alter table judge_result add column if not exists judge_attempt_id varchar(80);
alter table judge_result add column if not exists winner_score integer;
alter table judge_result add column if not exists quality_threshold integer default 75;
alter table judge_result add column if not exists score_gap_threshold integer default 5;
alter table judge_result add column if not exists scoring_version varchar(40) default 'judge-score-v2';
alter table judge_result add column if not exists candidate_set_hash varchar(64);
create table if not exists knowledge_candidate (
  id varchar(80) primary key, tenant_id varchar(80) not null, normalized_question text not null,
  proposed_answer text not null, intent varchar(40) not null, evidence_json text not null,
  judge_score integer not null, source_trace_id varchar(80) not null, content_hash varchar(64) not null,
  status varchar(30) not null, reject_reason varchar(255), created_at timestamp not null,
  constraint uq_knowledge_candidate_hash unique(tenant_id,content_hash)
);
alter table knowledge_candidate add column if not exists reviewed_by varchar(80);
alter table knowledge_candidate add column if not exists reviewed_at timestamp;
alter table knowledge_candidate add column if not exists knowledge_version varchar(255);
alter table knowledge_candidate add column if not exists rule_version varchar(80);
alter table knowledge_candidate add column if not exists prompt_version varchar(80);
alter table knowledge_candidate add column if not exists model_version varchar(120);
alter table knowledge_candidate add column if not exists applicable_conditions varchar(255);
alter table knowledge_candidate add column if not exists source_snapshot_hash varchar(64);
alter table knowledge_candidate add column if not exists dependency_json text;
alter table knowledge_doc add column if not exists lifecycle_status varchar(30) default 'ACTIVE';
alter table knowledge_doc add column if not exists source_version_hash varchar(64);
create table if not exists knowledge_dependency (
  knowledge_doc_id varchar(40) not null, tenant_id varchar(80) not null,
  dependency_type varchar(40) not null, dependency_key varchar(160) not null,
  dependency_version varchar(255) not null, created_at timestamp not null,
  primary key(knowledge_doc_id,dependency_type,dependency_key),
  foreign key(knowledge_doc_id) references knowledge_doc(id)
);
create index if not exists idx_knowledge_dependency_lookup
  on knowledge_dependency(tenant_id,dependency_type,dependency_key,dependency_version);
create table if not exists outbox_event (
  id varchar(80) primary key, tenant_id varchar(80) not null, aggregate_type varchar(50) not null,
  aggregate_id varchar(80) not null, event_type varchar(80) not null, payload_json text not null,
  status varchar(20) not null, created_at timestamp not null, published_at timestamp
);
alter table outbox_event add column if not exists attempt_count integer default 0;
alter table outbox_event add column if not exists next_attempt_at timestamp;
alter table outbox_event add column if not exists last_error text;
alter table outbox_event add column if not exists claimed_at timestamp;
alter table outbox_event add column if not exists worker_id varchar(80);
create index if not exists idx_outbox_pending on outbox_event(status,created_at);

-- 跨进程消息幂等：相同 tenant/conversation/messageId 复用同一组 Run/Trace 标识。
create table if not exists agent_request_dedup (
  tenant_id varchar(80) not null, conversation_id varchar(80) not null, message_id varchar(120) not null,
  user_id varchar(80) not null, request_hash varchar(64) not null, run_id varchar(80) not null,
  sub_run_id varchar(80) not null, trace_id varchar(80) not null, status varchar(30) not null,
  response_json text, error_type varchar(120), error_message text,
  created_at timestamp not null, updated_at timestamp not null,
  primary key(tenant_id,conversation_id,message_id)
);
create index if not exists idx_agent_request_run on agent_request_dedup(run_id);
alter table agent_request_dedup add column if not exists lease_owner varchar(80);
alter table agent_request_dedup add column if not exists lease_expires_at timestamp;

-- 四层 Memory 的持久层：短期摘要、情景记忆和结构化画像。
create table if not exists conversation_summary (
  tenant_id varchar(80) not null, user_id varchar(80) not null, conversation_id varchar(80) not null,
  summary text not null, version integer not null, updated_at timestamp not null,
  primary key(tenant_id,user_id,conversation_id)
);
-- summary 保留给旧版本读取；summary_json 是新的结构化正文。covered_* 与 version 共同参与 CAS，
-- 防止并发 Run 较晚完成的旧摘要覆盖已经覆盖更多消息的新摘要。
alter table conversation_summary add column if not exists summary_json text;
alter table conversation_summary add column if not exists covered_start_seq bigint default 0;
alter table conversation_summary add column if not exists covered_end_seq bigint default 0;
alter table conversation_summary add column if not exists source_message_hash varchar(64);
alter table conversation_summary add column if not exists model_name varchar(128);
alter table conversation_summary add column if not exists prompt_version varchar(80);
alter table conversation_summary add column if not exists input_tokens integer default 0;
alter table conversation_summary add column if not exists output_tokens integer default 0;
alter table conversation_summary add column if not exists status varchar(32) default 'ACTIVE';
alter table conversation_summary add column if not exists created_at timestamp;

-- 短期记忆的第三部分：任务索引只保存 businessTaskId/状态引用，不复制订单或退款对象。
create table if not exists conversation_task_index (
  tenant_id varchar(80) not null, user_id varchar(80) not null, conversation_id varchar(80) not null,
  business_task_id varchar(80) not null, agent_type varchar(40) not null, task_status varchar(40) not null,
  pending_action_type varchar(80), source_run_id varchar(80) not null, version bigint not null,
  expires_at timestamp, updated_at timestamp not null,
  primary key(tenant_id,user_id,conversation_id,business_task_id)
);
create index if not exists idx_conversation_task_active
  on conversation_task_index(tenant_id,user_id,conversation_id,task_status,expires_at);
create table if not exists episodic_memory (
  id varchar(80) primary key, tenant_id varchar(80) not null, user_id varchar(80) not null,
  memory_type varchar(40) not null, content text not null, source_run_id varchar(80) not null,
  content_hash varchar(64) not null, expires_at timestamp not null, created_at timestamp not null,
  constraint uq_memory_hash unique(tenant_id,user_id,content_hash)
);
alter table episodic_memory add column if not exists embedding_json text;
alter table episodic_memory add column if not exists embedding_model varchar(120);
alter table episodic_memory add column if not exists importance double precision default 0.5;
alter table episodic_memory add column if not exists status varchar(30) default 'ACTIVE';
-- MySQL/H2 中的行是权威事件，Elasticsearch 只是可重建的检索投影。以下字段用于领域过滤、
-- 来源追溯、可信度控制，以及区分存储期限和允许进入 Prompt 的期限。
alter table episodic_memory add column if not exists conversation_id varchar(80);
alter table episodic_memory add column if not exists business_task_id varchar(80);
alter table episodic_memory add column if not exists agent_type varchar(40) default 'UNKNOWN';
alter table episodic_memory add column if not exists event_type varchar(80) default 'PREFERENCE_CONFIRMED';
alter table episodic_memory add column if not exists subject_type varchar(40);
alter table episodic_memory add column if not exists subject_id varchar(80);
alter table episodic_memory add column if not exists source_type varchar(40) default 'USER_CONFIRMATION';
alter table episodic_memory add column if not exists source_event_id varchar(120);
alter table episodic_memory add column if not exists trust_level varchar(40) default 'USER_CONFIRMED';
alter table episodic_memory add column if not exists confidence double precision default 0.8;
alter table episodic_memory add column if not exists sensitivity_level varchar(40) default 'PERSONAL';
alter table episodic_memory add column if not exists occurred_at timestamp;
alter table episodic_memory add column if not exists prompt_eligible_until timestamp;
alter table episodic_memory add column if not exists retrieval_expires_at timestamp;
alter table episodic_memory add column if not exists storage_retain_until timestamp;
alter table episodic_memory add column if not exists version bigint default 1;
alter table episodic_memory add column if not exists updated_at timestamp;
create unique index if not exists uq_episodic_source_event
  on episodic_memory(tenant_id,source_event_id);
create index if not exists idx_episodic_recall
  on episodic_memory(tenant_id,user_id,agent_type,status,expires_at);
create table if not exists memory_candidate (
  id varchar(80) primary key, tenant_id varchar(80) not null, user_id varchar(80) not null,
  source_run_id varchar(80) not null, fact_key varchar(80) not null, fact_value varchar(255) not null,
  memory_type varchar(40) not null, confidence double precision not null, explicitly_confirmed boolean not null,
  ttl_days integer not null, status varchar(30) not null, reject_reason varchar(255),
  created_at timestamp not null, reviewed_at timestamp
);
alter table memory_candidate add column if not exists source_message_seq bigint;
alter table memory_candidate add column if not exists trust_level varchar(40) default 'MODEL_EXTRACTED';
alter table memory_candidate add column if not exists schema_version varchar(40) default 'profile-candidate-v2';
create index if not exists idx_memory_candidate_owner on memory_candidate(tenant_id,user_id,status,created_at);
create table if not exists user_profile_fact (
  tenant_id varchar(80) not null, user_id varchar(80) not null, fact_key varchar(80) not null,
  fact_value varchar(255) not null, source_run_id varchar(80) not null, confirmed boolean not null,
  expires_at timestamp, updated_at timestamp not null,
  primary key(tenant_id,user_id,fact_key)
);
alter table user_profile_fact add column if not exists source_type varchar(40) default 'USER_CONFIRMATION';
alter table user_profile_fact add column if not exists trust_level varchar(40) default 'USER_CONFIRMED';
alter table user_profile_fact add column if not exists confidence double precision default 1.0;
alter table user_profile_fact add column if not exists status varchar(30) default 'CONFIRMED';
alter table user_profile_fact add column if not exists confirmed_at timestamp;
alter table user_profile_fact add column if not exists version bigint default 1;

-- 画像字段目录是服务端白名单的持久投影。模型不能向该表插入字段，也不能扩大 allowed_agent_types。
create table if not exists profile_attribute_definition (
  attribute_code varchar(80) primary key, value_type varchar(32) not null,
  sensitivity_level varchar(32) not null, confirmation_required boolean not null,
  default_ttl_days integer, allowed_agent_types text not null, allowed_source_types text not null,
  embedding_allowed boolean not null, enabled boolean not null
);

-- 单行画像表保存“当前有效值”；历史表保存被覆盖版本，便于审计但 status=SUPERSEDED 的值永不召回。
create table if not exists user_profile_fact_history (
  id varchar(80) primary key, tenant_id varchar(80) not null, user_id varchar(80) not null,
  fact_key varchar(80) not null, fact_value varchar(255) not null, source_run_id varchar(80) not null,
  source_type varchar(40) not null, trust_level varchar(40) not null, confidence double precision not null,
  status varchar(30) not null, version bigint not null, expires_at timestamp,
  superseded_at timestamp not null
);
create index if not exists idx_profile_history_owner
  on user_profile_fact_history(tenant_id,user_id,fact_key,version);

-- Memory 访问审计只记录哈希/ID、Token 和选择结果，不复制正文。高基数 ID 进入审计而非 Prometheus 标签。
create table if not exists memory_access_audit (
  id varchar(80) primary key, trace_id varchar(80), tenant_id varchar(80) not null,
  user_id_hash varchar(64) not null, conversation_id varchar(80), run_id varchar(80),
  node_name varchar(80), memory_id varchar(160), memory_type varchar(32), source_type varchar(80),
  trust_level varchar(40), source_version varchar(80), retrieval_score double precision,
  access_action varchar(32) not null, access_result varchar(64) not null,
  token_count integer not null, created_at timestamp not null
);
create index if not exists idx_memory_access_trace on memory_access_audit(tenant_id,trace_id,created_at);

-- 旧版数据库缓存表，仅为历史数据迁移保留；v3 物理 L2 已统一使用带信封校验的 Redis。
create table if not exists agent_cache (
  cache_key varchar(255) primary key, cache_type varchar(40) not null, value_json text not null,
  version_tag varchar(160) not null, expires_at timestamp not null, created_at timestamp not null
);

-- 渐进式上下文审计。只保存来源、版本、哈希、Token 与裁剪原因，不复制完整 Prompt 或敏感业务数据。
create table if not exists ai_context_manifest (
  id varchar(80) primary key, tenant_id varchar(80) not null, conversation_id varchar(120) not null,
  run_id varchar(120) not null, trace_id varchar(120) not null, business_task_id varchar(80),
  agent_type varchar(40) not null, node_code varchar(60) not null, model_name varchar(120) not null,
  policy_version varchar(120) not null, prompt_versions_json text not null,
  total_input_tokens integer not null, reserved_output_tokens integer not null,
  safety_reserve_tokens integer not null, tool_protocol_tokens integer not null,
  trimmed_item_ids_json text not null, trim_reasons_json text not null, security_events_json text not null,
  assembled_context_hash varchar(64) not null, assemble_cost_ms bigint not null, assembled_at timestamp not null
);
create index if not exists idx_context_manifest_trace on ai_context_manifest(tenant_id,trace_id,assembled_at);
create index if not exists idx_context_manifest_run on ai_context_manifest(tenant_id,run_id,node_code,assembled_at);

create table if not exists ai_context_manifest_item (
  manifest_id varchar(80) not null, item_id varchar(160) not null, section_type varchar(60) not null,
  source_id varchar(160) not null, source_type varchar(80) not null, source_version varchar(160),
  content_hash varchar(64) not null, token_count integer not null, priority integer not null,
  trust_level varchar(40) not null, sensitivity_level varchar(40) not null, load_reason varchar(255),
  trimmed boolean not null, trim_reason varchar(120), delivery varchar(30) not null,
  primary key(manifest_id,item_id), foreign key(manifest_id) references ai_context_manifest(id)
);
create index if not exists idx_context_manifest_item_source
  on ai_context_manifest_item(section_type,source_id,source_version);

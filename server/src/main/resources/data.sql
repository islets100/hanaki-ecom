-- 旧演示库曾把整个平台当成一个 tenant；升级时按店铺拆成三个商家租户。
update merchant_store set tenant_id='merchant-hanaki' where id='STORE-HANAKI' and tenant_id='hanaki-demo';
update merchant_store set tenant_id='merchant-living' where id='STORE-LIVING' and tenant_id='hanaki-demo';
update merchant_store set tenant_id='merchant-trail' where id='STORE-TRAIL' and tenant_id='hanaki-demo';
update product set tenant_id='merchant-hanaki' where store_id='STORE-HANAKI' and tenant_id='hanaki-demo';
update product set tenant_id='merchant-living' where store_id='STORE-LIVING' and tenant_id='hanaki-demo';
update product set tenant_id='merchant-trail' where store_id='STORE-TRAIL' and tenant_id='hanaki-demo';
update customer_order set tenant_id='merchant-hanaki' where product_id in ('P1001','P1002','P1006','P1008') and tenant_id='hanaki-demo';
update customer_order set tenant_id='merchant-living' where product_id in ('P1003','P1004','P1007') and tenant_id='hanaki-demo';
update customer_order set tenant_id='merchant-trail' where product_id='P1005' and tenant_id='hanaki-demo';
update order_fulfillment set tenant_id=(select o.tenant_id from customer_order o where o.id=order_fulfillment.order_id)
where tenant_id='hanaki-demo' and exists(select 1 from customer_order o where o.id=order_fulfillment.order_id);
update logistics_event set tenant_id=(select o.tenant_id from customer_order o where o.id=logistics_event.order_id)
where tenant_id='hanaki-demo' and exists(select 1 from customer_order o where o.id=logistics_event.order_id);
update business_task set tenant_id=(select o.tenant_id from customer_order o where o.id=business_task.order_id)
where tenant_id='hanaki-demo' and order_id is not null and exists(select 1 from customer_order o where o.id=business_task.order_id);
update business_task_transition set tenant_id=(select b.tenant_id from business_task b where b.id=business_task_transition.business_task_id)
where tenant_id='hanaki-demo' and exists(select 1 from business_task b where b.id=business_task_transition.business_task_id);
update user_confirmation_record set tenant_id=(select b.tenant_id from business_task b where b.id=user_confirmation_record.business_task_id)
where tenant_id='hanaki-demo' and exists(select 1 from business_task b where b.id=user_confirmation_record.business_task_id);
update tool_operation_record set tenant_id=(select b.tenant_id from business_task b where b.id=tool_operation_record.business_task_id)
where tenant_id='hanaki-demo' and exists(select 1 from business_task b where b.id=tool_operation_record.business_task_id);
update app_account set tenant_id='platform' where tenant_id='hanaki-demo' and role in ('CUSTOMER','OFFICIAL_AGENT');
update app_account set tenant_id='merchant-hanaki' where tenant_id='hanaki-demo' and role='STORE_AGENT' and store_id='STORE-HANAKI';
update app_account set tenant_id='merchant-living' where tenant_id='hanaki-demo' and role='STORE_AGENT' and store_id='STORE-LIVING';
update app_account set tenant_id='merchant-trail' where tenant_id='hanaki-demo' and role='STORE_AGENT' and store_id='STORE-TRAIL';
update account_balance set tenant_id='platform' where account_id in (select id from app_account where role='CUSTOMER');
update balance_ledger set tenant_id='platform' where account_id in (select id from app_account where role='CUSTOMER');
insert into platform_balance(tenant_id,available_balance,version,updated_at)
select 'platform',available_balance,version,updated_at from platform_balance where tenant_id='hanaki-demo'
and not exists(select 1 from platform_balance where tenant_id='platform');
update platform_balance_ledger set tenant_id='platform' where tenant_id='hanaki-demo';
delete from platform_balance where tenant_id='hanaki-demo';
update knowledge_doc set tenant_id='merchant-hanaki' where id='K-PRODUCT-01' and tenant_id='hanaki-demo';
update knowledge_doc set tenant_id='platform' where id in ('K-AFTER-01','K-CANCEL-01','K-LOGISTICS-01','K-COMP-01','K-COMMON-01') and tenant_id='hanaki-demo';
update store_ai_session set tenant_id=(select p.tenant_id from product p where p.id=store_ai_session.product_id)
where tenant_id='hanaki-demo' and exists(select 1 from product p where p.id=store_ai_session.product_id);
update official_ai_session set tenant_id='platform' where tenant_id='hanaki-demo';
update conversation_message set tenant_id=(select s.tenant_id from store_ai_session s where s.conversation_id=conversation_message.conversation_id)
where tenant_id='hanaki-demo' and exists(select 1 from store_ai_session s where s.conversation_id=conversation_message.conversation_id);
update conversation_message set tenant_id='platform' where tenant_id='hanaki-demo';
update support_case set tenant_id=(select o.tenant_id from customer_order o where o.id=support_case.order_id)
where tenant_id='hanaki-demo' and order_id is not null and exists(select 1 from customer_order o where o.id=support_case.order_id);
update support_case set tenant_id='merchant-hanaki' where tenant_id='hanaki-demo' and store_id='STORE-HANAKI' and queue_name='STORE';
update support_case set tenant_id='merchant-living' where tenant_id='hanaki-demo' and store_id='STORE-LIVING' and queue_name='STORE';
update support_case set tenant_id='merchant-trail' where tenant_id='hanaki-demo' and store_id='STORE-TRAIL' and queue_name='STORE';
update support_case set tenant_id='platform' where tenant_id='hanaki-demo';
update purchase_request_dedup set tenant_id='platform' where tenant_id='hanaki-demo';
update saas_tenant set status='MIGRATED' where tenant_id='hanaki-demo';

-- 显式声明列名，保证从旧版数据库增加 store_id 后仍可重复执行种子脚本。
insert into product (id,tenant_id,name,subtitle,category,price,old_price,stock,badge,attributes_json,store_id) values
('P1001','merchant-hanaki','云感羊毛通勤大衣','澳洲美利奴羊毛 · 轻盈保暖','服饰',1299,1599,36,'今日上新','{"colors":["驼色","黑色"],"sizes":["S","M","L"]}','STORE-HANAKI'),
('P1002','merchant-hanaki','静听 Pro 降噪耳机','45dB 深度降噪 · 40h 续航','数码',899,1099,82,'热卖','{"bluetooth":"5.4","multipoint":true,"warranty":"2年"}','STORE-HANAKI'),
('P1003','merchant-living','轻羽人体工学椅','动态腰托 · 12 区精细调节','家居',1680,2099,18,'会员专享','{"warranty":"5年","load":"120kg"}','STORE-LIVING'),
('P1004','merchant-living','澄明无线氛围灯','无频闪阅读 · 智能调光','家居',329,399,61,'包邮','{"battery":"18h","temperature":"2700K-5000K"}','STORE-LIVING'),
('P1005','merchant-trail','山野轻量徒步鞋','防泼水 · Vibram 防滑底','运动',699,829,43,'口碑新品','{"waterproof":"防泼水","sizes":["36-45"]}','STORE-TRAIL'),
('P1006','merchant-hanaki','无界智能手表 S3','全天候健康监测 · 双频 GPS','数码',1499,1799,27,'限时直降','{"battery":"7天","waterproof":"5ATM"}','STORE-HANAKI'),
('P1007','merchant-living','植萃修护精华液','神经酰胺复配 · 维稳修护','美护',368,428,104,'回购榜 No.1','{"volume":"30ml","skin":"敏感肌可用"}','STORE-LIVING'),
('P1008','merchant-hanaki','城市轻旅托特包','头层牛皮 · 15 英寸电脑位','箱包',1180,1390,22,'匠心之选','{"material":"头层牛皮","laptop":"15英寸"}','STORE-HANAKI') on conflict do nothing;

insert into merchant_store values
('STORE-HANAKI','merchant-hanaki','花木数码旗舰店','曜','专注高品质智能数码与通勤装备，平台认证旗舰店。',4.96,4.92,'上海',current_timestamp),
('STORE-LIVING','merchant-living','栖居生活设计馆','栖','精选兼具实用性与审美的居家用品。',4.91,4.89,'杭州',current_timestamp),
('STORE-TRAIL','merchant-trail','山野行迹户外店','山','为城市轻户外与长线徒步提供可靠装备。',4.94,4.90,'成都',current_timestamp) on conflict do nothing;

update product set store_id='STORE-HANAKI' where tenant_id='merchant-hanaki' and id in ('P1001','P1002','P1006','P1008');
update product set store_id='STORE-LIVING' where tenant_id='merchant-living' and id in ('P1003','P1004','P1007');
update product set store_id='STORE-TRAIL' where tenant_id='merchant-trail' and id='P1005';

-- 可直接用于验收的官方客服与客户账号。密码哈希使用 IdentityService 相同的
-- PBKDF2-HMAC-SHA256（120000 次）格式；重复启动只会校准这两个测试账号，不会重复建号。
insert into app_account(id,tenant_id,username,password_hash,display_name,role,store_id,enabled,created_at)
values('OFF-DEMO-OFFICIAL','platform','official_service',
  '120000:ERITFBUWFxgZGhscHR4fIA==:vDbOEJM/z41QsmcEBW4lrLThh0zHCplB1IPGe55VDW0=',
  '平台官方客服','OFFICIAL_AGENT',null,true,current_timestamp) on conflict do nothing;
update app_account set password_hash='120000:ERITFBUWFxgZGhscHR4fIA==:vDbOEJM/z41QsmcEBW4lrLThh0zHCplB1IPGe55VDW0=',
  display_name='平台官方客服',role='OFFICIAL_AGENT',store_id=null,enabled=true,
  failed_login_attempts=0,locked_until=null
where tenant_id='platform' and username='official_service';

insert into app_account(id,tenant_id,username,password_hash,display_name,role,store_id,enabled,created_at)
values('STO-DEMO-LIVING','merchant-living','living_service',
  '120000:ICEiIyQlJicoKSorLC0uLw==:alJfis066Ffa3mhsCPOk0nhWDCtsYz1Sg7C15Zl8QGA=',
  '栖居店铺客服','STORE_AGENT','STORE-LIVING',true,current_timestamp) on conflict do nothing;
update app_account set password_hash='120000:ICEiIyQlJicoKSorLC0uLw==:alJfis066Ffa3mhsCPOk0nhWDCtsYz1Sg7C15Zl8QGA=',
  display_name='栖居店铺客服',role='STORE_AGENT',store_id='STORE-LIVING',enabled=true,
  failed_login_attempts=0,locked_until=null
where tenant_id='merchant-living' and username='living_service';

insert into app_account(id,tenant_id,username,password_hash,display_name,role,store_id,enabled,created_at)
values('CUS-12345678','platform','12345678',
  '120000:AQIDBAUGBwgJCgsMDQ4PEA==:CmVuFbe5FLsConuF6DlDYJSt7XhtAOxRWcyKUeiir2Y=',
  '退款流程测试用户','CUSTOMER',null,true,current_timestamp) on conflict do nothing;
update app_account set password_hash='120000:AQIDBAUGBwgJCgsMDQ4PEA==:CmVuFbe5FLsConuF6DlDYJSt7XhtAOxRWcyKUeiir2Y=',
  display_name='退款流程测试用户',role='CUSTOMER',store_id=null,enabled=true,
  failed_login_attempts=0,locked_until=null
where tenant_id='platform' and username='12345678';

-- 为升级前已存在的客户补建余额；新注册客户由 IdentityService 在同一事务中初始化。
insert into account_balance(account_id,tenant_id,available_balance,version,updated_at)
select a.id,a.tenant_id,10000,0,current_timestamp from app_account a
where a.role='CUSTOMER' and not exists(select 1 from account_balance b where b.account_id=a.id);

insert into balance_ledger(id,account_id,tenant_id,entry_type,amount,balance_after,reference_id,description,created_at)
select 'BL-INIT-12345678',a.id,'platform','INITIAL_GRANT',10000,10000,a.id,'测试用户初始额度',current_timestamp
from app_account a where a.tenant_id='platform' and a.username='12345678'
and not exists(select 1 from balance_ledger b where b.account_id=a.id and b.entry_type='INITIAL_GRANT');

-- 这笔无线氛围灯订单已经发货但尚未签收。“不想要了”不满足未发货取消规则，退款评分应低于
-- 自动退款门槛；履约事实也会强制进入店铺人工审核，适合完整验证客服审核与退款到账。
insert into customer_order(id,tenant_id,user_id,product_id,sku,amount,status,payment_status,logistics_status,created_at)
select 'OD-TEST-12345678','merchant-living',a.id,'P1004','暖白色 · 标准版',329,'SHIPPED',
  'BALANCE_PAID','运输中',timestamp '2026-08-03 20:00:00'
from app_account a where a.tenant_id='platform' and a.username='12345678'
on conflict do nothing;

update product set stock=stock-1 where id='P1004' and tenant_id='merchant-living' and stock>0
and not exists(select 1 from balance_ledger b join app_account a on a.id=b.account_id
  where a.tenant_id='platform' and a.username='12345678'
  and b.entry_type='PURCHASE' and b.reference_id='OD-TEST-12345678');
update account_balance set available_balance=available_balance-329,version=version+1,updated_at=current_timestamp
where account_id=(select id from app_account where tenant_id='platform' and username='12345678')
and available_balance>=329
and not exists(select 1 from balance_ledger b where b.account_id=account_balance.account_id
  and b.entry_type='PURCHASE' and b.reference_id='OD-TEST-12345678');
insert into balance_ledger(id,account_id,tenant_id,entry_type,amount,balance_after,reference_id,description,created_at)
select 'BL-PURCHASE-12345678',a.id,'platform','PURCHASE',-329,b.available_balance,
  'OD-TEST-12345678','购买「澄明无线氛围灯」',timestamp '2026-08-03 20:00:00'
from app_account a join account_balance b on b.account_id=a.id
where a.tenant_id='platform' and a.username='12345678' on conflict do nothing;

insert into platform_balance(tenant_id,available_balance,version,updated_at)
values('platform',0,0,current_timestamp) on conflict do nothing;
update platform_balance set available_balance=available_balance+329,version=version+1,updated_at=current_timestamp
where tenant_id='platform' and not exists(select 1 from platform_balance_ledger
  where tenant_id='platform' and entry_type='PAYMENT' and reference_id='OD-TEST-12345678');
insert into platform_balance_ledger(id,tenant_id,entry_type,amount,balance_after,reference_id,description,created_at)
select 'PBL-PAYMENT-12345678','platform','PAYMENT',329,available_balance,
  'OD-TEST-12345678','订单收款「澄明无线氛围灯」',timestamp '2026-08-03 20:00:00'
from platform_balance where tenant_id='platform' on conflict do nothing;

insert into order_fulfillment(order_id,tenant_id,store_id,planned_ship_at,estimated_arrival_at,
  shipped_at,delivered_at,status,updated_at) values
('OD-TEST-12345678','merchant-living','STORE-LIVING',timestamp '2026-08-04 00:30:00',
 timestamp '2026-08-05 18:00:00',timestamp '2026-08-04 01:10:00',null,'SHIPPED',current_timestamp)
on conflict do nothing;
insert into logistics_event(id,tenant_id,order_id,event_time,location,description) values
('LE-TEST-12345678-1','merchant-living','OD-TEST-12345678',timestamp '2026-08-03 20:00:00','花木商城','余额支付成功，商家正在备货'),
('LE-TEST-12345678-2','merchant-living','OD-TEST-12345678',timestamp '2026-08-04 01:10:00','杭州集散中心','快件已由顺丰速运揽收'),
('LE-TEST-12345678-3','merchant-living','OD-TEST-12345678',timestamp '2026-08-04 01:45:00','杭州集散中心','快件运输中，等待发往下一站')
on conflict do nothing;

-- 同步修正已经创建过的测试订单，保证重复启动后时间轴仍为：20:00 下单、00:30 计划发货、
-- 01:10 实际揽收、01:45 开始运输。只更新时间事实，不重置已经发生的退款等资金状态。
update customer_order set created_at=timestamp '2026-08-03 20:00:00'
where id='OD-TEST-12345678' and tenant_id='merchant-living';
update order_fulfillment set planned_ship_at=timestamp '2026-08-04 00:30:00',
  estimated_arrival_at=timestamp '2026-08-05 18:00:00',shipped_at=timestamp '2026-08-04 01:10:00',
  updated_at=current_timestamp
where order_id='OD-TEST-12345678' and tenant_id='merchant-living' and delivered_at is null;
update logistics_event set event_time=timestamp '2026-08-03 20:00:00',
  location='花木商城',description='余额支付成功，商家正在备货'
where id='LE-TEST-12345678-1' and tenant_id='merchant-living';
update logistics_event set event_time=timestamp '2026-08-04 01:10:00',
  location='杭州集散中心',description='快件已由顺丰速运揽收'
where id='LE-TEST-12345678-2' and tenant_id='merchant-living';
update logistics_event set event_time=timestamp '2026-08-04 01:45:00',
  location='杭州集散中心',description='快件运输中，等待发往下一站'
where id='LE-TEST-12345678-3' and tenant_id='merchant-living';

insert into customer_order values
('OD202607274832','merchant-hanaki','user-1001','P1002','花木黑 · 标准版',899,'SHIPPED','PAID','运输中',timestamp '2026-07-27 21:32:00'),
('OD202607186351','merchant-living','user-1001','P1007','30ml',368,'COMPLETED','PAID','已签收',timestamp '2026-07-18 09:20:00') on conflict do nothing;

insert into logistics_event values
('LE1','merchant-hanaki','OD202607274832',timestamp '2026-07-29 08:42:00','上海转运中心','快件已到达上海转运中心'),
('LE2','merchant-hanaki','OD202607274832',timestamp '2026-07-28 23:10:00','苏州集散中心','快件已发往上海'),
('LE3','merchant-hanaki','OD202607274832',timestamp '2026-07-28 17:10:00','苏州','顺丰速运已揽收') on conflict do nothing;

insert into knowledge_doc(id,tenant_id,domain,title,content,version,active) values
('K-PRODUCT-P1001','merchant-hanaki','PRE_SALE','云感羊毛通勤大衣商品介绍','云感羊毛通勤大衣采用澳洲美利奴羊毛，主打轻盈保暖，适合日常通勤穿着。商品提供驼色、黑色两种颜色，以及 S、M、L 三个尺码。购买时请根据需要选择颜色和尺码；实时价格与库存以商品页面和提交订单时的结果为准。','v1',true),
('K-PRODUCT-01','merchant-hanaki','PRE_SALE','静听 Pro 商品说明','静听 Pro 支持蓝牙 5.4、两台设备快速切换、45dB 主动降噪，单次续航 10 小时，配合充电盒最长 40 小时。整机提供两年有限保修。','v8',true),
('K-AFTER-01','platform','AFTER_SALE','七天无理由政策','普通商品自签收次日起 7 个自然日内，在商品完好、配件及包装齐全时可申请无理由退货。已激活的数字商品、贴身用品及定制商品除外；质量问题不受无理由适用范围限制。','v12',true),
('K-CANCEL-01','platform','AFTER_SALE','未发货订单取消规则','余额支付订单在商家实际发货前，购买人可以申请取消订单并全额退款。服务端确认订单支付状态为余额已支付且物流状态仍为待发货时，可在用户确认后自动退款，无需店铺人工审核；服务端还必须以权威履约记录确认不存在实际发货时间，已经发货的订单不适用本规则。','v2',true),
('K-LOGISTICS-01','platform','IN_SALE','物流异常规则','普通快递轨迹连续 48 小时未更新定义为轻度停滞，72 小时未更新或出现疑似丢件时创建物流异常工单。自动催单前需要用户确认。','v5',true),
('K-COMP-01','platform','AFTER_SALE','补偿规则','补偿由规则引擎根据订单金额、延迟时长、历史补偿与风险等级计算。模型无权自行决定金额；自动补偿默认只允许优惠券，超过商家上限必须人工审批。','v4',true),
('K-COMMON-01','platform','COMMON','服务承诺','平台提供正品溯源、30 天价保和全链路售后进度查询。退款、取消订单、修改地址与补偿发放等写操作必须由用户明确确认。','v3',true) on conflict do nothing;

-- v1 在首次引入时未把权威支付状态送入评分器；只升级该已知版本，避免覆盖后续后台治理发布的新版本。
update knowledge_doc set content='余额支付订单在商家实际发货前，购买人可以申请取消订单并全额退款。服务端确认订单支付状态为余额已支付且物流状态仍为待发货时，可在用户确认后自动退款，无需店铺人工审核；服务端还必须以权威履约记录确认不存在实际发货时间，已经发货的订单不适用本规则。',
  version='v2',active=true where id='K-CANCEL-01' and tenant_id='platform' and version='v1';

-- MemoryPolicyEngine 中的代码白名单是最终安全边界；本表用于后台治理、审计和未来配置发布。
-- 用 on conflict 保证开发环境重复启动不会制造重复定义。
insert into profile_attribute_definition(attribute_code,value_type,sensitivity_level,confirmation_required,
  default_ttl_days,allowed_agent_types,allowed_source_types,embedding_allowed,enabled) values
('尺码偏好','STRING','PERSONAL',true,90,'["PRE_SALE"]','["USER_CONFIRMATION","HUMAN_CONFIRMATION"]',true,true),
('颜色偏好','STRING','PERSONAL',true,180,'["PRE_SALE"]','["USER_CONFIRMATION","HUMAN_CONFIRMATION"]',true,true),
('品牌偏好','STRING_LIST','PERSONAL',true,180,'["PRE_SALE"]','["USER_CONFIRMATION","HUMAN_CONFIRMATION"]',true,true),
('收货时段','STRING','PERSONAL',true,90,'["PRE_SALE","IN_SALE"]','["USER_CONFIRMATION"]',false,true),
('沟通偏好','STRING','PERSONAL',true,365,'["PRE_SALE","IN_SALE","AFTER_SALE","COMPLAINT"]','["USER_CONFIRMATION","HUMAN_CONFIRMATION"]',false,true),
('语言偏好','STRING','PERSONAL',true,365,'["PRE_SALE","IN_SALE","AFTER_SALE","COMPLAINT"]','["USER_CONFIRMATION","HUMAN_CONFIRMATION"]',false,true)
on conflict do nothing;

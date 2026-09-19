-- 本地联用演示数据，只打 liang_gateway，不要打进 liang_gateway_test。
-- 表结构由 Flyway 创建；本脚本可重复执行。
-- MCP 数据面：POST /mcp/demo ，访问令牌 lgw_demo_mcp_token 。
-- 工具出站打到 JSONPlaceholder，不经过模型，不记账。

DELETE FROM `mcp_tool`
WHERE `server_code` IN ('mcs_demo_jsonplaceholder', 'mcs_demo_disabled');
DELETE FROM `mcp_server`
WHERE `code` IN ('mcs_demo_jsonplaceholder', 'mcs_demo_disabled');

INSERT INTO `mcp_server` (
    `code`, `name`, `path`, `description`, `version`, `enabled`, `create_time`, `update_time`
) VALUES
    (
        'mcs_demo_jsonplaceholder',
        'JSONPlaceholder 演示',
        'demo',
        '本地联用的公开 REST 演示。覆盖 path / query / body 三种参数落点。',
        '1.0.0',
        1,
        CURRENT_TIMESTAMP(3),
        CURRENT_TIMESTAMP(3)
    ),
    (
        'mcs_demo_disabled',
        '已停用演示',
        'demo-off',
        '未启用，discover / list / call 都应视为不存在。',
        '1.0.0',
        0,
        CURRENT_TIMESTAMP(3),
        CURRENT_TIMESTAMP(3)
    );

INSERT INTO `mcp_tool` (
    `code`, `server_code`, `name`, `description`, `http_url`, `http_method`,
    `http_headers`, `timeout_ms`, `args`, `enabled`, `create_time`, `update_time`
) VALUES
    (
        'mct_demo_get_post',
        'mcs_demo_jsonplaceholder',
        'get_post',
        '按 ID 查询一篇演示文章',
        'https://jsonplaceholder.typicode.com/posts/{id}',
        'GET',
        '{"Accept":"application/json"}',
        15000,
        '[{"name":"id","value_type":"string","required":true,"description":"文章 ID，例如 1","position":"path"}]',
        1,
        CURRENT_TIMESTAMP(3),
        CURRENT_TIMESTAMP(3)
    ),
    (
        'mct_demo_list_posts',
        'mcs_demo_jsonplaceholder',
        'list_posts',
        '列出演示文章，可按作者过滤',
        'https://jsonplaceholder.typicode.com/posts',
        'GET',
        '{"Accept":"application/json"}',
        15000,
        '[{"name":"userId","value_type":"integer","required":false,"description":"作者 ID，可空","position":"query"}]',
        1,
        CURRENT_TIMESTAMP(3),
        CURRENT_TIMESTAMP(3)
    ),
    (
        'mct_demo_create_post',
        'mcs_demo_jsonplaceholder',
        'create_post',
        '新建一篇演示文章（JSONPlaceholder 不会持久化）',
        'https://jsonplaceholder.typicode.com/posts',
        'POST',
        '{"Accept":"application/json","Content-Type":"application/json"}',
        15000,
        '[{"name":"title","value_type":"string","required":true,"description":"标题","position":"body"},{"name":"body","value_type":"string","required":true,"description":"正文","position":"body"},{"name":"userId","value_type":"integer","required":true,"description":"作者 ID","position":"body"}]',
        1,
        CURRENT_TIMESTAMP(3),
        CURRENT_TIMESTAMP(3)
    ),
    (
        'mct_demo_get_user',
        'mcs_demo_jsonplaceholder',
        'get_user',
        '按 ID 查询一个演示用户',
        'https://jsonplaceholder.typicode.com/users/{id}',
        'GET',
        '{"Accept":"application/json"}',
        15000,
        '[{"name":"id","value_type":"string","required":true,"description":"用户 ID，例如 1","position":"path"}]',
        1,
        CURRENT_TIMESTAMP(3),
        CURRENT_TIMESTAMP(3)
    ),
    (
        'mct_demo_disabled_tool',
        'mcs_demo_jsonplaceholder',
        'get_todo',
        '未启用，不应出现在 tools/list，call 视为工具不存在',
        'https://jsonplaceholder.typicode.com/todos/{id}',
        'GET',
        '{}',
        15000,
        '[{"name":"id","value_type":"string","required":true,"description":"待办 ID","position":"path"}]',
        0,
        CURRENT_TIMESTAMP(3),
        CURRENT_TIMESTAMP(3)
    ),
    (
        'mct_demo_off_noop',
        'mcs_demo_disabled',
        'noop',
        '挂在已停用 server 上，整台入口不服务',
        'https://jsonplaceholder.typicode.com/posts/1',
        'GET',
        '{}',
        15000,
        '[]',
        1,
        CURRENT_TIMESTAMP(3),
        CURRENT_TIMESTAMP(3)
    );

INSERT INTO `user` (`code`, `name`, `authority`, `enabled`, `create_time`, `update_time`)
VALUES ('usr_demo', '本地演示用户', 'DATA', 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)) AS new
ON DUPLICATE KEY UPDATE
    `name` = new.`name`,
    `authority` = new.`authority`,
    `enabled` = new.`enabled`,
    `update_time` = new.`update_time`;

INSERT INTO `user_access_token` (
    `code`, `user_code`, `access_token`, `enabled`, `expire_time`, `qpm_limit`, `create_time`, `update_time`
) VALUES (
    'tok_demo_mcp',
    'usr_demo',
    'lgw_demo_mcp_token',
    1,
    NULL,
    1000,
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
) AS new
ON DUPLICATE KEY UPDATE
    `access_token` = new.`access_token`,
    `enabled` = new.`enabled`,
    `expire_time` = new.`expire_time`,
    `qpm_limit` = new.`qpm_limit`,
    `update_time` = new.`update_time`;

INSERT INTO `usage_limit` (
    `user_code`, `token_code`, `limit_type`, `usage`, `used`, `create_time`, `update_time`
) VALUES (
    'usr_demo', 'tok_demo_mcp', 0, 0, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
) AS new
ON DUPLICATE KEY UPDATE
    `usage` = new.`usage`,
    `update_time` = new.`update_time`;

INSERT INTO `llm_apikey_config` (
    `code`, `name`, `provider`, `base_url`, `secret`, `prefix`, `enabled`, `expire_time`, `create_time`, `update_time`
) VALUES (
    'apk_demo_mock',
    '本地 Mock 供应商',
    'mock',
    'http://127.0.0.1:8090/v1',
    'sk-mock-local',
    'sk-mock-',
    1,
    NULL,
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
) AS new
ON DUPLICATE KEY UPDATE
    `name` = new.`name`,
    `provider` = new.`provider`,
    `base_url` = new.`base_url`,
    `secret` = new.`secret`,
    `prefix` = new.`prefix`,
    `enabled` = new.`enabled`,
    `update_time` = new.`update_time`;

INSERT INTO `llm_model` (
    `code`, `name`, `provider`, `apikey_code`,
    `input_price_fen_per_million`, `output_price_fen_per_million`,
    `enabled`, `create_time`, `update_time`
) VALUES (
    'mdl_demo_mock',
    'mock-llm',
    'mock',
    'apk_demo_mock',
    1,
    1,
    1,
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
) AS new
ON DUPLICATE KEY UPDATE
    `name` = new.`name`,
    `provider` = new.`provider`,
    `apikey_code` = new.`apikey_code`,
    `enabled` = new.`enabled`,
    `update_time` = new.`update_time`;

INSERT INTO `user_access_token` (
    `code`, `user_code`, `access_token`, `enabled`, `expire_time`, `qpm_limit`, `create_time`, `update_time`
) VALUES (
    'tok_demo_chat',
    'usr_demo',
    'lgw_demo_chat_token',
    1,
    NULL,
    100000,
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
) AS new
ON DUPLICATE KEY UPDATE
    `access_token` = new.`access_token`,
    `enabled` = new.`enabled`,
    `qpm_limit` = new.`qpm_limit`,
    `update_time` = new.`update_time`;

INSERT INTO `usage_limit` (
    `user_code`, `token_code`, `limit_type`, `usage`, `used`, `create_time`, `update_time`
) VALUES (
    'usr_demo', 'tok_demo_chat', 0, 0, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
) AS new
ON DUPLICATE KEY UPDATE
    `usage` = new.`usage`,
    `update_time` = new.`update_time`;

INSERT INTO `user_access_token_model` (`token_code`, `model`, `create_time`)
SELECT 'tok_demo_chat', 'mock-llm', CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM `user_access_token_model`
    WHERE `token_code` = 'tok_demo_chat' AND `model` = 'mock-llm'
);

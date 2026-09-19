-- Existing user/token/model usage indexes and upstream-key log index are retained.
ALTER TABLE `llm_call_log`
    ADD KEY `idx_lcl_time_id` (`create_time`, `id`),
    ADD KEY `idx_lcl_model_time` (`model`, `create_time`);

ALTER TABLE `usage_record`
    ADD KEY `idx_usage_time_id` (`create_time`, `id`);

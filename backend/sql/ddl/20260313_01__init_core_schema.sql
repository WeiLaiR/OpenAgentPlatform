-- OpenAgent core schema initialization
-- Target database: openagent
-- Created at: 2026-03-13

USE openagent;

CREATE TABLE IF NOT EXISTS conversation (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(255) DEFAULT NULL,
  mode_code VARCHAR(32) NOT NULL DEFAULT 'CHAT' COMMENT 'CHAT/RAG/AGENT/RAG_AGENT',
  enable_rag TINYINT NOT NULL DEFAULT 0,
  enable_agent TINYINT NOT NULL DEFAULT 0,
  memory_enabled TINYINT NOT NULL DEFAULT 1,
  system_prompt_id BIGINT UNSIGNED DEFAULT NULL,
  agent_profile_id BIGINT UNSIGNED DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1=ACTIVE,0=ARCHIVED,-1=DELETED',
  last_message_at DATETIME(3) DEFAULT NULL,
  ext_json JSON DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_conversation_user_id (user_id),
  KEY idx_conversation_last_message_at (last_message_at),
  KEY idx_conversation_status_created_at (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_message (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  role_code VARCHAR(32) NOT NULL COMMENT 'SYSTEM/USER/ASSISTANT/TOOL',
  message_type VARCHAR(32) NOT NULL COMMENT 'TEXT/TOOL_CALL/TOOL_RESULT/RAG_CONTEXT',
  content MEDIUMTEXT,
  content_json JSON DEFAULT NULL,
  request_id VARCHAR(64) DEFAULT NULL,
  parent_message_id BIGINT UNSIGNED DEFAULT NULL,
  token_count INT DEFAULT NULL,
  model_name VARCHAR(128) DEFAULT NULL,
  finish_reason VARCHAR(64) DEFAULT NULL,
  error_code VARCHAR(64) DEFAULT NULL,
  error_message VARCHAR(512) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_conv_msg_conversation_id_created_at (conversation_id, created_at),
  KEY idx_conv_msg_request_id (request_id),
  KEY idx_conv_msg_parent_message_id (parent_message_id),
  KEY idx_conv_msg_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_kb_binding (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT UNSIGNED NOT NULL,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  selected TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_conv_kb (conversation_id, knowledge_base_id),
  KEY idx_conv_kb_kb_id (knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_mcp_binding (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT UNSIGNED NOT NULL,
  mcp_server_id BIGINT UNSIGNED NOT NULL,
  selected TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_conv_mcp (conversation_id, mcp_server_id),
  KEY idx_conv_mcp_server_id (mcp_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trace_event (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT UNSIGNED NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  message_id BIGINT UNSIGNED DEFAULT NULL,
  event_type VARCHAR(64) NOT NULL,
  event_stage VARCHAR(64) DEFAULT NULL,
  event_source VARCHAR(32) NOT NULL DEFAULT 'APP_CUSTOM' COMMENT 'LANGCHAIN4J_NATIVE/APP_CUSTOM',
  event_payload_json JSON DEFAULT NULL,
  success_flag TINYINT NOT NULL DEFAULT 1,
  cost_millis INT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_trace_conversation_id_created_at (conversation_id, created_at),
  KEY idx_trace_request_id_created_at (request_id, created_at),
  KEY idx_trace_event_type_created_at (event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_base (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1=ACTIVE,0=DISABLED',
  embedding_model_name VARCHAR(128) NOT NULL,
  embedding_dimension INT NOT NULL,
  milvus_database_name VARCHAR(128) DEFAULT NULL,
  milvus_collection_name VARCHAR(128) NOT NULL,
  milvus_partition_name VARCHAR(128) NOT NULL,
  parser_strategy VARCHAR(64) DEFAULT 'TIKA',
  chunk_strategy VARCHAR(64) DEFAULT 'DEFAULT',
  chunk_size INT NOT NULL DEFAULT 1000,
  chunk_overlap INT NOT NULL DEFAULT 150,
  ext_json JSON DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_kb_name_owner (owner_user_id, name),
  KEY idx_kb_status_created_at (status, created_at),
  KEY idx_kb_collection_partition (milvus_collection_name, milvus_partition_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_file (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_ext VARCHAR(32) DEFAULT NULL,
  file_size BIGINT UNSIGNED NOT NULL,
  storage_type VARCHAR(32) NOT NULL DEFAULT 'LOCAL' COMMENT 'LOCAL/MINIO/OSS/S3',
  storage_uri VARCHAR(512) NOT NULL,
  file_hash VARCHAR(128) DEFAULT NULL,
  parse_status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PARSING/PARSED/FAILED',
  index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/INDEXING/INDEXED/FAILED',
  parser_name VARCHAR(128) DEFAULT NULL,
  parser_result_json JSON DEFAULT NULL,
  error_message VARCHAR(512) DEFAULT NULL,
  uploaded_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_kf_kb_id_created_at (knowledge_base_id, created_at),
  KEY idx_kf_parse_status (parse_status),
  KEY idx_kf_index_status (index_status),
  KEY idx_kf_file_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_segment (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  file_id BIGINT UNSIGNED NOT NULL,
  segment_no INT NOT NULL,
  text_preview VARCHAR(1024) DEFAULT NULL,
  full_text MEDIUMTEXT,
  token_count INT DEFAULT NULL,
  page_no INT DEFAULT NULL,
  source_title VARCHAR(255) DEFAULT NULL,
  source_path VARCHAR(512) DEFAULT NULL,
  metadata_json JSON DEFAULT NULL,
  milvus_primary_key VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_file_segment_no (file_id, segment_no),
  UNIQUE KEY uk_segment_milvus_pk (milvus_primary_key),
  KEY idx_ks_kb_file_id (knowledge_base_id, file_id),
  KEY idx_ks_page_no (page_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mcp_server (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) DEFAULT NULL,
  protocol_type VARCHAR(32) NOT NULL DEFAULT 'ANTHROPIC_MCP',
  transport_type VARCHAR(32) NOT NULL COMMENT 'STDIO/STREAMABLE_HTTP',
  endpoint VARCHAR(512) DEFAULT NULL,
  command_line VARCHAR(512) DEFAULT NULL,
  args_json JSON DEFAULT NULL,
  env_json JSON DEFAULT NULL,
  headers_json JSON DEFAULT NULL,
  auth_type VARCHAR(32) DEFAULT 'NONE' COMMENT 'NONE/BEARER/BASIC/CUSTOM',
  auth_config_json JSON DEFAULT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/HEALTHY/UNHEALTHY',
  risk_level VARCHAR(32) NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW/MEDIUM/HIGH',
  ext_json JSON DEFAULT NULL,
  last_connected_at DATETIME(3) DEFAULT NULL,
  last_sync_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_mcp_owner_name (owner_user_id, name),
  KEY idx_mcp_enabled_health (enabled, health_status),
  KEY idx_mcp_transport_type (transport_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mcp_tool_snapshot (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  mcp_server_id BIGINT UNSIGNED NOT NULL,
  runtime_tool_name VARCHAR(128) NOT NULL COMMENT 'runtime unique tool name, e.g. serverAlias__toolName',
  origin_tool_name VARCHAR(128) NOT NULL COMMENT 'original remote MCP tool name',
  tool_title VARCHAR(255) DEFAULT NULL,
  description TEXT,
  input_schema_json JSON DEFAULT NULL,
  output_schema_json JSON DEFAULT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  risk_level VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
  version_no VARCHAR(64) DEFAULT NULL,
  sync_hash VARCHAR(128) DEFAULT NULL,
  synced_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_mcp_runtime_tool (runtime_tool_name),
  UNIQUE KEY uk_mcp_server_origin_tool (mcp_server_id, origin_tool_name),
  KEY idx_mcp_tool_enabled (enabled),
  KEY idx_mcp_tool_synced_at (synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_memory_session (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT UNSIGNED NOT NULL,
  memory_id VARCHAR(128) NOT NULL,
  memory_type VARCHAR(32) NOT NULL DEFAULT 'TOKEN_WINDOW' COMMENT 'MESSAGE_WINDOW/TOKEN_WINDOW',
  max_messages INT DEFAULT NULL,
  max_tokens INT DEFAULT NULL,
  tokenizer_name VARCHAR(128) DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_memory_session_memory_id (memory_id),
  UNIQUE KEY uk_memory_session_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_memory_message (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  memory_session_id BIGINT UNSIGNED NOT NULL,
  message_order INT NOT NULL,
  role_code VARCHAR(32) NOT NULL,
  message_json JSON NOT NULL,
  token_count INT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_memory_order (memory_session_id, message_order),
  KEY idx_memory_session_id (memory_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS prompt_template (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(128) NOT NULL,
  prompt_type VARCHAR(32) NOT NULL COMMENT 'SYSTEM/TOOL/RAG/AGENT',
  content MEDIUMTEXT NOT NULL,
  variables_json JSON DEFAULT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_prompt_owner_name (owner_user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_profile (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) DEFAULT NULL,
  system_prompt_id BIGINT UNSIGNED DEFAULT NULL,
  default_enable_rag TINYINT NOT NULL DEFAULT 0,
  default_enable_agent TINYINT NOT NULL DEFAULT 1,
  default_mcp_strategy VARCHAR(64) DEFAULT 'MANUAL',
  ext_json JSON DEFAULT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_agent_profile_owner_name (owner_user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

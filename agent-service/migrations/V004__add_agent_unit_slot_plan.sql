-- 批次槽位计划——骨架施工后创建的批次单元携带本批槽位清单。
-- slot_plan JSONB:
--   {
--     "batchLabel": "批 1：模块总览与主要流程",
--     "batchType": "FLOW" | "SYMBOL",
--     "slots": [ {slotId, slotType, title, primarySymbolKey, filePath, sortOrder} ]
--   }

ALTER TABLE agent_service.agent_units
    ADD COLUMN slot_plan JSONB;

-- SKELETON_PLAN 单元类型（骨架施工）：加入 unit_kind 检查约束
ALTER TABLE agent_service.agent_units
    DROP CONSTRAINT ck_agent_unit_kind,
    ADD CONSTRAINT ck_agent_unit_kind CHECK (
        unit_kind IN (
            'CURRENT_FILE_ANALYSIS', 'PROJECT_DISCOVERY',
            'SEMANTIC_ANALYSIS', 'SKELETON_PLAN'
        )
    );

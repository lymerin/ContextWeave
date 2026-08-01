ALTER TABLE document_change_binding_proposals
    ADD COLUMN binding_role VARCHAR(20) NOT NULL DEFAULT 'PRIMARY';
ALTER TABLE document_change_binding_proposals
    ADD COLUMN binding_ordinal INTEGER NOT NULL DEFAULT 1;
ALTER TABLE document_change_binding_proposals
    ADD CONSTRAINT ck_document_change_binding_proposal_role
        CHECK (binding_role IN ('PRIMARY', 'SUPPORTING'));
ALTER TABLE document_change_binding_proposals
    ADD CONSTRAINT ck_document_change_binding_proposal_ordinal
        CHECK (binding_ordinal >= 1);

ALTER TABLE code_document_bindings
    ADD COLUMN binding_role VARCHAR(20) NOT NULL DEFAULT 'PRIMARY';
ALTER TABLE code_document_bindings
    ADD COLUMN binding_ordinal INTEGER NOT NULL DEFAULT 1;
ALTER TABLE code_document_bindings
    ADD CONSTRAINT ck_code_document_binding_role
        CHECK (binding_role IN ('PRIMARY', 'SUPPORTING'));
ALTER TABLE code_document_bindings
    ADD CONSTRAINT ck_code_document_binding_ordinal
        CHECK (binding_ordinal >= 1);

CREATE INDEX idx_code_binding_target_role_order
    ON code_document_bindings(document_id, target_key, binding_role, binding_ordinal);

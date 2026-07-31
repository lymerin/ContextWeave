package com.devcollab.knowledgecore.document.core.infrastructure;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDocumentBlockRepository implements DocumentBlockRepository {

    private static final RowMapper<DocumentBlock> BLOCK_ROW_MAPPER =
            (rs, rowNum) -> new DocumentBlock(
                    rs.getObject("id", UUID.class),
                    rs.getObject("document_id", UUID.class),
                    DocumentBlockType.valueOf(rs.getString("type")),
                    rs.getString("text"),
                    rs.getInt("content_schema_version"),
                    rs.getString("content_json"),
                    rs.getInt("sort_order"),
                    rs.getLong("version"),
                    rs.getObject("created_by", UUID.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentBlockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DocumentBlock save(DocumentBlock block) {
        int updated = jdbcTemplate.update("""
                        UPDATE document_blocks
                           SET type = ?, text = ?, content_schema_version = ?,
                               content_json = ?, sort_order = ?,
                               version = ?, updated_at = ?
                         WHERE id = ?
                        """,
                block.type().name(), block.text(), block.contentSchemaVersion(),
                block.contentJson(), block.sortOrder(),
                block.version(), Timestamp.from(block.updatedAt()),
                block.id());

        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO document_blocks
                                (id, document_id, type, text,
                                 content_schema_version, content_json, sort_order,
                                 version, created_by, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    block.id(), block.documentId(), block.type().name(),
                    block.text(), block.contentSchemaVersion(),
                    block.contentJson(), block.sortOrder(), block.version(),
                    block.createdBy(),
                    Timestamp.from(block.createdAt()),
                    Timestamp.from(block.updatedAt()));
        }
        return block;
    }

    @Override
    @Transactional
    public List<DocumentBlock> saveAll(List<DocumentBlock> blocks) {
        blocks.forEach(this::save);
        return blocks;
    }

    @Override
    public Optional<DocumentBlock> findById(UUID blockId) {
        return jdbcTemplate.query(
                "SELECT * FROM document_blocks WHERE id = ?",
                BLOCK_ROW_MAPPER,
                blockId
        ).stream().findFirst();
    }

    @Override
    public Optional<DocumentBlock> findByIdForUpdate(UUID blockId) {
        return jdbcTemplate.query(
                "SELECT * FROM document_blocks WHERE id = ? FOR UPDATE",
                BLOCK_ROW_MAPPER,
                blockId
        ).stream().findFirst();
    }

    @Override
    public List<DocumentBlock> findAllByDocumentId(UUID documentId) {
        return jdbcTemplate.query("""
                        SELECT * FROM document_blocks
                         WHERE document_id = ?
                         ORDER BY sort_order, id
                        """,
                BLOCK_ROW_MAPPER,
                documentId
        );
    }

    @Override
    public Optional<DocumentBlock> updateContentIfVersionMatches(
            UUID blockId,
            String text,
            int contentSchemaVersion,
            String contentJson,
            java.time.Instant updatedAt,
            long expectedVersion
    ) {
        int updated = jdbcTemplate.update("""
                        UPDATE document_blocks
                           SET text = ?, content_schema_version = ?,
                               content_json = ?, version = version + 1,
                               updated_at = ?
                         WHERE id = ? AND version = ?
                        """,
                text,
                contentSchemaVersion,
                contentJson,
                Timestamp.from(updatedAt),
                blockId,
                expectedVersion);

        if (updated == 0) {
            return Optional.empty();
        }
        return findById(blockId);
    }

    @Override
    public boolean deleteIfVersionMatches(
            UUID blockId,
            long expectedVersion
    ) {
        return jdbcTemplate.update(
                "DELETE FROM document_blocks WHERE id = ? AND version = ?",
                blockId,
                expectedVersion
        ) == 1;
    }

    @Override
    public void deleteById(UUID blockId) {
        jdbcTemplate.update(
                "DELETE FROM document_blocks WHERE id = ?",
                blockId
        );
    }
}

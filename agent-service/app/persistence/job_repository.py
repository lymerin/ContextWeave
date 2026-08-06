from __future__ import annotations

import json
from collections.abc import Mapping
from datetime import datetime
from typing import Any, Protocol
from uuid import NAMESPACE_URL, UUID, uuid5

import asyncpg  # type: ignore[import-untyped]

from app.runtime.semantic_planner import PlannedSemanticUnit


def decode_json_array(value: Any) -> list[Any]:
    decoded = json.loads(value) if isinstance(value, str) else value
    return list(decoded) if isinstance(decoded, (list, tuple)) else []


class AgentJobRepository(Protocol):
    async def create_job(self, job: Mapping[str, Any], unit: Mapping[str, Any]) -> None: ...

    async def get_job(self, job_id: UUID) -> dict[str, Any] | None: ...

    async def claim_next_unit(
        self, worker_id: str, lease_seconds: int
    ) -> dict[str, Any] | None: ...

    async def heartbeat(self, unit_id: UUID, worker_id: str, lease_seconds: int) -> bool: ...

    async def update_phase(self, unit_id: UUID, worker_id: str, phase: str) -> bool: ...

    async def get_unit_context(self, unit_id: UUID) -> dict[str, Any]: ...

    async def complete_project_discovery(
        self,
        unit_id: UUID,
        worker_id: str,
        files: list[dict[str, Any]],
        units: list[PlannedSemanticUnit],
        stats: Mapping[str, Any],
        max_attempts: int,
        execution_limit: int,
    ) -> None: ...

    async def create_batch_units(
        self, job_id: UUID, batches: list[dict[str, Any]]
    ) -> None: ...

    async def list_semantic_units(
        self, job_id: UUID, offset: int, limit: int
    ) -> tuple[int, list[dict[str, Any]]]: ...

    async def complete_unit(
        self,
        unit_id: UUID,
        worker_id: str,
        result: str,
        review_request_id: UUID | None,
    ) -> None: ...

    async def fail_unit(
        self,
        unit_id: UUID,
        worker_id: str,
        error_code: str,
        error_message: str,
        retry_at: datetime | None,
    ) -> None: ...

    async def record_worker_heartbeat(self, worker_id: str) -> None: ...

    async def close(self) -> None: ...


class PostgresAgentJobRepository:
    def __init__(
        self,
        database_url: str,
        pool: asyncpg.Pool | None = None,
    ) -> None:
        self._database_url = database_url
        self._pool = pool

    @classmethod
    async def connect(cls, database_url: str) -> PostgresAgentJobRepository:
        return cls(
            database_url,
            await asyncpg.create_pool(database_url, min_size=1, max_size=10),
        )

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()

    async def _database(self) -> asyncpg.Pool:
        if self._pool is None:
            self._pool = await asyncpg.create_pool(
                self._database_url, min_size=1, max_size=10
            )
        return self._pool

    async def create_job(self, job: Mapping[str, Any], unit: Mapping[str, Any]) -> None:
        pool = await self._database()
        async with pool.acquire() as connection, connection.transaction():
            await connection.execute(
                """
                INSERT INTO agent_service.agent_jobs (
                    id, delegation_id, created_by_user_id, workspace_id, repository_id,
                    revision, scope_type, scope_payload, user_instruction, status,
                    total_units, created_at, updated_at
                ) VALUES (
                    $1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9, 'QUEUED',
                    1, $10, $10
                )
                """,
                job["id"],
                job["delegation_id"],
                job["created_by_user_id"],
                job["workspace_id"],
                job["repository_id"],
                job["revision"],
                job["scope_type"],
                json.dumps(job["scope_payload"]),
                job.get("user_instruction"),
                job["created_at"],
            )
            await connection.execute(
                """
                INSERT INTO agent_service.agent_units (
                    id, job_id, ordinal, status, max_attempts, unit_kind,
                    created_at, updated_at
                ) VALUES ($1, $2, 1, 'PENDING', $3, $4, $5, $5)
                """,
                unit["id"],
                job["id"],
                unit["max_attempts"],
                unit["unit_kind"],
                job["created_at"],
            )

    async def get_job(self, job_id: UUID) -> dict[str, Any] | None:
        pool = await self._database()
        row = await pool.fetchrow(
            """
            SELECT j.*,
                   COALESCE(j.phase, u.phase) AS current_phase,
                   COALESCE(progress.planned_unit_count, 0) AS planned_unit_count,
                   COALESCE(progress.pending_unit_count, 0) AS pending_unit_count,
                   COALESCE(progress.running_unit_count, 0) AS running_unit_count,
                   COALESCE(progress.completed_unit_count, 0) AS completed_unit_count,
                   COALESCE(progress.failed_unit_count, 0) AS failed_unit_count,
                   COALESCE(progress.no_change_unit_count, 0) AS no_change_unit_count,
                   COALESCE(progress.review_submitted_unit_count, 0)
                       AS review_submitted_unit_count,
                   COALESCE(progress.current_unit_names, '[]'::jsonb)
                       AS current_unit_names
            FROM agent_service.agent_jobs j
            LEFT JOIN agent_service.agent_units u
              ON u.job_id = j.id AND u.ordinal = 1
            LEFT JOIN LATERAL (
                SELECT
                    count(*) FILTER (
                        WHERE unit_kind = 'SEMANTIC_ANALYSIS'
                    )::int AS planned_unit_count,
                    count(*) FILTER (
                        WHERE unit_kind = 'SEMANTIC_ANALYSIS'
                          AND status IN ('PENDING', 'RETRY_WAITING')
                    )::int AS pending_unit_count,
                    count(*) FILTER (
                        WHERE unit_kind = 'SEMANTIC_ANALYSIS'
                          AND status IN ('CLAIMED', 'RUNNING')
                    )::int AS running_unit_count,
                    count(*) FILTER (
                        WHERE unit_kind = 'SEMANTIC_ANALYSIS'
                          AND status = 'COMPLETED'
                    )::int AS completed_unit_count,
                    count(*) FILTER (
                        WHERE unit_kind = 'SEMANTIC_ANALYSIS'
                          AND status = 'FAILED'
                    )::int AS failed_unit_count,
                    count(*) FILTER (
                        WHERE unit_kind = 'SEMANTIC_ANALYSIS'
                          AND result = 'NO_CHANGE'
                    )::int AS no_change_unit_count,
                    count(*) FILTER (
                        WHERE unit_kind = 'SEMANTIC_ANALYSIS'
                          AND result = 'REVIEW_SUBMITTED'
                    )::int AS review_submitted_unit_count,
                    COALESCE(
                        jsonb_agg(display_name ORDER BY ordinal)
                            FILTER (
                                WHERE unit_kind = 'SEMANTIC_ANALYSIS'
                                  AND status IN ('CLAIMED', 'RUNNING')
                            ),
                        '[]'::jsonb
                    ) AS current_unit_names
                FROM agent_service.agent_units
                WHERE job_id = j.id
            ) progress ON true
            WHERE j.id = $1
            """,
            job_id,
        )
        return self._decode_job(row) if row else None

    async def claim_next_unit(
        self, worker_id: str, lease_seconds: int
    ) -> dict[str, Any] | None:
        pool = await self._database()
        async with pool.acquire() as connection, connection.transaction():
            exhausted = await connection.fetch(
                """
                UPDATE agent_service.agent_units
                SET status = 'FAILED',
                    lease_expires_at = NULL,
                    error_code = 'WORKER_LEASE_EXPIRED',
                    error_message = 'Worker lease expired after maximum attempts',
                    completed_at = now(),
                    updated_at = now()
                WHERE status IN ('CLAIMED', 'RUNNING')
                  AND lease_expires_at <= now()
                  AND attempt >= max_attempts
                RETURNING job_id
                """
            )
            exhausted_job_ids = list({row["job_id"] for row in exhausted})
            for exhausted_job_id in exhausted_job_ids:
                await self._refresh_project_job(connection, exhausted_job_id)
            row = await connection.fetchrow(
                """
                WITH candidate AS (
                    SELECT u.id
                    FROM agent_service.agent_units u
                    JOIN agent_service.agent_jobs j ON j.id = u.job_id
                    WHERE j.status <> 'CANCELLED'
                      AND u.attempt < u.max_attempts
                      AND u.unit_kind IN (
                        'CURRENT_FILE_ANALYSIS', 'PROJECT_DISCOVERY',
                        'SEMANTIC_ANALYSIS', 'SKELETON_PLAN'
                      )
                      AND (
                        (u.status IN ('PENDING', 'RETRY_WAITING')
                          AND (u.next_attempt_at IS NULL OR u.next_attempt_at <= now()))
                        OR
                        (u.status IN ('CLAIMED', 'RUNNING')
                          AND u.lease_expires_at <= now())
                      )
                      AND (
                        u.unit_kind <> 'SEMANTIC_ANALYSIS'
                        OR NOT EXISTS (
                            SELECT 1
                            FROM agent_service.agent_unit_documents candidate_document
                            JOIN agent_service.agent_unit_documents active_document
                              ON active_document.document_id =
                                 candidate_document.document_id
                            JOIN agent_service.agent_units active_unit
                              ON active_unit.id = active_document.unit_id
                            WHERE candidate_document.unit_id = u.id
                              AND active_unit.id <> u.id
                              AND active_unit.status IN ('CLAIMED', 'RUNNING')
                        )
                      )
                    ORDER BY COALESCE(u.next_attempt_at, u.created_at), u.created_at
                    FOR UPDATE OF u SKIP LOCKED
                    LIMIT 1
                )
                UPDATE agent_service.agent_units u
                SET status = 'CLAIMED',
                    phase = CASE
                        WHEN u.unit_kind = 'PROJECT_DISCOVERY'
                            THEN 'DISCOVERING_FILES'
                        WHEN u.unit_kind = 'SEMANTIC_ANALYSIS'
                            THEN 'LOADING_CONTEXT'
                        ELSE 'LOADING_CONTEXT'
                    END,
                    worker_id = $1,
                    attempt = u.attempt + 1,
                    lease_expires_at = now() + ($2 * interval '1 second'),
                    heartbeat_at = now(),
                    next_attempt_at = NULL,
                    started_at = COALESCE(u.started_at, now()),
                    updated_at = now(),
                    error_code = NULL,
                    error_message = NULL
                FROM candidate
                WHERE u.id = candidate.id
                RETURNING u.*
                """,
                worker_id,
                lease_seconds,
            )
            if row is None:
                return None
            await connection.execute(
                """
                UPDATE agent_service.agent_jobs
                SET status = 'RUNNING',
                    started_at = COALESCE(started_at, now()),
                    updated_at = now(),
                    version = version + 1
                WHERE id = $1 AND status <> 'CANCELLED'
                """,
                row["job_id"],
            )
            job = await connection.fetchrow(
                "SELECT * FROM agent_service.agent_jobs WHERE id = $1",
                row["job_id"],
            )
            return {**dict(row), "job": self._decode_job(job)}

    async def get_unit_context(self, unit_id: UUID) -> dict[str, Any]:
        pool = await self._database()
        unit = await pool.fetchrow(
            "SELECT * FROM agent_service.agent_units WHERE id = $1",
            unit_id,
        )
        if unit is None:
            raise KeyError("Agent unit not found")
        files = await pool.fetch(
            """
            SELECT file_path, role, relevance_reason, ordinal
            FROM agent_service.agent_unit_files
            WHERE unit_id = $1
            ORDER BY ordinal, file_path
            """,
            unit_id,
        )
        documents = await pool.fetch(
            """
            SELECT document_id, relationship, source, ordinal
            FROM agent_service.agent_unit_documents
            WHERE unit_id = $1
            ORDER BY ordinal, document_id
            """,
            unit_id,
        )
        unit_dict = dict(unit)
        if isinstance(unit_dict.get("slot_plan"), str):
            unit_dict["slot_plan"] = json.loads(unit_dict["slot_plan"])
        return {
            **unit_dict,
            "files": [dict(row) for row in files],
            "documents": [dict(row) for row in documents],
        }

    @staticmethod
    def _decode_job(row: asyncpg.Record) -> dict[str, Any]:
        job = dict(row)
        for field, fallback in (
            ("scope_payload", {}),
            ("review_request_ids", []),
            ("skipped_reason_counts", {}),
            ("current_unit_names", []),
        ):
            value = job.get(field)
            if isinstance(value, str):
                job[field] = json.loads(value)
            elif value is None:
                job[field] = fallback
        return job

    async def heartbeat(self, unit_id: UUID, worker_id: str, lease_seconds: int) -> bool:
        pool = await self._database()
        result = await pool.execute(
            """
            UPDATE agent_service.agent_units
            SET heartbeat_at = now(),
                lease_expires_at = now() + ($3 * interval '1 second'),
                updated_at = now(),
                status = CASE WHEN status = 'CLAIMED' THEN 'RUNNING' ELSE status END
            WHERE id = $1 AND worker_id = $2 AND status IN ('CLAIMED', 'RUNNING')
            """,
            unit_id,
            worker_id,
            lease_seconds,
        )
        return bool(result.endswith(" 1"))

    async def update_phase(self, unit_id: UUID, worker_id: str, phase: str) -> bool:
        pool = await self._database()
        async with pool.acquire() as connection, connection.transaction():
            row = await connection.fetchrow(
                """
                UPDATE agent_service.agent_units
                SET phase = $3, status = 'RUNNING', updated_at = now()
                WHERE id = $1 AND worker_id = $2
                  AND status IN ('CLAIMED', 'RUNNING')
                RETURNING job_id
                """,
                unit_id,
                worker_id,
                phase,
            )
            if row is None:
                return False
            await connection.execute(
                """
                UPDATE agent_service.agent_jobs
                SET phase = $2::varchar,
                    planner_status = CASE
                        WHEN $2::varchar = 'PLANNING_UNITS' THEN 'RUNNING'
                        WHEN $2::varchar = 'VALIDATING_UNIT_PLAN' THEN 'VALIDATING'
                        ELSE planner_status
                    END,
                    updated_at = now(), version = version + 1
                WHERE id = $1
                """,
                row["job_id"],
                phase,
            )
            return True

    async def complete_project_discovery(
        self,
        unit_id: UUID,
        worker_id: str,
        files: list[dict[str, Any]],
        units: list[PlannedSemanticUnit],
        stats: Mapping[str, Any],
        max_attempts: int,
        execution_limit: int,
    ) -> None:
        pool = await self._database()
        async with pool.acquire() as connection, connection.transaction():
            discovery = await connection.fetchrow(
                """
                SELECT job_id
                FROM agent_service.agent_units
                WHERE id = $1 AND worker_id = $2
                  AND unit_kind = 'PROJECT_DISCOVERY'
                  AND status IN ('CLAIMED', 'RUNNING')
                FOR UPDATE
                """,
                unit_id,
                worker_id,
            )
            if discovery is None:
                raise RuntimeError("Agent unit lease was lost")
            job_id = discovery["job_id"]
            await connection.execute(
                """
                DELETE FROM agent_service.agent_units
                WHERE job_id = $1
                  AND unit_kind = 'SEMANTIC_ANALYSIS'
                  AND status IN ('PENDING', 'READY_FOR_ANALYSIS')
                """,
                job_id,
            )
            if files:
                await connection.executemany(
                    """
                    INSERT INTO agent_service.agent_job_files (
                        id, job_id, repository_id, revision, file_path, file_name,
                        extension, language, size_bytes, classification, package_name,
                        module_key, layer_hint, role_hints, import_keys,
                        exported_symbols, top_level_symbols, route_hints, is_generated,
                        metadata_error
                    ) VALUES (
                        $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12,
                        $13, $14::jsonb, $15::jsonb, $16::jsonb, $17::jsonb,
                        $18::jsonb, $19, $20
                    )
                    """,
                    [
                        (
                            row["id"], job_id, row["repository_id"], row["revision"],
                            row["file_path"], row["file_name"], row["extension"],
                            row["language"], row["size_bytes"], row["classification"],
                            row["package_name"], row["module_key"], row["layer_hint"],
                            json.dumps(row["role_hints"]),
                            json.dumps(row["import_keys"]),
                            json.dumps(row["exported_symbols"]),
                            json.dumps(row["top_level_symbols"]),
                            json.dumps(row["route_hints"]),
                            row["is_generated"], row["metadata_error"],
                        )
                        for row in files
                    ],
                )
            for execution_index, unit in enumerate(units, 1):
                ordinal = execution_index + 1
                initial_status = (
                    "PENDING"
                    if execution_limit == 0 or execution_index <= execution_limit
                    else "READY_FOR_ANALYSIS"
                )
                await connection.execute(
                    """
                    INSERT INTO agent_service.agent_units (
                        id, job_id, ordinal, status, phase, attempt, max_attempts,
                        unit_kind, semantic_key, display_name, semantic_kind,
                        primary_directory, language_set, estimated_size_bytes,
                        grouping_reasons, unit_fingerprint, summary, created_at, updated_at
                    ) VALUES (
                        $1, $2, $3, $14::varchar, 'EXECUTING_UNITS',
                        0, $12, 'SKELETON_PLAN', $4, $5, $6, $7,
                        $8::jsonb, $9, $10::jsonb, $11, $13, now(), now()
                    )
                    """,
                    unit.id, job_id, ordinal, unit.semantic_key, unit.display_name,
                    unit.semantic_kind, unit.primary_directory,
                    json.dumps(unit.language_set), unit.estimated_size_bytes,
                    json.dumps(unit.grouping_reasons), unit.unit_fingerprint,
                    max_attempts,
                    unit.summary,
                    initial_status,
                )
                if unit.files:
                    await connection.executemany(
                        """
                        INSERT INTO agent_service.agent_unit_files (
                            unit_id, job_file_id, file_path, role,
                            relevance_reason, ordinal
                        ) VALUES ($1, $2, $3, $4, $5, $6)
                        """,
                        [
                            (
                                unit.id, item.job_file_id, item.file_path, item.role,
                                item.relevance_reason, item.ordinal,
                            )
                            for item in unit.files
                        ],
                    )
                if unit.documents:
                    await connection.executemany(
                        """
                        INSERT INTO agent_service.agent_unit_documents (
                            unit_id, document_id, relationship, source, ordinal
                        ) VALUES ($1, $2, $3, $4, $5)
                        """,
                        [
                            (
                                unit.id, item.document_id, item.relationship,
                                item.source, item.ordinal,
                            )
                            for item in unit.documents
                        ],
                    )
            await connection.execute(
                """
                UPDATE agent_service.agent_units
                SET status = 'COMPLETED', phase = 'COMPLETED',
                    result = NULL, lease_expires_at = NULL,
                    completed_at = now(), updated_at = now()
                WHERE id = $1
                """,
                unit_id,
            )
            await connection.execute(
                """
                UPDATE agent_service.agent_jobs
                SET status = 'RUNNING',
                    phase = 'EXECUTING_UNITS',
                    planner_status = 'COMPLETED',
                    total_units = $2,
                    completed_units = 0,
                    failed_units = 0,
                    review_request_ids = '[]'::jsonb,
                    discovered_file_count = $3,
                    supported_code_count = $4,
                    skipped_file_count = $5,
                    skipped_reason_counts = $6::jsonb,
                    metadata_parsed_count = $7,
                    metadata_failed_count = $8,
                    bound_file_count = $9,
                    unbound_file_count = $10,
                    analysis_unit_count = $11,
                    overlapping_file_count = $12,
                    completed_at = NULL,
                    updated_at = now(),
                    version = version + 1
                WHERE id = $1
                """,
                job_id,
                len(units),
                stats["discovered_file_count"],
                stats["supported_code_count"],
                stats["skipped_file_count"],
                json.dumps(stats["skipped_reason_counts"]),
                stats["metadata_parsed_count"],
                stats["metadata_failed_count"],
                stats["bound_file_count"],
                stats["unbound_file_count"],
                stats["analysis_unit_count"],
                stats["overlapping_file_count"],
            )

    async def create_batch_units(
        self, job_id: UUID, batches: list[dict[str, Any]]
    ) -> None:
        """骨架施工后创建批次单元（SEMANTIC_ANALYSIS + slot_plan）。

        批次单元 ID 由 (job_id, batch_index) 确定性生成——骨架单元重试时
        重新创建批次不产生重复单元。ordinal 接续任务现有最大序号。
        """
        if not batches:
            return
        pool = await self._database()
        async with pool.acquire() as connection, connection.transaction():
            max_ordinal = await connection.fetchval(
                """
                SELECT COALESCE(max(ordinal), 0)
                FROM agent_service.agent_units WHERE job_id = $1
                """,
                job_id,
            )
            # max_attempts 在 agent_units 上（每单元独立）；取任务任一单元的值
            max_attempts_row = await connection.fetchrow(
                """
                SELECT max_attempts
                FROM agent_service.agent_units WHERE job_id = $1 LIMIT 1
                """,
                job_id,
            )
            if max_attempts_row is None:
                raise KeyError("Agent job not found")
            max_attempts = int(max_attempts_row["max_attempts"])
            for batch in batches:
                batch_index = int(batch.get("batch_index", 0))
                max_ordinal += 1
                unit_id = uuid5(
                    NAMESPACE_URL, f"devcollab:{job_id}:batch:{batch_index}"
                )
                await connection.execute(
                    """
                    INSERT INTO agent_service.agent_units (
                        id, job_id, ordinal, status, phase, attempt, max_attempts,
                        unit_kind, display_name, summary, slot_plan, created_at, updated_at
                    ) VALUES (
                        $1, $2, $3, 'PENDING', 'LOADING_CONTEXT',
                        0, $4, 'SEMANTIC_ANALYSIS', $5, $6, $7::jsonb, now(), now()
                    )
                    ON CONFLICT (id) DO NOTHING
                    """,
                    unit_id,
                    job_id,
                    max_ordinal,
                    max_attempts,
                    batch.get("display_name", batch.get("batch_label", "批次")),
                    batch.get("summary", ""),
                    json.dumps(batch.get("slot_plan", {})),
                )
                file_paths = batch.get("file_paths", [])
                if file_paths:
                    rows = await connection.fetch(
                        """
                        SELECT id, file_path FROM agent_service.agent_job_files
                        WHERE job_id = $1 AND file_path = ANY($2::text[])
                        """,
                        job_id,
                        file_paths,
                    )
                    by_path = {row["file_path"]: row["id"] for row in rows}
                    await connection.executemany(
                        """
                        INSERT INTO agent_service.agent_unit_files (
                            unit_id, job_file_id, file_path, role,
                            relevance_reason, ordinal
                        ) VALUES ($1, $2, $3, 'PRIMARY', 'BATCH_SLOT', $4)
                        ON CONFLICT (unit_id, job_file_id, role) DO NOTHING
                        """,
                        [
                            (unit_id, by_path[path], path, index + 1)
                            for index, path in enumerate(file_paths)
                            if path in by_path
                        ],
                    )

    async def list_semantic_units(
        self, job_id: UUID, offset: int, limit: int
    ) -> tuple[int, list[dict[str, Any]]]:
        pool = await self._database()
        total = await pool.fetchval(
            """
            SELECT count(*)
            FROM agent_service.agent_units
            WHERE job_id = $1 AND unit_kind = 'SEMANTIC_ANALYSIS'
            """,
            job_id,
        )
        rows = await pool.fetch(
            """
            SELECT u.*,
                   COALESCE(
                     jsonb_agg(DISTINCT jsonb_build_object(
                       'filePath', uf.file_path,
                       'role', uf.role,
                       'relevanceReason', uf.relevance_reason,
                       'ordinal', uf.ordinal
                     )) FILTER (WHERE uf.unit_id IS NOT NULL),
                     '[]'::jsonb
                   ) AS files,
                   COALESCE(
                     jsonb_agg(DISTINCT jsonb_build_object(
                       'documentId', ud.document_id,
                       'relationship', ud.relationship,
                       'source', ud.source,
                       'ordinal', ud.ordinal
                     )) FILTER (WHERE ud.unit_id IS NOT NULL),
                     '[]'::jsonb
                   ) AS documents
            FROM agent_service.agent_units u
            LEFT JOIN agent_service.agent_unit_files uf ON uf.unit_id = u.id
            LEFT JOIN agent_service.agent_unit_documents ud ON ud.unit_id = u.id
            WHERE u.job_id = $1 AND u.unit_kind = 'SEMANTIC_ANALYSIS'
            GROUP BY u.id
            ORDER BY u.semantic_kind, u.primary_directory, u.semantic_key
            OFFSET $2 LIMIT $3
            """,
            job_id,
            offset,
            limit,
        )
        result: list[dict[str, Any]] = []
        for row in rows:
            item = dict(row)
            for field in ("language_set", "grouping_reasons", "files", "documents"):
                if isinstance(item.get(field), str):
                    item[field] = json.loads(item[field])
            result.append(item)
        return int(total or 0), result

    async def complete_unit(
        self,
        unit_id: UUID,
        worker_id: str,
        result: str,
        review_request_id: UUID | None,
    ) -> None:
        pool = await self._database()
        async with pool.acquire() as connection, connection.transaction():
            row = await connection.fetchrow(
                """
                UPDATE agent_service.agent_units
                SET status = 'COMPLETED', result = $3, review_request_id = $4,
                    lease_expires_at = NULL, completed_at = now(), updated_at = now()
                WHERE id = $1 AND worker_id = $2 AND status IN ('CLAIMED', 'RUNNING')
                RETURNING job_id, unit_kind
                """,
                unit_id,
                worker_id,
                result,
                review_request_id,
            )
            if row is None:
                raise RuntimeError("Agent unit lease was lost")
            if row["unit_kind"] in ("SEMANTIC_ANALYSIS", "SKELETON_PLAN"):
                await self._refresh_project_job(connection, row["job_id"])
                return
            review_ids = [] if review_request_id is None else [str(review_request_id)]
            await connection.execute(
                """
                UPDATE agent_service.agent_jobs
                SET status = 'COMPLETED', result = $2, completed_units = 1,
                    failed_units = 0, review_request_ids = $3::jsonb,
                    completed_at = now(), updated_at = now(), version = version + 1
                WHERE id = $1
                """,
                row["job_id"],
                result,
                json.dumps(review_ids),
            )

    async def fail_unit(
        self,
        unit_id: UUID,
        worker_id: str,
        error_code: str,
        error_message: str,
        retry_at: datetime | None,
    ) -> None:
        pool = await self._database()
        async with pool.acquire() as connection, connection.transaction():
            status = "RETRY_WAITING" if retry_at else "FAILED"
            row = await connection.fetchrow(
                """
                UPDATE agent_service.agent_units
                SET status = $3::varchar, next_attempt_at = $4, lease_expires_at = NULL,
                    error_code = $5, error_message = $6, updated_at = now(),
                    completed_at = CASE
                        WHEN $3::varchar = 'FAILED' THEN now()
                        ELSE NULL
                    END
                WHERE id = $1 AND worker_id = $2
                RETURNING job_id, unit_kind
                """,
                unit_id,
                worker_id,
                status,
                retry_at,
                error_code,
                error_message[:500],
            )
            if row is None:
                return
            if row["unit_kind"] in ("SEMANTIC_ANALYSIS", "SKELETON_PLAN"):
                await self._refresh_project_job(connection, row["job_id"])
                return
            if retry_at is None:
                await connection.execute(
                    """
                    UPDATE agent_service.agent_jobs
                    SET status = 'FAILED', failed_units = 1, error_code = $2,
                        error_message = $3,
                        planner_status = CASE
                            WHEN $4 = 'PROJECT_DISCOVERY' THEN 'FAILED'
                            ELSE planner_status
                        END,
                        completed_at = now(), updated_at = now(),
                        version = version + 1
                    WHERE id = $1
                    """,
                    row["job_id"],
                    error_code,
                    error_message[:500],
                    row["unit_kind"],
                )
            else:
                await connection.execute(
                    """
                    UPDATE agent_service.agent_jobs
                    SET status = 'QUEUED', error_code = $2, error_message = $3,
                        updated_at = now(), version = version + 1
                    WHERE id = $1
                    """,
                    row["job_id"],
                    error_code,
                    error_message[:500],
                )

    async def _refresh_project_job(
        self,
        connection: asyncpg.Connection,
        job_id: UUID,
    ) -> None:
        progress = await connection.fetchrow(
            """
            SELECT
                count(*)::int AS total,
                count(*) FILTER (
                    WHERE status <> 'READY_FOR_ANALYSIS'
                )::int AS executable,
                count(*) FILTER (
                    WHERE status IN (
                        'PENDING', 'CLAIMED', 'RUNNING', 'RETRY_WAITING'
                    )
                )::int AS active,
                count(*) FILTER (WHERE status = 'COMPLETED')::int AS completed,
                count(*) FILTER (WHERE status = 'FAILED')::int AS failed,
                count(*) FILTER (WHERE result = 'NO_CHANGE')::int AS no_change,
                count(*) FILTER (WHERE result = 'REVIEW_SUBMITTED')::int AS reviewed,
                COALESCE(
                    jsonb_agg(review_request_id ORDER BY ordinal)
                        FILTER (WHERE review_request_id IS NOT NULL),
                    '[]'::jsonb
                ) AS review_ids
            FROM agent_service.agent_units
            WHERE job_id = $1
              AND unit_kind IN ('SEMANTIC_ANALYSIS', 'SKELETON_PLAN')
            """,
            job_id,
        )
        if progress is None or int(progress["total"]) == 0:
            return
        total = int(progress["total"])
        executable = int(progress["executable"])
        active = int(progress["active"])
        completed = int(progress["completed"])
        failed = int(progress["failed"])
        reviewed = int(progress["reviewed"])
        review_ids = decode_json_array(progress["review_ids"])
        if active > 0:
            status = "RUNNING"
            phase = "EXECUTING_UNITS"
            result = None
            completed_at = None
        elif executable > 0 and completed == executable:
            status = "COMPLETED"
            phase = "COMPLETED"
            result = "REVIEW_SUBMITTED" if reviewed else "NO_CHANGE"
            completed_at = datetime.now().astimezone()
        elif executable > 0 and completed > 0 and completed + failed == executable:
            status = "PARTIALLY_COMPLETED"
            phase = "COMPLETED"
            result = "PARTIALLY_COMPLETED"
            completed_at = datetime.now().astimezone()
        else:
            status = "FAILED"
            phase = "COMPLETED"
            result = None
            completed_at = datetime.now().astimezone()
        await connection.execute(
            """
            UPDATE agent_service.agent_jobs
            SET status = $2::varchar,
                phase = $3::varchar,
                result = $4::varchar,
                total_units = $5,
                completed_units = $6,
                failed_units = $7,
                review_request_ids = $8::jsonb,
                error_code = CASE
                    WHEN $2::varchar = 'FAILED' THEN error_code ELSE NULL
                END,
                error_message = CASE
                    WHEN $2::varchar = 'FAILED' THEN error_message ELSE NULL
                END,
                completed_at = $9,
                updated_at = now(),
                version = version + 1
            WHERE id = $1 AND status <> 'CANCELLED'
            """,
            job_id,
            status,
            phase,
            result,
            total,
            completed,
            failed,
            json.dumps([str(value) for value in review_ids]),
            completed_at,
        )
    async def record_worker_heartbeat(self, worker_id: str) -> None:
        pool = await self._database()
        await pool.execute(
            """
            INSERT INTO agent_service.worker_heartbeats(worker_id, heartbeat_at, started_at)
            VALUES ($1, now(), now())
            ON CONFLICT (worker_id) DO UPDATE SET heartbeat_at = excluded.heartbeat_at
            """,
            worker_id,
        )

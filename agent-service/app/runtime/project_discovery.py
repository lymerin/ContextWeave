from __future__ import annotations

import json
from collections import Counter
from collections.abc import Awaitable, Callable
from typing import Any
from uuid import NAMESPACE_URL, UUID, uuid5

from app.clients.mcp_client import McpClientError, ReadOnlyMcpClient
from app.config import Settings
from app.runtime.file_classification import ClassifiedFile, classify_file
from app.execution.errors import JobExecutionError
from app.runtime.semantic_planner import ProjectFile

PhaseCallback = Callable[[str], Awaitable[None]]


class ProjectDiscoveryService:
    def __init__(
        self,
        client: ReadOnlyMcpClient,
        settings: Settings,
        on_phase: PhaseCallback,
    ) -> None:
        self._client = client
        self._settings = settings
        self._on_phase = on_phase

    async def execute(
        self,
        *,
        job_id: UUID,
        workspace_id: UUID,
        repository_id: UUID,
        revision: str,
    ) -> tuple[
        list[dict[str, Any]],
        list[ProjectFile],
        list[dict[str, Any]],
        dict[str, Any],
        dict[str, Any],
    ]:
        await self._on_phase("DISCOVERING_FILES")
        discovered = await self._discover(
            str(workspace_id), str(repository_id), revision
        )
        await self._on_phase("CLASSIFYING_FILES")
        classified = [
            classify_file(
                item,
                max_size_bytes=self._settings.agent_max_single_file_bytes,
            )
            for item in discovered
        ]
        eligible = [item for item in classified if item.eligible]
        skipped = [item for item in classified if not item.eligible]

        await self._on_phase("LOADING_CODE_METADATA")
        metadata_by_path, metadata_failures = await self._load_metadata(
            str(workspace_id),
            str(repository_id),
            revision,
            [item.file_path for item in eligible],
        )
        project_files = [
            self._project_file(job_id, item, metadata_by_path.get(item.file_path, {}))
            for item in eligible
        ]

        await self._on_phase("LOADING_BINDINGS")
        bindings = await self._load_bindings(
            str(workspace_id),
            str(repository_id),
            [item.file_path for item in eligible],
        )
        all_rows = [
            self._file_row(
                job_id, repository_id, revision, item,
                metadata_by_path.get(item.file_path, {}),
            )
            for item in classified
        ]
        bound_file_count = sum(bool(bindings.get(item.file_path)) for item in eligible)
        documents: dict[str, dict[str, str]] = {}
        binding_rows: list[dict[str, Any]] = []
        for file_path in sorted(bindings):
            for binding in bindings[file_path]:
                document_id = str(binding.get("documentId", ""))
                if document_id:
                    documents[document_id] = {
                        "documentId": document_id,
                        "title": str(binding.get("documentTitle") or ""),
                    }
                binding_rows.append(
                    {
                        "filePath": file_path,
                        "bindingId": binding.get("bindingId"),
                        "documentId": binding.get("documentId"),
                        "documentTitle": binding.get("documentTitle"),
                        "blockId": binding.get("blockId"),
                        "pathPattern": binding.get("pathPattern"),
                    }
                )
        project_index = {
            "repositoryId": str(repository_id),
            "revision": revision,
            "topLevelModules": sorted(
                {
                    (item.file_path.split("/", 1)[0] if "/" in item.file_path else ".")
                    for item in eligible
                }
            ),
            "files": [
                {
                    "filePath": item.file_path,
                    "language": item.language,
                    "sizeBytes": item.size_bytes,
                    "packageName": item.package_name,
                    "moduleKey": item.module_key,
                    "layerHint": item.layer_hint,
                    "roleHints": list(item.role_hints[:8]),
                    "imports": list(item.import_keys[:12]),
                    "exportedSymbols": list(item.exported_symbols[:8]),
                    "topLevelSymbols": list(item.top_level_symbols[:8]),
                    "routeHints": _string_list(
                        metadata_by_path.get(item.file_path, {}).get("routeHints")
                    ),
                    "eligible": True,
                }
                for item in project_files
            ],
            "bindings": binding_rows,
            "documents": [documents[key] for key in sorted(documents)],
        }
        stats = {
            "discovered_file_count": len(discovered),
            "supported_code_count": len(eligible),
            "skipped_file_count": len(skipped),
            "skipped_reason_counts": dict(
                sorted(Counter(item.classification for item in skipped).items())
            ),
            "metadata_parsed_count": len(eligible) - metadata_failures,
            "metadata_failed_count": metadata_failures,
            "bound_file_count": bound_file_count,
            "unbound_file_count": len(eligible) - bound_file_count,
            "analysis_unit_count": 0,
            "overlapping_file_count": 0,
        }
        planner_batches = _partition_project_index(
            project_index, self._settings.agent_model_max_input_characters
        )
        return all_rows, project_files, planner_batches, project_index, stats

    async def _discover(
        self,
        workspace_id: str,
        repository_id: str,
        revision: str,
    ) -> list[dict[str, Any]]:
        cursor: str | None = None
        seen_cursors: set[str] = set()
        unique: dict[str, dict[str, Any]] = {}
        for _ in range(self._settings.agent_max_discovery_pages):
            arguments: dict[str, Any] = {
                "workspaceId": workspace_id,
                "repositoryId": repository_id,
                "revision": revision,
                "pathPrefix": "",
                "recursive": True,
                "limit": self._settings.agent_project_list_page_size,
            }
            if cursor:
                arguments["cursor"] = cursor
            response = await self._client.call_tool(
                "devcollab.repository.list_files", arguments, "delegated"
            )
            actual_revision_value = response.get("revision")
            if not isinstance(actual_revision_value, str) or not actual_revision_value:
                raise JobExecutionError(
                    "REPOSITORY_SCAN_FAILED",
                    "Repository file page did not declare its revision",
                )
            actual_revision = actual_revision_value
            if actual_revision.lower() != revision.lower():
                raise JobExecutionError(
                    "REVISION_CHANGED",
                    "Repository revision changed after the Agent job was created",
                )
            for item in response.get("files", []):
                path = str(item.get("filePath", "")).replace("\\", "/")
                if path:
                    unique[path] = dict(item)
            if len(unique) > self._settings.agent_project_max_files:
                raise JobExecutionError(
                    "DISCOVERY_LIMIT_EXCEEDED",
                    "Repository exceeds the configured file limit",
                )
            if not response.get("hasMore"):
                return [unique[path] for path in sorted(unique)]
            next_cursor = response.get("nextCursor")
            if (
                not isinstance(next_cursor, str)
                or not next_cursor
                or next_cursor in seen_cursors
            ):
                raise JobExecutionError(
                    "REPOSITORY_SCAN_FAILED",
                    "Repository pagination cursor did not advance",
                )
            seen_cursors.add(next_cursor)
            cursor = next_cursor
        raise JobExecutionError(
            "DISCOVERY_LIMIT_EXCEEDED",
            "Repository discovery page limit exceeded",
        )

    async def _load_metadata(
        self,
        workspace_id: str,
        repository_id: str,
        revision: str,
        paths: list[str],
    ) -> tuple[dict[str, dict[str, Any]], int]:
        result: dict[str, dict[str, Any]] = {}
        failures = 0
        size = self._settings.agent_metadata_batch_size
        for index in range(0, len(paths), size):
            chunk = paths[index:index + size]
            response = await self._client.call_tool(
                "devcollab.repository.inspect_code_metadata",
                {
                    "workspaceId": workspace_id,
                    "repositoryId": repository_id,
                    "revision": revision,
                    "filePaths": chunk,
                },
                "delegated",
            )
            actual_revision = response.get("revision")
            if (
                not isinstance(actual_revision, str)
                or actual_revision.lower() != revision.lower()
            ):
                raise JobExecutionError(
                    "REVISION_CHANGED",
                    "Code metadata revision does not match the Agent job revision",
                )
            returned_paths: set[str] = set()
            for item in response.get("files", []):
                path = str(item.get("filePath", ""))
                if path not in chunk or path in returned_paths:
                    continue
                returned_paths.add(path)
                result[path] = dict(item)
                if item.get("parseStatus") != "PARSED":
                    failures += 1
            for missing_path in set(chunk) - returned_paths:
                result[missing_path] = {
                    "filePath": missing_path,
                    "parseStatus": "FAILED",
                    "errorCode": "METADATA_MISSING",
                }
                failures += 1
        return result, failures

    async def _load_bindings(
        self,
        workspace_id: str,
        repository_id: str,
        paths: list[str],
    ) -> dict[str, list[dict[str, Any]]]:
        result: dict[str, list[dict[str, Any]]] = {path: [] for path in paths}
        try:
            size = self._settings.agent_binding_batch_size
            for index in range(0, len(paths), size):
                chunk = paths[index:index + size]
                response = await self._client.call_tool(
                    "devcollab.binding.list_batch",
                    {
                        "workspaceId": workspace_id,
                        "repositoryId": repository_id,
                        "filePaths": chunk,
                    },
                    "delegated",
                )
                for group in response.get("files", []):
                    result[str(group["filePath"])] = list(group.get("bindings", []))
        except McpClientError as exc:
            raise JobExecutionError("BINDING_BATCH_FAILED", str(exc)[:300]) from exc
        return result

    @staticmethod
    def _project_file(
        job_id: UUID,
        item: ClassifiedFile,
        metadata: dict[str, Any],
    ) -> ProjectFile:
        return ProjectFile(
            id=uuid5(NAMESPACE_URL, f"{job_id}:{item.file_path}"),
            file_path=item.file_path,
            language=item.language or "Unknown",
            size_bytes=item.size_bytes,
            package_name=_optional_text(metadata.get("packageName")),
            module_key=_optional_text(metadata.get("moduleKey")),
            layer_hint=_optional_text(metadata.get("layerHint")),
            role_hints=tuple(_string_list(metadata.get("roleHints"))),
            import_keys=tuple(_string_list(metadata.get("imports"))),
            exported_symbols=tuple(_string_list(metadata.get("exportedSymbols"))),
            top_level_symbols=tuple(_string_list(metadata.get("topLevelSymbols"))),
        )

    @staticmethod
    def _file_row(
        job_id: UUID,
        repository_id: UUID,
        revision: str,
        item: ClassifiedFile,
        metadata: dict[str, Any],
    ) -> dict[str, Any]:
        return {
            "id": uuid5(NAMESPACE_URL, f"{job_id}:{item.file_path}"),
            "job_id": job_id,
            "repository_id": repository_id,
            "revision": revision,
            "file_path": item.file_path,
            "file_name": item.file_name,
            "extension": item.extension,
            "language": item.language,
            "size_bytes": item.size_bytes,
            "classification": item.classification,
            "package_name": _optional_text(metadata.get("packageName")),
            "module_key": _optional_text(metadata.get("moduleKey")),
            "layer_hint": _optional_text(metadata.get("layerHint")),
            "role_hints": _string_list(metadata.get("roleHints")),
            "import_keys": _string_list(metadata.get("imports")),
            "exported_symbols": _string_list(metadata.get("exportedSymbols")),
            "top_level_symbols": _string_list(metadata.get("topLevelSymbols")),
            "route_hints": _string_list(metadata.get("routeHints")),
            "is_generated": item.is_generated,
            "metadata_error": _optional_text(metadata.get("errorCode")),
        }


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value if isinstance(item, str)]


def _optional_text(value: object) -> str | None:
    return str(value) if isinstance(value, str) and value else None


def _partition_project_index(
    project_index: dict[str, Any],
    max_characters: int,
) -> list[dict[str, Any]]:
    if len(json.dumps(project_index, ensure_ascii=False)) <= max_characters:
        return [project_index]

    files_by_module: dict[str, list[dict[str, Any]]] = {}
    for item in project_index["files"]:
        path = str(item["filePath"])
        module = path.split("/", 1)[0] if "/" in path else "."
        files_by_module.setdefault(module, []).append(_compact_file(item))

    batches: list[dict[str, Any]] = []
    for module in sorted(files_by_module):
        current: list[dict[str, Any]] = []
        for item in files_by_module[module]:
            candidate = _project_index_batch(
                project_index, current + [item], len(batches)
            )
            if (
                current
                and len(json.dumps(candidate, ensure_ascii=False)) > max_characters
            ):
                batches.append(
                    _project_index_batch(project_index, current, len(batches))
                )
                current = [item]
                candidate = _project_index_batch(
                    project_index, current, len(batches)
                )
            else:
                current.append(item)
            if len(json.dumps(candidate, ensure_ascii=False)) > max_characters:
                raise JobExecutionError(
                    "PROJECT_INDEX_LIMIT_EXCEEDED",
                    f"ProjectIndex batch for {module} exceeds the model input budget",
                )
        if current:
            batches.append(_project_index_batch(project_index, current, len(batches)))
    for index, batch in enumerate(batches):
        batch["batch"] = {
            "index": index,
            "count": len(batches),
            "strategy": "TOP_LEVEL_MODULE_THEN_CAPACITY",
        }
    return batches


def _compact_file(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "filePath": item["filePath"],
        "language": item.get("language"),
        "packageName": item.get("packageName"),
        "moduleKey": item.get("moduleKey"),
        "layerHint": item.get("layerHint"),
        "roleHints": list(item.get("roleHints", []))[:4],
        "imports": list(item.get("imports", []))[:4],
        "exportedSymbols": list(item.get("exportedSymbols", []))[:3],
        "topLevelSymbols": list(item.get("topLevelSymbols", []))[:3],
        "routeHints": list(item.get("routeHints", []))[:3],
        "eligible": True,
    }


def _project_index_batch(
    project_index: dict[str, Any],
    files: list[dict[str, Any]],
    batch_index: int,
) -> dict[str, Any]:
    paths = {str(item["filePath"]) for item in files}
    bindings = [
        item for item in project_index["bindings"]
        if str(item.get("filePath")) in paths
    ]
    document_ids = {
        str(item.get("documentId")) for item in bindings if item.get("documentId")
    }
    return {
        "repositoryId": project_index["repositoryId"],
        "revision": project_index["revision"],
        "topLevelModules": sorted(
            {
                (
                    str(item["filePath"]).split("/", 1)[0]
                    if "/" in str(item["filePath"])
                    else "."
                )
                for item in files
            }
        ),
        "files": files,
        "bindings": bindings,
        "documents": [
            item for item in project_index["documents"]
            if str(item.get("documentId")) in document_ids
        ],
        "compression": {
            "applied": True,
            "strategy": "KEEP_ALL_BATCH_PATHS_LIMIT_RELATION_HINTS",
            "batchIndex": batch_index,
        },
    }

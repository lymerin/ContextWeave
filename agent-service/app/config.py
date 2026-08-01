from enum import StrEnum
from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class DeepSeekModelName(StrEnum):
    """Allowed DeepSeek model identifiers."""

    FLASH = "deepseek-v4-flash"
    PRO = "deepseek-v4-pro"


_MODEL_ALIASES: dict[str, DeepSeekModelName] = {
    "deepseek-v4-flash": DeepSeekModelName.FLASH,
    "deepseekflash": DeepSeekModelName.FLASH,
    "deepseek-flash": DeepSeekModelName.FLASH,
    "deepseek_flash": DeepSeekModelName.FLASH,
    "flash": DeepSeekModelName.FLASH,
    "deepseek-v4-pro": DeepSeekModelName.PRO,
    "deepseekpro": DeepSeekModelName.PRO,
    "deepseek-pro": DeepSeekModelName.PRO,
    "deepseek_pro": DeepSeekModelName.PRO,
    "pro": DeepSeekModelName.PRO,
}


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_prefix="", extra="ignore", case_sensitive=False
    )

    deepseek_api_key: str = ""
    deepseek_base_url: str = ""
    deepseek_model: str = ""
    deepseek_thinking: bool = False

    @field_validator("deepseek_model", mode="before")
    @classmethod
    def validate_deepseek_model(cls, value: object) -> str:
        """Normalize and validate the DeepSeek model name.

        Accepts canonical names (``deepseek-v4-flash``, ``deepseek-v4-pro``)
        and common misspellings.  An empty string is allowed so the service
        can start unconfigured and fail fast at request time.
        """
        raw = str(value).strip().lower()
        if not raw:
            return ""
        canonical = _MODEL_ALIASES.get(raw)
        if canonical is None:
            allowed = ", ".join(sorted({m.value for m in DeepSeekModelName}))
            raise ValueError(
                f"Invalid DeepSeek model '{value}'. "
                f"Allowed values: {allowed}"
            )
        return canonical.value
    mcp_base_url: str = "http://localhost:8091/mcp"
    redis_url: str = "redis://localhost:6379/0"
    agent_database_url: str = "postgresql://devcollab:devcollab@localhost:5432/devcollab"
    knowledge_core_base_url: str = "http://localhost:8080"
    agent_internal_service_token: str = ""
    agent_delegation_timeout_seconds: float = Field(10, gt=0, le=120)
    agent_max_selected_files: int = Field(6, ge=1, le=20)
    agent_max_code_chars: int = Field(40_000, ge=1)
    agent_max_bound_documents: int = Field(5, ge=1, le=20)
    agent_max_candidate_documents: int = Field(5, ge=1, le=20)
    agent_max_document_structures: int = Field(3, ge=1, le=20)
    agent_max_tool_calls: int = Field(24, ge=5, le=100)
    agent_run_ttl_seconds: int = Field(86_400, ge=60)
    agent_request_timeout_seconds: float = Field(30, gt=0, le=120)
    agent_model_connect_timeout_seconds: float = Field(15, gt=0, le=300)
    agent_model_request_timeout_seconds: float = Field(3600, gt=0, le=14_400)
    agent_unit_timeout_seconds: float = Field(14_400, gt=0, le=86_400)
    agent_worker_poll_seconds: float = Field(2, gt=0, le=60)
    agent_worker_heartbeat_seconds: float = Field(15, gt=0, le=300)
    agent_unit_lease_seconds: int = Field(60, ge=10, le=3600)
    agent_unit_max_attempts: int = Field(3, ge=1, le=10)
    agent_model_max_input_characters: int = Field(120_000, ge=1)
    agent_review_max_operations: int = Field(50, ge=1, le=50)
    agent_review_max_evidence: int = Field(50, ge=1, le=50)
    agent_repository_page_size: int = Field(200, ge=1, le=200)
    agent_project_list_page_size: int = Field(200, ge=1, le=200)
    agent_metadata_batch_size: int = Field(100, ge=1, le=100)
    agent_binding_batch_size: int = Field(100, ge=1, le=100)
    agent_max_discovered_files: int = Field(5_000, ge=1, le=50_000)
    agent_project_max_files: int = Field(5_000, ge=1, le=50_000)
    agent_max_discovery_pages: int = Field(100, ge=1, le=1_000)
    agent_max_single_file_bytes: int = Field(1_000_000, ge=1)
    agent_max_files_per_unit: int = Field(6, ge=1, le=20)
    agent_max_primary_files_per_unit: int = Field(6, ge=1, le=20)
    agent_max_supporting_files_per_unit: int = Field(4, ge=0, le=20)
    agent_max_total_files_per_unit: int = Field(10, ge=1, le=30)
    agent_max_deleted_paths_per_unit: int = Field(100, ge=1, le=1_000)
    agent_max_units: int = Field(500, ge=1, le=5_000)
    agent_max_analysis_units: int = Field(500, ge=1, le=5_000)
    agent_project_unit_concurrency: int = Field(2, ge=1, le=16)
    agent_project_execution_limit: int = Field(0, ge=0, le=500)
    agent_project_max_tool_calls: int = Field(24, ge=5, le=100)
    devcollab_memory_profile_enabled: bool = False
    devcollab_memory_profile_run_id: str = ""
    devcollab_memory_profile_output_dir: Path = Path(".data/memory-profile")
    devcollab_memory_profile_interval_ms: int = Field(1000, ge=500, le=60_000)
    devcollab_memory_profile_queue_capacity: int = Field(1024, ge=1, le=65_536)


@lru_cache
def get_settings() -> Settings:
    return Settings.model_validate({})

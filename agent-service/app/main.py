from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.agent_jobs import router as jobs_router
from app.api.agent_runs import router as runs_router
from app.clients.delegation_client import KnowledgeCoreDelegationClient
from app.clients.mcp_client import OfficialMcpClient
from app.clients.run_store import RedisRunStore
from app.config import Settings, get_settings
from app.persistence.job_repository import AgentJobRepository, PostgresAgentJobRepository
from app.profiling import MemoryProfileConfig, RuntimeMemoryProfiler
from app.providers.base import ModelProvider
from app.providers.deepseek import DeepSeekProvider
from app.runtime.executor import AgentRunExecutor


def create_app(
    settings: Settings | None = None,
    mcp_client: object | None = None,
    run_store: object | None = None,
    model_provider: ModelProvider | None = None,
    job_repository: AgentJobRepository | None = None,
    delegation_client: object | None = None,
) -> FastAPI:
    configured = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.settings = configured
        app.state.memory_profiler = RuntimeMemoryProfiler(
            MemoryProfileConfig(
                enabled=configured.devcollab_memory_profile_enabled,
                run_id=configured.devcollab_memory_profile_run_id,
                output_dir=configured.devcollab_memory_profile_output_dir,
                interval_ms=configured.devcollab_memory_profile_interval_ms,
                queue_capacity=configured.devcollab_memory_profile_queue_capacity,
            ),
            "agent-service",
        )
        app.state.mcp_client = mcp_client or OfficialMcpClient(
            configured.mcp_base_url,
            configured.agent_request_timeout_seconds,
        )
        app.state.run_store = run_store or RedisRunStore(configured.redis_url)
        app.state.model_provider = model_provider or DeepSeekProvider(
            api_key=configured.deepseek_api_key,
            base_url=configured.deepseek_base_url,
            model=configured.deepseek_model,
            connect_timeout_seconds=configured.agent_model_connect_timeout_seconds,
            request_timeout_seconds=configured.agent_model_request_timeout_seconds,
            thinking=configured.deepseek_thinking,
        )
        app.state.run_executor = AgentRunExecutor(
            app.state.mcp_client,
            app.state.model_provider,
            app.state.run_store,
            configured,
        )
        app.state.job_repository = job_repository or PostgresAgentJobRepository(
            configured.agent_database_url
        )
        app.state.delegation_client = delegation_client or KnowledgeCoreDelegationClient(
            configured.knowledge_core_base_url,
            configured.agent_internal_service_token,
            configured.agent_delegation_timeout_seconds,
        )
        yield
        try:
            await app.state.run_executor.close()
            if job_repository is None:
                await app.state.job_repository.close()
        finally:
            app.state.memory_profiler.close()

    app = FastAPI(
        title="DevCollab Agent Service",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.include_router(runs_router)
    app.include_router(jobs_router)

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP", "mode": "context-only"}

    return app


app = create_app()

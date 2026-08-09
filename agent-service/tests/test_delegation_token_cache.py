from uuid import uuid4

import pytest

from app.runtime.delegation_token_cache import DelegationTokenCache


@pytest.mark.asyncio
async def test_reuses_delegation_token_within_ttl():
    now = 100.0
    calls = 0

    async def exchange(*, delegation_id, job_id):
        nonlocal calls
        calls += 1
        return f"token-{calls}"

    cache = DelegationTokenCache(ttl_seconds=60, clock=lambda: now)
    delegation_id = uuid4()

    first = await cache.get(
        delegation_id=delegation_id,
        job_id=uuid4(),
        exchange=exchange,
    )
    second = await cache.get(
        delegation_id=delegation_id,
        job_id=uuid4(),
        exchange=exchange,
    )

    assert first == "token-1"
    assert second == "token-1"
    assert calls == 1


@pytest.mark.asyncio
async def test_exchanges_again_after_ttl_expires():
    now = 100.0
    calls = 0

    def clock():
        return now

    async def exchange(*, delegation_id, job_id):
        nonlocal calls
        calls += 1
        return f"token-{calls}"

    cache = DelegationTokenCache(ttl_seconds=60, clock=clock)
    delegation_id = uuid4()

    first = await cache.get(
        delegation_id=delegation_id,
        job_id=uuid4(),
        exchange=exchange,
    )
    now = 161.0
    second = await cache.get(
        delegation_id=delegation_id,
        job_id=uuid4(),
        exchange=exchange,
    )

    assert first == "token-1"
    assert second == "token-2"
    assert calls == 2


def test_caps_ttl_at_five_minutes():
    cache = DelegationTokenCache(ttl_seconds=600)

    assert cache.ttl_seconds == 300.0

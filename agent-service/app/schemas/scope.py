"""语义范围模型——第 2b 层产出。

EntryPoint      一个业务入口（HTTP 路由、消息消费者等）。
ScopeMember     范围内一个符号及其角色、结构距离。
SemanticScope   从一个或多个入口出发收集的跨文件代码子图。
"""

from __future__ import annotations

from dataclasses import dataclass, field


# ── 入口类型 ────────────────────────────────────────────────────────────────


class EntryKind:
    HTTP_ROUTE = "HTTP_ROUTE"
    MESSAGE_CONSUMER = "MESSAGE_CONSUMER"
    SCHEDULED_TASK = "SCHEDULED_TASK"
    RPC_HANDLER = "RPC_HANDLER"
    COMMAND_HANDLER = "COMMAND_HANDLER"
    PUBLIC_METHOD = "PUBLIC_METHOD"


@dataclass(frozen=True)
class EntryPoint:
    """一个业务入口。

    symbol_key   入口符号的 symbol_key（指向 AtomCatalog）。
    kind         入口类型。
    label        人类可读标签（如 "POST /users"）。
    """

    symbol_key: str
    kind: str
    label: str = ""


# ── 范围成员 ────────────────────────────────────────────────────────────────


class MemberRole:
    """成员在范围中的结构角色——纯位置描述，不含业务语义。"""
    ENTRY = "ENTRY"                    # 入口本身
    DIRECT_CALLEE = "DIRECT_CALLEE"     # 入口直接调用
    INDIRECT_CALLEE = "INDIRECT_CALLEE"  # 沿调用链可达
    TYPE_DEPENDENCY = "TYPE_DEPENDENCY"  # 参数或返回类型
    SHARED_DEPENDENCY = "SHARED_DEPENDENCY"  # 被多个入口共享


@dataclass(frozen=True)
class ScopeMember:
    """范围内一个符号的记录。

    symbol_key       符号标识。
    role             在范围中的结构角色。
    distance         到最近入口的最短路径深度（入口 = 0）。
    entry_paths      可达此符号的入口列表。
    is_shared        是否被多个入口共享。
    """

    symbol_key: str
    role: str
    distance: int = 0
    entry_paths: tuple[str, ...] = ()
    is_shared: bool = False


# ── 语义范围 ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class SemanticScope:
    """从一个或多个入口出发收集的跨文件代码子图。

    scope_id          sha256(入口集合) 生成。
    entries          入口列表。
    members          范围内所有符号。
    boundary         边界关系（外部符号，只记录不展开）。
    unresolved       未解析关系（有调用但无法定位）。
    related_files    范围内符号涉及的文件路径（去重、排序）。
    """

    scope_id: str
    entries: tuple[EntryPoint, ...] = ()
    members: tuple[ScopeMember, ...] = ()
    boundary: tuple[str, ...] = ()       # 外部符号名列表
    unresolved: tuple[str, ...] = ()     # 未解析符号名列表
    related_files: tuple[str, ...] = ()

    @property
    def member_count(self) -> int:
        return len(self.members)

    @property
    def entry_count(self) -> int:
        return len(self.entries)

    def member_keys(self) -> frozenset[str]:
        return frozenset(m.symbol_key for m in self.members)

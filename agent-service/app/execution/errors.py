"""执行层通用错误类型。"""


class JobExecutionError(RuntimeError):
    """Agent job 执行失败。"""
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code

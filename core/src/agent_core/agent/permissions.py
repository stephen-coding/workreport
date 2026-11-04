"""Permission bookkeeping for tool executions.

The Shell owns the actual prompting (diff card / confirm dialog); the Core only
tracks the outcome so a refused tool cannot loop forever.
"""

DENIAL_PREFIXES = ("用户拒绝", "user denied", "denied by user")


class PermissionTracker:
    def __init__(self, max_consecutive_denials: int = 3) -> None:
        self.max_consecutive_denials = max_consecutive_denials
        self.consecutive_denials = 0

    def record_result(self, ok: bool, error: str = "") -> bool:
        """Track one tool result; returns True when the loop should stop."""
        if ok or not self._is_denial(error):
            self.consecutive_denials = 0
            return False
        self.consecutive_denials += 1
        return self.consecutive_denials >= self.max_consecutive_denials

    @staticmethod
    def _is_denial(error: str) -> bool:
        text = (error or "").strip().lower()
        return any(text.startswith(prefix.lower()) for prefix in DENIAL_PREFIXES)

    def reset(self) -> None:
        self.consecutive_denials = 0

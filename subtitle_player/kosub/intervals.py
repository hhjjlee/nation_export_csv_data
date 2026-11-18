"""처리가 끝난 시간 구간(초 단위)을 관리하는 집합."""

from __future__ import annotations

import bisect
from typing import Iterable, List, Optional, Sequence, Tuple

Interval = Tuple[float, float]


class IntervalSet:
    """겹치거나 merge_gap 이내로 가까운 구간은 하나로 합쳐서 정렬된 상태로 유지한다."""

    def __init__(self, items: Optional[Iterable[Sequence[float]]] = None, merge_gap: float = 0.25):
        self.merge_gap = merge_gap
        self._iv: List[List[float]] = []
        for item in items or []:
            self.add(float(item[0]), float(item[1]))

    def add(self, start: float, end: float) -> None:
        if end <= start:
            return
        gap = self.merge_gap
        result: List[List[float]] = []
        i, n = 0, len(self._iv)
        while i < n and self._iv[i][1] + gap < start:
            result.append(self._iv[i])
            i += 1
        while i < n and self._iv[i][0] - gap <= end:
            start = min(start, self._iv[i][0])
            end = max(end, self._iv[i][1])
            i += 1
        result.append([start, end])
        result.extend(self._iv[i:])
        self._iv = result

    def contains(self, t: float) -> bool:
        idx = bisect.bisect_right([s for s, _ in self._iv], t) - 1
        return idx >= 0 and self._iv[idx][0] <= t < self._iv[idx][1]

    def interval_at(self, t: float) -> Optional[Interval]:
        idx = bisect.bisect_right([s for s, _ in self._iv], t) - 1
        if idx >= 0 and self._iv[idx][0] <= t < self._iv[idx][1]:
            return (self._iv[idx][0], self._iv[idx][1])
        return None

    def gaps(self, lo: float, hi: float, min_len: float = 0.0) -> List[Interval]:
        """[lo, hi] 안에서 아직 처리되지 않은 구간 목록."""
        out: List[Interval] = []
        cur = lo
        for s, e in self._iv:
            if e <= cur:
                continue
            if s >= hi:
                break
            if s > cur:
                out.append((cur, min(s, hi)))
            cur = max(cur, e)
            if cur >= hi:
                break
        if cur < hi:
            out.append((cur, hi))
        return [g for g in out if g[1] - g[0] >= min_len]

    def total(self) -> float:
        return sum(e - s for s, e in self._iv)

    def to_list(self) -> List[List[float]]:
        return [[round(s, 3), round(e, 3)] for s, e in self._iv]

    def __len__(self) -> int:
        return len(self._iv)

    def __iter__(self):
        return iter((s, e) for s, e in self._iv)

    def __repr__(self) -> str:
        return f"IntervalSet({self.to_list()!r})"

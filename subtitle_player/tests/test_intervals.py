from kosub.intervals import IntervalSet


def test_add_merges_overlaps_and_near_neighbours():
    s = IntervalSet(merge_gap=0.25)
    s.add(10, 20)
    s.add(0, 5)
    s.add(19, 30)
    s.add(5.1, 6)  # 0.1초 차이 → 합쳐짐
    assert s.to_list() == [[0, 6], [10, 30]]
    s.add(6, 10)
    assert s.to_list() == [[0, 30]]


def test_gaps_and_contains():
    s = IntervalSet([(0, 10), (20, 30)])
    assert s.gaps(0, 40) == [(10, 20), (30, 40)]
    assert s.gaps(5, 25) == [(10, 20)]
    assert s.gaps(0, 40, min_len=15) == []
    assert s.contains(5) and not s.contains(15) and not s.contains(30)
    assert s.interval_at(25) == (20, 30)
    assert s.total() == 20


def test_ignores_empty_and_reversed():
    s = IntervalSet()
    s.add(5, 5)
    s.add(6, 2)
    assert len(s) == 0
    assert s.gaps(0, 3) == [(0, 3)]

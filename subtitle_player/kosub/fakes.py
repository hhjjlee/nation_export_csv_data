"""테스트용 가짜 음성 인식기/번역기 (환경 변수 KOSUB_FAKE=1 일 때 사용).

모델을 내려받지 않고도 플레이어와 작업 흐름 전체를 시험할 수 있다.
소리 에너지가 있는 곳마다 'Fake sentence ...' 문장을 만들어 낸다.
"""

from __future__ import annotations

import os
import time
from typing import Iterator, List, Optional, Sequence, Tuple

import numpy as np

from .segmentation import Segment, Word
from .translators import BaseTranslator

SR = 16000


class FakeTranscriber:
    model_name = "fake"
    device = "cpu"
    compute_type = "none"
    device_note = "가짜 인식기"

    def __init__(self) -> None:
        self.speed = float(os.environ.get("KOSUB_FAKE_SPEED", "40"))  # 실시간 대비 처리 배속
        self.calls = 0

    def transcribe(
        self, audio: np.ndarray, language: Optional[str] = None, word_timestamps: bool = True, beam_size: int = 5
    ) -> Tuple[Iterator[Segment], Tuple[Optional[str], float]]:
        self.calls += 1
        duration = len(audio) / SR

        def generate() -> Iterator[Segment]:
            t = 0.4
            n = 0
            while t + 2.0 <= duration:
                time.sleep(2.6 / self.speed)
                seg_audio = audio[int(t * SR) : int((t + 2.0) * SR)]
                if len(seg_audio) and float(np.sqrt(np.mean(seg_audio ** 2))) > 0.003:
                    n += 1
                    words_text = ["Fake", "sentence", "number", f"{n}."]
                    words: List[Word] = []
                    step = 2.0 / len(words_text)
                    for i, w in enumerate(words_text):
                        words.append(Word(t + i * step, t + (i + 1) * step - 0.05, (" " if i else " ") + w))
                    yield Segment(t, t + 2.0, " " + " ".join(words_text), words, 0.01, -0.2)
                t += 3.0

        return generate(), (language or "en", 0.99)


class FakeTranslator(BaseTranslator):
    name = "fake"
    label = "가짜 번역기"
    batch_size = 20

    def _translate(self, texts: List[str], src_lang: str, context: Sequence) -> List[Optional[str]]:
        time.sleep(float(os.environ.get("KOSUB_FAKE_TRANSLATE_DELAY", "0.02")))
        return [f"가짜 번역: {t}" for t in texts]

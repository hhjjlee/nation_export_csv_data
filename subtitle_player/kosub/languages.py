"""Whisper 언어 코드와 한국어 이름, 번역 서비스용 코드 변환."""

from __future__ import annotations

from typing import Dict, Optional

# Whisper가 지원하는 언어 (openai/whisper tokenizer 순서)
WHISPER_LANGUAGES: Dict[str, str] = {
    "en": "영어",
    "zh": "중국어",
    "de": "독일어",
    "es": "스페인어",
    "ru": "러시아어",
    "ko": "한국어",
    "fr": "프랑스어",
    "ja": "일본어",
    "pt": "포르투갈어",
    "tr": "튀르키예어",
    "pl": "폴란드어",
    "ca": "카탈루냐어",
    "nl": "네덜란드어",
    "ar": "아랍어",
    "sv": "스웨덴어",
    "it": "이탈리아어",
    "id": "인도네시아어",
    "hi": "힌디어",
    "fi": "핀란드어",
    "vi": "베트남어",
    "he": "히브리어",
    "uk": "우크라이나어",
    "el": "그리스어",
    "ms": "말레이어",
    "cs": "체코어",
    "ro": "루마니아어",
    "da": "덴마크어",
    "hu": "헝가리어",
    "ta": "타밀어",
    "no": "노르웨이어",
    "th": "태국어",
    "ur": "우르두어",
    "hr": "크로아티아어",
    "bg": "불가리아어",
    "lt": "리투아니아어",
    "la": "라틴어",
    "mi": "마오리어",
    "ml": "말라얄람어",
    "cy": "웨일스어",
    "sk": "슬로바키아어",
    "te": "텔루구어",
    "fa": "페르시아어",
    "lv": "라트비아어",
    "bn": "벵골어",
    "sr": "세르비아어",
    "az": "아제르바이잔어",
    "sl": "슬로베니아어",
    "kn": "칸나다어",
    "et": "에스토니아어",
    "mk": "마케도니아어",
    "br": "브르타뉴어",
    "eu": "바스크어",
    "is": "아이슬란드어",
    "hy": "아르메니아어",
    "ne": "네팔어",
    "mn": "몽골어",
    "bs": "보스니아어",
    "kk": "카자흐어",
    "sq": "알바니아어",
    "sw": "스와힐리어",
    "gl": "갈리시아어",
    "mr": "마라티어",
    "pa": "펀자브어",
    "si": "싱할라어",
    "km": "크메르어",
    "sn": "쇼나어",
    "yo": "요루바어",
    "so": "소말리어",
    "af": "아프리칸스어",
    "oc": "오크어",
    "ka": "조지아어",
    "be": "벨라루스어",
    "tg": "타지크어",
    "sd": "신드어",
    "gu": "구자라트어",
    "am": "암하라어",
    "yi": "이디시어",
    "lo": "라오어",
    "uz": "우즈베크어",
    "fo": "페로어",
    "ht": "아이티 크리올어",
    "ps": "파슈토어",
    "tk": "투르크멘어",
    "nn": "노르웨이어(뉘노르스크)",
    "mt": "몰타어",
    "sa": "산스크리트어",
    "lb": "룩셈부르크어",
    "my": "미얀마어",
    "bo": "티베트어",
    "tl": "타갈로그어",
    "mg": "말라가시어",
    "as": "아삼어",
    "tt": "타타르어",
    "haw": "하와이어",
    "ln": "링갈라어",
    "ha": "하우사어",
    "ba": "바시키르어",
    "jw": "자바어",
    "su": "순다어",
    "yue": "광둥어",
}

# 설정 화면에서 위쪽에 보여 줄 자주 쓰는 언어
COMMON_LANGUAGES = ["en", "ja", "zh", "es", "fr", "de", "ru", "vi", "th", "id", "pt", "it", "ko"]

# 단어 사이에 공백을 쓰지 않는 문자 체계
NO_SPACE_LANGUAGES = {"ja", "zh", "yue", "th", "lo", "my", "km", "bo"}

# 구글 번역이 받는 언어 코드 (Whisper 코드와 다른 것만 변환)
_GOOGLE_SPECIAL = {"zh": "zh-CN", "he": "iw", "jw": "jw", "nn": "no", "yue": "auto"}
_GOOGLE_SUPPORTED = set(
    """af sq am ar hy as az eu be bn bs bg ca zh-CN zh-TW hr cs da nl en et tl fi fr gl ka de el gu ht
    ha haw iw hi hu is id it ja jw kn kk km ko lo la lv ln lt lb mk mg ms ml mt mi mr mn my ne no ps fa
    pl pt pa ro ru sa sr sn sd si sk sl so es su sw sv tg ta tt te th tr tk uk ur uz vi cy yi yo ba br
    bo fo oc""".split()
)


def language_name(code: Optional[str]) -> str:
    if not code:
        return "알 수 없음"
    return WHISPER_LANGUAGES.get(code, code)


def uses_spaces(code: Optional[str]) -> bool:
    return (code or "") not in NO_SPACE_LANGUAGES


def google_code(code: Optional[str]) -> str:
    if not code:
        return "auto"
    mapped = _GOOGLE_SPECIAL.get(code, code)
    return mapped if mapped in _GOOGLE_SUPPORTED else "auto"

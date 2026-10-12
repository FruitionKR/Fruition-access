#!/usr/bin/env python3
"""access의 AiModelCatalog 모델 목록이 Fruition-document dev의 목록과 같은지 검사한다.

기준은 document다(Fruition-document#45). 목록을 바꿀 때는 document를 먼저 머지한다.
사용법: scripts/check-ai-model-catalog.py [document AiModelCatalog.java 경로 또는 URL]
"""
import difflib
import re
import sys
import urllib.request
from pathlib import Path

PATH = "src/main/java/fruition/shared/ai/AiModelCatalog.java"
DOCUMENT_URL = f"https://raw.githubusercontent.com/FruitionKR/Fruition-document/dev/{PATH}"
CONSTANT = re.compile(r'public static final String (DEFAULT_\w+) = "([^"]*)";')
MODEL = re.compile(r'new AiModel\(\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)"\s*\)')


def entries(source):
    # 순서도 비교한다. 프론트가 provider별 첫 항목을 기본값으로 고르기 때문이다.
    lines = [f"{name} = {value}" for name, value in CONSTANT.findall(source)]
    lines += [" | ".join(model) for model in MODEL.findall(source)]
    return lines


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else DOCUMENT_URL
    if target.startswith("https://"):
        with urllib.request.urlopen(target, timeout=30) as response:
            document = response.read().decode()
    else:
        document = Path(target).read_text()
    access = Path(__file__).resolve().parent.parent.joinpath(PATH).read_text()

    expected, actual = entries(document), entries(access)
    if not any(" | " in line for line in expected):
        sys.exit(f"document 목록에서 AiModel 항목을 찾지 못했습니다. 형식이 바뀌었으면 이 스크립트를 고치세요: {target}")
    if expected != actual:
        diff = difflib.unified_diff(expected, actual, "document (기준)", "access", lineterm="")
        print("\n".join(diff))
        sys.exit("AiModelCatalog 목록이 document와 다릅니다. document 목록에 맞추세요(document를 먼저 머지).")
    print(f"AiModelCatalog 일치: 상수 {sum(' = ' in l for l in actual)}개, 모델 {sum(' | ' in l for l in actual)}개")


if __name__ == "__main__":
    main()

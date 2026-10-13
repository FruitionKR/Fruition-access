#!/usr/bin/env python3
"""access의 AiModelCatalog 모델 목록이 Fruition-document dev의 목록과 같은지 검사한다.

기준은 document다(Fruition-document#45). 목록을 바꿀 때는 document를 먼저 머지한다.
사용법: scripts/check-ai-model-catalog.py [document AiModelCatalog.java 경로 또는 URL | --self-test]
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
# 문자열 리터럴은 그대로 두고 // 줄 주석과 /* */ 블록 주석만 지운다.
COMMENT = re.compile(r'("(?:\\.|[^"\\\n])*")|//[^\n]*|/\*.*?\*/', re.DOTALL)


def entries(source):
    # 주석 처리한 항목은 목록에서 빠진 것이므로 비교에서 제외한다.
    source = COMMENT.sub(lambda m: m.group(1) or "", source)
    # 순서도 비교한다. 프론트가 provider별 첫 항목을 기본값으로 고르기 때문이다.
    lines = [f"{name} = {value}" for name, value in CONSTANT.findall(source)]
    lines += [" | ".join(model) for model in MODEL.findall(source)]
    return lines


def self_test():
    base = 'String DEFAULT_MODEL = "m";\nList.of(\nnew AiModel("p", "a", "A"),\nnew AiModel("p", "b", "B // x")\n);'
    assert entries(base) == entries(base.replace("\n", "\n    ")) and len(entries(base)) == 2
    assert entries(base)[-1] == "p | b | B // x"
    line = base.replace('new AiModel("p", "a"', '// new AiModel("p", "a"')
    assert entries(line) != entries(base) and len(entries(line)) == len(entries(base)) - 1
    block = base.replace('new AiModel("p", "a", "A"),', '/* new AiModel("p", "a", "A"),\n */')
    assert entries(block) == entries(line)
    print("self-test 통과")


def main():
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
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

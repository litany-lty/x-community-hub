#!/usr/bin/env python3
"""Distill quantitative & qualitative quote-tweet voice traits from a post archive."""
import re
import sys
from collections import Counter
from pathlib import Path

EMOJI_RE = re.compile(r"[\U0001F300-\U0001FAFF\u2600-\u27BF]")
SENT_SPLIT_RE = re.compile(r"[\n。！？!?…]+")


def distill_markdown_archive(path: Path) -> dict:
    raw = path.read_text(encoding="utf-8")
    body = raw.split("## 第二部分：")[1] if "## 第二部分：" in raw else raw
    if "## 第三部分：" in body:
        body = body.split("## 第三部分：")[0]
    blocks = re.split(r"\n#### #\d+ ｜ `[^`]+` ｜ \[\d+\]\([^)]+\)\n", body)[1:]
    comments = []
    for block in blocks:
        if "｜ 引用：`" not in block:
            continue
        _, _, rest = block.partition("｜ 引用：`")
        end_match = re.search(r"`\s*\n", rest)
        if not end_match:
            continue
        quoted = rest[:end_match.start()]
        comment = rest[end_match.end():].strip()
        if not comment or "纯媒体/卡片动态" in comment or "@elidlicom" in quoted[:80]:
            continue
        comments.append(comment)

    if not comments:
        return {"count": 0}

    char_lens = sorted(len(c) for c in comments)
    sent_counts = sorted(
        len([s.strip() for s in SENT_SPLIT_RE.split(c) if s.strip()]) for c in comments
    )
    emojis = Counter()
    for c in comments:
        emojis.update(EMOJI_RE.findall(c))

    n = len(comments)
    return {
        "quote_sample_count": n,
        "char_len_p25_p50_p75": [char_lens[n // 4], char_lens[n // 2], char_lens[(3 * n) // 4]],
        "sentence_p25_p50_p75": [sent_counts[n // 4], sent_counts[n // 2], sent_counts[(3 * n) // 4]],
        "le_5_sentences_ratio": round(sum(1 for s in sent_counts if s <= 5) / n, 3),
        "blank_line_spacing_ratio": round(sum(1 for c in comments if "\n\n" in c) / n, 3),
        "exclamation_ratio": round(sum(1 for c in comments if "!" in c or "！" in c) / n, 3),
        "question_ratio": round(sum(1 for c in comments if "?" in c or "？" in c) / n, 3),
        "top_emojis": emojis.most_common(12),
    }


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Usage: distill_quote_voice.py <posts_archive.md>")
    import json
    print(json.dumps(distill_markdown_archive(Path(sys.argv[1])), ensure_ascii=False, indent=2))

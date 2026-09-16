#!/usr/bin/env python3
"""AI 研究报告：提示词组装与结构化解析。

这里放的全是**纯函数**：给定任务信息与确定性上下文，产出要发给模型的 messages；
给定模型回复，解析成结构化报告。真正的网络调用在 ai_service.research_report。

口径上的一个关键约定
--------------------
研究任务的数值口径**由 Java 计算并提供**，本模块只原样引用，绝不重算。
原因：同一批K线在 Java 与 Python 各算一遍，界面上的数字与报告里的数字迟早会分叉，
而这种分叉极难发现——两边都"看起来对"。所以本模块不做任何金融计算，
只把 Java 给出的 metrics 原样写进系统消息。

这与既有的确定性计算层（research_tools.py，服务于聊天与单次分析路径）是**两条口径**，
属于已知的重复，边界写在各自的文档里：新任务路径用 Java 的数值，
既有路径沿用 Python 的数值。合并两者需要一次单独的、带迁移的决定。
"""
from typing import Any, Dict, List, Optional
import json
import re

# 任务类型。**这是跨语言协议**：必须与 Java 的
# com.jarvis.research.ai.ResearchTaskType 枚举名逐字一致，
# 测试 test_research_report.py 会直接读那个文件来钉住它。
TASK_TYPES = ("REPORT", "SENTIMENT", "CHAIN", "RISK", "TREND", "STRATEGY")

# 每种类型要求模型聚焦什么。放在这里而不是让 Java 传提示词：
# 提示词的措辞与模型的脾气有关，属于 AI 服务自己的事。
TASK_TYPE_FOCUS: Dict[str, str] = {
    "REPORT": "给出一份完整的研究判断：先说结论，再给支撑理由，最后给需要继续跟踪的变量。",
    "SENTIMENT": "聚焦市场情绪：当前情绪偏乐观还是偏谨慎，依据是什么，情绪可能如何反转。",
    "CHAIN": "聚焦产业链与关联标的：该标的上游成本、下游需求、可替代标的与传导路径。",
    "RISK": "聚焦风险：识别价格、流动性、政策与事件风险，并说明哪些指标已经显示风险抬升。",
    "TREND": "聚焦趋势研判：当前处于什么阶段，趋势的支撑与破坏条件分别是什么。",
    "STRATEGY": "聚焦策略讨论：在不同风险偏好下可以考虑的应对方式，以及各自的触发条件。",
}

# 硬约束。放在每条提示词末尾，且与 FIN_SYS_PROMPT 的口径一致。
_HARD_RULES = (
    "硬性要求：\n"
    "1. 只能使用下面提供的确定性数值，不得自行计算、修改或编造任何价格与指标；"
    "上下文里没有的数值，就说数据不足，不要估计。\n"
    "2. 必须如实说明数据缺口，不要用推测填补。\n"
    "3. 涉及操作建议时要提示风险，不承诺收益。\n"
    "4. 只输出一个 JSON 对象，不要输出任何解释性文字或 Markdown 代码块围栏。\n"
)

_SYSTEM_PROMPT = (
    "你是「库里帕酱」，贾维斯金融投研平台的研究助手。"
    "你收到的数值全部由程序计算，是唯一可信口径；不得改写、重算或编造。"
    "你的职责是基于这些数值给出有依据的解释与判断，并明确区分"
    "「数据支持的事实」与「你的推断」。"
)

_JSON_SHAPE = (
    "JSON 结构：\n"
    '{"summary": "一句话结论", '
    '"sections": [{"title": "小节标题", "content": "小节正文"}], '
    '"risks": ["风险提示", "..."]}\n'
)


def build_messages(task: Dict[str, Any], metrics: Optional[Dict[str, Any]] = None,
                   quote: Optional[Dict[str, Any]] = None,
                   warnings: Optional[List[str]] = None) -> List[Dict[str, str]]:
    """组装发给模型的 messages。

    metrics / quote 都是 Java 确定性计算层给的**只读**数值，原样引用。
    warnings 是 Java 记录的数据缺口，也原样引用——让"数据缺什么"由程序决定，
    而不是让模型自己判断自己缺什么。
    """
    task_type = str(task.get("task_type") or "REPORT").upper()
    focus = TASK_TYPE_FOCUS.get(task_type, TASK_TYPE_FOCUS["REPORT"])

    lines: List[str] = [
        "请完成一次金融研究任务。",
        f"任务类型：{task_type}",
    ]
    if task.get("title"):
        lines.append(f"任务标题：{task['title']}")
    if task.get("market") or task.get("symbol"):
        lines.append(
            "标的：{} {}".format(task.get("market") or "", task.get("symbol") or "").strip()
        )
    if task.get("question"):
        lines.append(f"研究者的问题：{task['question']}")
    lines.append(f"本次要求：{focus}")

    lines.append("")
    lines.append("以下数值由 JARVIS 确定性计算层提供，是唯一可信的数值口径：")
    lines.append(json.dumps(
        {"metrics": metrics or {}, "quote": quote or {}},
        ensure_ascii=False,
        separators=(",", ":"),
        default=str,
    ))

    if warnings:
        lines.append("")
        lines.append("已知数据缺口（请如实转述，不要回避）：")
        for warning in warnings:
            lines.append(f"- {warning}")

    lines.append("")
    lines.append(_HARD_RULES + _JSON_SHAPE)

    return [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": "\n".join(lines)},
    ]


def parse_report(content: Any) -> Dict[str, Any]:
    """把模型回复解析成结构化报告。

    **解析失败也绝不丢内容**：把原文放进 summary，sections 留空。
    一份格式不合规的报告仍然是有价值的产出，直接判失败会让用户白等一次调用；
    而静默丢弃更是最糟的选择。
    """
    text = "" if content is None else str(content).strip()
    payload = _extract_json(text)
    if not isinstance(payload, dict):
        return {"summary": text, "sections": [], "risks": [], "parsed": False}

    return {
        "summary": _as_text(payload.get("summary")),
        "sections": _as_sections(payload.get("sections")),
        "risks": _as_text_list(payload.get("risks")),
        "parsed": True,
    }


def _extract_json(text: str) -> Optional[Any]:
    """从模型回复里取出 JSON 对象。

    模型很爱加代码块围栏或前后缀说明，所以：先剥围栏，再从第一个 { 到最后一个 }
    截一段试解析。取最外层而不是最内层，因为报告本身就是一个对象。
    """
    if not text:
        return None
    candidate = text.strip()
    fence = re.search(r"```(?:json)?\s*(.+?)\s*```", candidate, re.DOTALL)
    if fence:
        candidate = fence.group(1).strip()

    try:
        return json.loads(candidate)
    except (ValueError, TypeError):
        pass

    start = candidate.find("{")
    end = candidate.rfind("}")
    if start == -1 or end <= start:
        return None
    try:
        return json.loads(candidate[start:end + 1])
    except (ValueError, TypeError):
        return None


def _as_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    return json.dumps(value, ensure_ascii=False)


def _as_text_list(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value.strip()] if value.strip() else []
    if isinstance(value, list):
        return [_as_text(item) for item in value if _as_text(item)]
    return [_as_text(value)]


def _as_sections(value: Any) -> List[Dict[str, str]]:
    """小节必须是 {title, content} 形状；形状不对就丢掉那一条，不丢掉整份报告。"""
    if not isinstance(value, list):
        return []
    sections: List[Dict[str, str]] = []
    for item in value:
        if isinstance(item, dict):
            title = _as_text(item.get("title"))
            content = _as_text(item.get("content"))
            if title or content:
                sections.append({"title": title, "content": content})
        elif isinstance(item, str) and item.strip():
            sections.append({"title": "", "content": item.strip()})
    return sections
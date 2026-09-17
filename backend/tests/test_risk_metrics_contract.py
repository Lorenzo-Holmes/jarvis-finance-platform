"""风险指标的跨语言口径契约（Phase 2 ⑧ 的护栏）。

同一组输入，Java 的 RiskMetrics 与 Python 的 risk_metrics 必须给出**逐字相同**的字符串。
这条测试的存在理由：迁移调用方之前必须先证明两套实现一致，否则"统一口径"无从谈起——
先切调用方再发现两边算出的数不一样，那就等于把两个口径都改坏了。

期望值与 java-backend/src/test/java/com/jarvis/research/ai/RiskMetricsTest.java
用的是同一组向量、同一组期望值，两边都是人工闭合推导出来的（推导过程写在各自注释里），
**不是从任一实现里抄的**——只复述实现的测试证明不了"算对了"。
"""
from backend.app import research_tools


def doubling():
    """每次翻倍：11 根 → 10 个收益率，每个都精确等于 1.0（2 倍是精确可表示的）。"""
    closes, value = [], 100.0
    for _ in range(11):
        closes.append(value)
        value *= 2
    return closes


def flat_then_crash():
    """十根持平 + 最后一根腰斩：9 个 0 收益率 + 1 个 -0.5，便于手算分位数与回撤。"""
    return [100.0] * 10 + [50.0]


def jitter():
    """在 100 / 100.5 之间来回：收益率绝对值都很小，一个阈值都不该命中。"""
    return ([100.0, 100.5] * 6)[:11]


def test_doubling_series_matches_java():
    result = research_tools.risk_metrics(doubling())

    assert result["available"] is True
    assert result["bars"] == 11
    # 置信度缺省 0.95；alpha = 0.05；k = max(1, round(10 × 0.05)) = 1
    assert result["confidence"] == "0.950000"
    assert result["var_pct"] == "100.0000"
    assert result["es_pct"] == "100.0000"
    # 10 个完全相同值的样本标准差 = 0
    assert result["vol_annual_pct"] == "0.0000"
    assert result["max_drawdown_pct"] == "0.0000"
    assert result["last_close"] == "102400.000000"
    assert result["symbol"] is None
    assert "var_amount" not in result

    assert [alert["metric"] for alert in result["alerts"]] == ["var", "es"]
    assert result["alerts"][0] == {
        "level": "high",
        "metric": "var",
        "rule": "单日VaR绝对值 >= 4%",
        "message": "单日最大预期亏损约 100.0000%，风险敞口偏高。",
    }


def test_flat_then_crash_matches_java():
    result = research_tools.risk_metrics(flat_then_crash())

    assert result["var_pct"] == "-50.0000"
    assert result["es_pct"] == "-50.0000"
    # 手算：收益率 = [0×9, -0.5]，均值 -0.05
    # 离差平方和 = 9×0.05² + 0.45² = 0.225 → 样本方差 = 0.225/9 = 0.025
    # 年化 = √0.025 × √252 ≈ 2.5099800796 → ×100 → 250.99800796 → 4 位小数
    assert result["vol_annual_pct"] == "250.9980"
    assert result["max_drawdown_pct"] == "-50.0000"
    assert result["last_close"] == "50.000000"

    assert [alert["metric"] for alert in result["alerts"]] == ["var", "es", "max_drawdown"]
    assert result["alerts"][2]["level"] == "medium"
    assert result["alerts"][2]["rule"] == "历史最大回撤 >= 20%"


def test_insufficient_samples_matches_java():
    # 10 根 → 通过 MIN_BARS，但只有 9 个收益率
    ten = research_tools.risk_metrics(list(range(1, 11)))
    assert ten == {"available": False, "reason": "insufficient_closes", "bars": 10}

    # 5 根 → 连 MIN_BARS 都不到
    five = research_tools.risk_metrics([1, 2, 3, 4, 5])
    assert five == {"available": False, "reason": "insufficient_closes", "bars": 5}


def test_zero_and_negative_closes_are_dropped_like_java():
    # 13 个输入里有 1 个 0 和 1 个负价 → 只剩 11 根，刚好够 10 个收益率
    result = research_tools.risk_metrics(
        [100.0, 110, 0, 90, -5, 120, 130, 140, 150, 160, 170, 180, 190])

    assert result["available"] is True
    assert result["bars"] == 11


def test_dropping_values_can_fall_below_the_sample_floor():
    # 12 个输入里有 1 个 0 和 1 个无法解析 → 只剩 10 根 → 9 个收益率 → 判定不可用。
    # 过滤坏数据与"够不够样本"是耦合的：丢着丢着就不该再给结论。
    result = research_tools.risk_metrics(
        ["100", "110", "0", "120", "130", "140", "150", "160", "170", "180", "190", "abc"])

    assert result == {"available": False, "reason": "insufficient_closes", "bars": 10}


def test_portfolio_amount_and_alert_order_match_java():
    result = research_tools.risk_metrics(doubling(), portfolio_value=10000)

    assert result["var_amount"] == "10000.000000"
    # 先阈值告警，再金额告警——顺序即契约
    assert [alert["metric"] for alert in result["alerts"]] == ["var", "es", "portfolio_var"]
    assert result["alerts"][2]["level"] == "medium"
    assert result["alerts"][2]["message"] == "按资金规模估算，单日潜在亏损约 10000.000000 元，建议评估仓位。"


def test_no_threshold_hit_matches_java():
    result = research_tools.risk_metrics(jitter())

    # 收益率在 ±0.5% 附近：|VaR| ≈ 0.4975% < 2%，|回撤| ≈ 0.4975% < 20%
    assert result["var_pct"] == "-0.4975"
    assert len(result["alerts"]) == 1
    assert result["alerts"][0] == {
        "level": "low",
        "metric": "overall",
        "rule": "无阈值命中",
        "message": "当前样本未命中高风险阈值，维持常规监控。",
    }


def test_no_threshold_hit_with_portfolio_matches_java():
    result = research_tools.risk_metrics(jitter(), portfolio_value=100000)

    # 100/100.5 - 1 = -0.00497512437810945273…；×100000 → 取绝对值 6 位小数
    assert result["var_amount"] == "-497.512438"
    assert len(result["alerts"]) == 1
    assert result["alerts"][0]["message"] == \
        "当前样本未命中高风险阈值；按资金规模估算单日潜在亏损约 497.512438 元。"


def test_confidence_is_clamped_like_java():
    assert research_tools.risk_metrics(doubling())["confidence"] == "0.950000"
    assert research_tools.risk_metrics(doubling(), confidence=None)["confidence"] == "0.950000"
    assert research_tools.risk_metrics(doubling(), confidence=2.0)["confidence"] == "0.990000"
    assert research_tools.risk_metrics(doubling(), confidence=0.1)["confidence"] == "0.500000"
    assert research_tools.risk_metrics(doubling(), confidence="0.9")["confidence"] == "0.900000"
    # 解析不出来时退回缺省，而不是抛异常
    assert research_tools.risk_metrics(doubling(), confidence="不是数字")["confidence"] == "0.950000"


def test_output_shape_and_key_order_match_java():
    result = research_tools.risk_metrics(doubling(), portfolio_value=10000, symbol="sh600519")

    assert list(result.keys()) == [
        "available", "symbol", "confidence", "bars", "last_close", "var_pct",
        "es_pct", "vol_annual_pct", "max_drawdown_pct", "alerts", "var_amount",
    ]
    assert result["symbol"] == "sh600519"
    assert list(result["alerts"][0].keys()) == ["level", "metric", "rule", "message"]
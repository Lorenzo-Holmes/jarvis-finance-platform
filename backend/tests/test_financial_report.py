from backend.app.ai_service import parse_financial_report


def test_financial_report_parser_returns_stable_schema_and_missing_sections():
    result = parse_financial_report(
        "【核心结论】\n利润改善，但仍需复核。\n"
        "【风险点】\n- 应收账款增长。\n",
        128,
    )

    assert result["schema_version"] == "financial-report-v1"
    assert result["source_length"] == 128
    assert result["sections"]["conclusion"] == "利润改善，但仍需复核。"
    assert result["sections"]["risks"] == "- 应收账款增长。"
    assert result["validation"]["status"] == "needs_review"
    assert "cash_flow" in result["validation"]["missing_sections"]


def test_financial_report_parser_marks_complete_fixed_sections():
    content = "\n".join(f"【{title}】\n数据不足" for title in [
        "核心结论", "营收与利润", "盈利质量与毛利率", "资产负债",
        "现金流", "风险点", "投资观点", "待核验事项",
    ])
    result = parse_financial_report(content, 80)
    assert result["validation"]["status"] == "complete"
    assert result["validation"]["missing_sections"] == []

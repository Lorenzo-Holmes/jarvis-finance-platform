"""⑧ 跨语言契约：模拟盘组合风险指标（portfolio_metrics / PortfolioMetrics）。

与 Java 的 PortfolioMetricsTest 钉同一组独立手算的字面量，覆盖三条风险档与两类数据质量分支。

一个容易写错的口径在这里固定下来：**杠杆 = 总敞口 ÷ 净权益**（不是反过来），
所以空仓时是 0 而不是 1。（我第一版 Java 测试就写反了，被测试当场拦下。）
"""
from backend.app.research_tools import portfolio_metrics

RESULT_KEYS = ["available", "account_status", "cash", "initial_cash", "gross_exposure",
               "total_assets", "loan_balance", "position_loan_sum", "frozen_margin",
               "position_margin_sum", "net_equity", "gross_leverage", "maintenance_margin_pct",
               "total_return_pct", "risk_status", "max_position_concentration_pct",
               "stale_position_count", "accounting_invariant_ok", "data_quality_status", "positions"]

POSITION_KEYS = ["symbol", "quantity", "current_price", "exposure", "loan", "margin_used",
                 "unrealized_pnl", "return_on_equity_pct", "quote_time", "stale", "concentration_pct"]


def _position(quantity, price, loan, margin, stale=None, quote_time=None):
    item = {"quantity": quantity, "currentPrice": price, "loan": loan, "marginUsed": margin}
    if stale is not None:
        item["stale"] = stale
    if quote_time is not None:
        item["quoteTime"] = quote_time
    return item


def _payload(cash, initial_cash, loan_balance, frozen_margin, positions):
    return {"cash": cash, "initialCash": initial_cash, "loanBalance": loan_balance,
            "frozenMargin": frozen_margin, "positions": positions}


def test_empty_portfolio():
    result = portfolio_metrics(_payload(10000, 10000, 0, 0, {}))

    assert list(result.keys()) == RESULT_KEYS
    assert result["available"] is True
    assert result["cash"] == "10000.000000"
    assert result["gross_exposure"] == "0.000000"
    assert result["total_assets"] == "10000.000000"
    assert result["net_equity"] == "10000.000000"
    assert result["gross_leverage"] == "0.0000"          # 总敞口/净权益 = 0/10000
    assert result["maintenance_margin_pct"] == "100.0000"
    assert result["total_return_pct"] == "0.0000"
    assert result["risk_status"] == "NONE"
    assert result["max_position_concentration_pct"] is None
    assert result["stale_position_count"] == 0
    assert result["accounting_invariant_ok"] is True
    assert result["data_quality_status"] == "OK"
    assert result["positions"] == []


def test_single_position():
    result = portfolio_metrics(_payload(
        1000, 1000, 0, 50, {"BTC": _position(2, 100, 0, 50, quote_time="2026-01-02T10:00:00")}))

    assert result["gross_exposure"] == "200.000000"
    assert result["total_assets"] == "1200.000000"
    assert result["net_equity"] == "1200.000000"
    assert result["maintenance_margin_pct"] == "600.0000"
    assert result["gross_leverage"] == "0.1667"
    assert result["total_return_pct"] == "20.0000"
    assert result["risk_status"] == "SAFE"
    assert result["max_position_concentration_pct"] == "100.0000"
    assert result["accounting_invariant_ok"] is True
    assert result["data_quality_status"] == "OK"

    item = result["positions"][0]
    assert list(item.keys()) == POSITION_KEYS
    assert item["symbol"] == "BTC"
    assert item["quantity"] == "2.000000"
    assert item["current_price"] == "100.000000"
    assert item["exposure"] == "200.000000"
    assert item["loan"] == "0.000000"
    assert item["margin_used"] == "50.000000"
    assert item["unrealized_pnl"] == "150.000000"
    assert item["return_on_equity_pct"] == "300.0000"
    assert item["quote_time"] == "2026-01-02T10:00:00"
    assert item["stale"] is False
    assert item["concentration_pct"] == "100.0000"


def test_danger_and_stale_precedence():
    # stale 用字符串 "false"：Python 真值语义下非空字符串为真
    result = portfolio_metrics(_payload(
        0, 0, 190, 10, {"X": _position(1, 200, 190, 10, stale="false")}))

    assert result["net_equity"] == "10.000000"
    assert result["maintenance_margin_pct"] == "5.0000"
    assert result["gross_leverage"] == "20.0000"
    assert result["risk_status"] == "DANGER"
    assert result["total_return_pct"] is None            # initialCash 非正
    assert result["stale_position_count"] == 1
    assert result["data_quality_status"] == "STALE_QUOTES"
    assert result["accounting_invariant_ok"] is True
    assert result["positions"][0]["stale"] is True


def test_warn_band():
    result = portfolio_metrics(_payload(0, 1000, 160, 40, {"X": _position(1, 200, 160, 40)}))

    assert result["net_equity"] == "40.000000"
    assert result["maintenance_margin_pct"] == "20.0000"
    assert result["risk_status"] == "WARN"


def test_accounting_mismatch():
    result = portfolio_metrics(_payload(1000, 1000, 0, 0, {"X": _position(1, 200, 0, 50)}))

    assert result["accounting_invariant_ok"] is False
    assert result["data_quality_status"] == "ACCOUNTING_MISMATCH"


def test_defensive_branches():
    assert portfolio_metrics(None) == {"available": False, "reason": "no_portfolio_data"}
    assert list(portfolio_metrics(None).keys()) == ["available", "reason"]

    # positions 不是对象 → 视为空
    assert portfolio_metrics(_payload(100, 100, 0, 0, ["不是对象"]))["gross_exposure"] == "0.000000"

    # 非对象条目跳过；非数值字段按 0；margin<=0 时 ROE 为 None
    result = portfolio_metrics(_payload(100, 100, 0, 0,
                                        {"X": "不是对象", "Y": _position("abc", None, None, None)}))

    assert len(result["positions"]) == 1
    assert result["positions"][0]["symbol"] == "Y"
    assert result["gross_exposure"] == "0.000000"
    assert result["positions"][0]["return_on_equity_pct"] is None
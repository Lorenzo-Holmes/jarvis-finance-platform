"""确定性金融研究工具。

所有供 LLM 引用的量化指标在这里由 Python 计算；模型只负责解释这些结果，
不得自行替代行情/指标计算。输入数据由 Java 主后端从自身业务数据层注入。
"""
from __future__ import annotations

from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any, Dict, Iterable, List, Optional


Q6 = Decimal("0.000001")
Q4 = Decimal("0.0001")


def _decimal(value: Any) -> Optional[Decimal]:
    if value is None:
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        return None


def _fmt(value: Optional[Decimal], quantum: Decimal = Q6) -> Optional[str]:
    if value is None:
        return None
    return format(value.quantize(quantum, rounding=ROUND_HALF_UP), "f")


def _percent(numerator: Decimal, denominator: Decimal) -> Optional[Decimal]:
    if denominator == 0:
        return None
    return numerator * Decimal("100") / denominator


def quote_metrics(price_data: Dict[str, Any]) -> Dict[str, Any]:
    """从一条行情快照计算可审计的派生指标。"""
    price = _decimal(price_data.get("price"))
    prev = _decimal(price_data.get("prev_close") or price_data.get("yesterday_price"))
    open_price = _decimal(price_data.get("open"))
    high = _decimal(price_data.get("high"))
    low = _decimal(price_data.get("low"))

    result: Dict[str, Any] = {
        "price": _fmt(price),
        "prev_close": _fmt(prev),
        "open": _fmt(open_price),
        "high": _fmt(high),
        "low": _fmt(low),
        "quote_time": price_data.get("quote_time") or price_data.get("time"),
        "source": price_data.get("source"),
    }
    if price is not None and prev is not None:
        result["change"] = _fmt(price - prev)
        result["change_pct"] = _fmt(_percent(price - prev, prev), Q4)
    if price is not None and open_price is not None:
        result["vs_open_pct"] = _fmt(_percent(price - open_price, open_price), Q4)
    if high is not None and low is not None and prev is not None:
        result["intraday_range_pct"] = _fmt(_percent(high - low, prev), Q4)
    return result


def _valid_rows(payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    rows = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        return []
    valid: List[Dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        close = _decimal(row.get("close"))
        if close is None:
            continue
        valid.append(row)
    return valid


def _sma(values: List[Decimal], period: int) -> Optional[Decimal]:
    if len(values) < period:
        return None
    window = values[-period:]
    return sum(window, Decimal("0")) / Decimal(period)


def _ema(values: List[Decimal], period: int) -> Optional[Decimal]:
    if len(values) < period:
        return None
    alpha = Decimal("2") / Decimal(period + 1)
    value = sum(values[:period], Decimal("0")) / Decimal(period)
    for current in values[period:]:
        value = current * alpha + value * (Decimal("1") - alpha)
    return value


def _rsi(values: List[Decimal], period: int = 14) -> Optional[Decimal]:
    if len(values) < period + 1:
        return None
    window = values[-(period + 1):]
    gains = Decimal("0")
    losses = Decimal("0")
    for previous, current in zip(window, window[1:]):
        delta = current - previous
        if delta >= 0:
            gains += delta
        else:
            losses += -delta
    avg_gain = gains / Decimal(period)
    avg_loss = losses / Decimal(period)
    if avg_loss == 0:
        return Decimal("100") if avg_gain > 0 else Decimal("50")
    rs = avg_gain / avg_loss
    return Decimal("100") - Decimal("100") / (Decimal("1") + rs)


def kline_metrics(payload: Dict[str, Any]) -> Dict[str, Any]:
    """基于 K 线计算研究助手可引用的技术指标。"""
    rows = _valid_rows(payload)
    if not rows:
        return {"available": False, "reason": "no_kline_data"}
    closes = [_decimal(row.get("close")) for row in rows]
    closes = [value for value in closes if value is not None]
    if not closes:
        return {"available": False, "reason": "no_close_data"}

    last = closes[-1]
    sma5 = _sma(closes, 5)
    sma20 = _sma(closes, 20)
    ema12 = _ema(closes, 12)
    rsi14 = _rsi(closes, 14)
    recent = rows[-20:]
    lows = [_decimal(row.get("low")) for row in recent]
    highs = [_decimal(row.get("high")) for row in recent]
    lows = [value for value in lows if value is not None]
    highs = [value for value in highs if value is not None]
    support20 = min(lows) if lows else None
    resistance20 = max(highs) if highs else None

    return {
        "available": True,
        "bars": len(rows),
        "start": rows[0].get("date"),
        "end": rows[-1].get("date"),
        "last_close": _fmt(last),
        "sma5": _fmt(sma5),
        "sma20": _fmt(sma20),
        "ema12": _fmt(ema12),
        "rsi14": _fmt(rsi14, Q4),
        "distance_to_sma20_pct": _fmt(_percent(last - sma20, sma20), Q4) if sma20 is not None else None,
        "support20": _fmt(support20),
        "resistance20": _fmt(resistance20),
    }


def portfolio_metrics(payload: Dict[str, Any]) -> Dict[str, Any]:
    """从模拟盘原始账户/持仓快照计算 AI 可引用的组合风险指标。"""
    if not isinstance(payload, dict):
        return {"available": False, "reason": "no_portfolio_data"}

    cash = _decimal(payload.get("cash")) or Decimal("0")
    initial_cash = _decimal(payload.get("initialCash")) or Decimal("0")
    account_loan = _decimal(payload.get("loanBalance")) or Decimal("0")
    frozen_margin = _decimal(payload.get("frozenMargin")) or Decimal("0")
    raw_positions = payload.get("positions") if isinstance(payload.get("positions"), dict) else {}

    positions: List[Dict[str, Any]] = []
    gross_exposure = Decimal("0")
    total_position_loan = Decimal("0")
    total_margin = Decimal("0")
    for symbol, raw in raw_positions.items():
        if not isinstance(raw, dict):
            continue
        quantity = _decimal(raw.get("quantity")) or Decimal("0")
        current_price = _decimal(raw.get("currentPrice")) or Decimal("0")
        exposure = quantity * current_price
        loan = _decimal(raw.get("loan")) or Decimal("0")
        margin = _decimal(raw.get("marginUsed")) or Decimal("0")
        invested = loan + margin
        pnl = exposure - invested
        roe = _percent(pnl, margin) if margin > 0 else None
        gross_exposure += exposure
        total_position_loan += loan
        total_margin += margin
        positions.append({
            "symbol": str(symbol),
            "quantity": _fmt(quantity),
            "current_price": _fmt(current_price),
            "exposure": _fmt(exposure),
            "loan": _fmt(loan),
            "margin_used": _fmt(margin),
            "unrealized_pnl": _fmt(pnl),
            "return_on_equity_pct": _fmt(roe, Q4),
            "quote_time": raw.get("quoteTime"),
            "stale": bool(raw.get("stale")),
        })

    net_equity = cash + gross_exposure - account_loan
    total_assets = cash + gross_exposure
    maintenance = _percent(net_equity, gross_exposure) if gross_exposure > 0 else Decimal("100")
    gross_leverage = gross_exposure / net_equity if net_equity > 0 else None
    total_return = _percent(net_equity - initial_cash, initial_cash) if initial_cash > 0 else None
    risk_status = "NONE"
    if gross_exposure > 0 and maintenance is not None:
        risk_status = "DANGER" if maintenance < Decimal("15") else "WARN" if maintenance < Decimal("25") else "SAFE"

    max_concentration = Decimal("0")
    stale_position_count = 0
    for item in positions:
        exposure = _decimal(item.get("exposure")) or Decimal("0")
        concentration = _percent(exposure, gross_exposure) if gross_exposure > 0 else None
        item["concentration_pct"] = _fmt(concentration, Q4)
        if concentration is not None:
            max_concentration = max(max_concentration, concentration)
        if item.get("stale"):
            stale_position_count += 1

    tolerance = Decimal("0.01")
    accounting_invariant_ok = (
        abs(account_loan - total_position_loan) <= tolerance
        and abs(frozen_margin - total_margin) <= tolerance
    )
    data_quality_status = "STALE_QUOTES" if stale_position_count else "ACCOUNTING_MISMATCH" if not accounting_invariant_ok else "OK"

    return {
        "available": True,
        "account_status": payload.get("status"),
        "cash": _fmt(cash),
        "initial_cash": _fmt(initial_cash),
        "gross_exposure": _fmt(gross_exposure),
        "total_assets": _fmt(total_assets),
        "loan_balance": _fmt(account_loan),
        "position_loan_sum": _fmt(total_position_loan),
        "frozen_margin": _fmt(frozen_margin),
        "position_margin_sum": _fmt(total_margin),
        "net_equity": _fmt(net_equity),
        "gross_leverage": _fmt(gross_leverage, Q4),
        "maintenance_margin_pct": _fmt(maintenance, Q4),
        "total_return_pct": _fmt(total_return, Q4),
        "risk_status": risk_status,
        "max_position_concentration_pct": _fmt(max_concentration, Q4) if positions else None,
        "stale_position_count": stale_position_count,
        "accounting_invariant_ok": accounting_invariant_ok,
        "data_quality_status": data_quality_status,
        "positions": positions,
    }


def deterministic_context(raw_context: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    if not isinstance(raw_context, dict):
        return {}
    prices = raw_context.get("prices") if isinstance(raw_context.get("prices"), dict) else {}
    klines = raw_context.get("klines") if isinstance(raw_context.get("klines"), dict) else {}
    portfolio = raw_context.get("portfolio") if isinstance(raw_context.get("portfolio"), dict) else None
    return {
        "generated_at": raw_context.get("generated_at"),
        "quotes": {
            str(market): quote_metrics(quote)
            for market, quote in prices.items()
            if isinstance(quote, dict)
        },
        "indicators": {
            str(market): kline_metrics(payload)
            for market, payload in klines.items()
            if isinstance(payload, dict)
        },
        "portfolio": portfolio_metrics(portfolio) if portfolio is not None else {"available": False, "reason": "no_portfolio_data"},
    }

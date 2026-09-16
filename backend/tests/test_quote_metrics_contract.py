"""⑧ 跨语言契约：行情快照派生指标（quote_metrics / QuoteMetrics）。

与 Java 的 QuoteMetricsTest 钉**同一组字面量**，两边各自独立推导。数值不是从实现输出
回抄的——change / change_pct / vs_open_pct / intraday_range_pct 全部手算，
并把三处边界单独钉住：数值 0 的 or 回退、分母为 0、键缺失（而非值为 null）。

契约要点（改实现前先读这里）：
- 金额 6 位小数、百分比 4 位小数，ROUND_HALF_UP，定点格式（不是科学计数）
- 依赖项不成立时**整键缺失**；而分母为 0 时是**键在、值为 None**——两种语义不能混
- `prev_close or yesterday_price` 与 `quote_time or time` 都是 Python 真值判断：
  数值 0、空串都会回退。Java 侧必须复刻这套真值表，否则同一份快照会算出不同的数

已知差异（不隐藏，且不影响任何实际输入）：Java 的 QuoteMetrics.compute(null) 会返回
一组 null 值的基础键，而 Python 的 quote_metrics(None) 会抛 AttributeError。
Java 那侧是对 None 的防御性扩展（口径不因此改变）；空字典 {} 两端行为一致，故此处在
空字典上做断言。
"""
from backend.app.research_tools import quote_metrics

BASE_KEYS = ["price", "prev_close", "open", "high", "low", "quote_time", "source"]


def test_typical_snapshot_pins_key_order_and_scales():
    result = quote_metrics({
        "price": 100.5, "prev_close": 100, "open": 99, "high": 102, "low": 98.5,
        "quote_time": "2026-01-02T10:00:00", "source": "tencent",
    })

    # 键顺序即 Python 字典插入顺序，Java 侧用 LinkedHashMap 对齐
    assert list(result.keys()) == BASE_KEYS + [
        "change", "change_pct", "vs_open_pct", "intraday_range_pct"]
    assert result["price"] == "100.500000"
    assert result["prev_close"] == "100.000000"
    assert result["open"] == "99.000000"
    assert result["high"] == "102.000000"
    assert result["low"] == "98.500000"
    assert result["quote_time"] == "2026-01-02T10:00:00"
    assert result["source"] == "tencent"
    # 手算：change = 0.5；pct = 0.5*100/100 = 0.5；vs_open = 1.5*100/99 = 1.515151… → 1.5152；
    # range = 3.5*100/100 = 3.5
    assert result["change"] == "0.500000"
    assert result["change_pct"] == "0.5000"
    assert result["vs_open_pct"] == "1.5152"
    assert result["intraday_range_pct"] == "3.5000"


def test_zero_prev_close_falls_back_to_yesterday_price():
    # 数值 0 是假值 → 走 yesterday_price=50。只判 None 的实现会算出完全不同的一组数
    result = quote_metrics({"price": 55, "prev_close": 0, "yesterday_price": 50,
                            "open": 54, "high": 56, "low": 53})

    assert result["prev_close"] == "50.000000"
    assert result["change"] == "5.000000"
    assert result["change_pct"] == "10.0000"
    assert result["vs_open_pct"] == "1.8519"      # 1*100/54 = 1.851851… → 1.8519
    assert result["intraday_range_pct"] == "6.0000"


def test_missing_prev_close_keeps_null_key_and_drops_dependents():
    result = quote_metrics({"price": 10, "prev_close": 0, "open": 9, "high": 11, "low": 8.5})

    assert "prev_close" in result and result["prev_close"] is None
    assert "change" not in result
    assert "change_pct" not in result
    assert "intraday_range_pct" not in result          # 依赖 prev
    assert result["vs_open_pct"] == "11.1111"          # 1*100/9 = 11.1111…


def test_zero_denominator_yields_present_key_with_null():
    result = quote_metrics({"price": 10, "prev_close": 10, "open": 0, "high": 12, "low": 11})

    assert result["change"] == "0.000000"
    assert result["change_pct"] == "0.0000"
    assert "vs_open_pct" in result and result["vs_open_pct"] is None
    assert result["intraday_range_pct"] == "10.0000"


def test_unparsable_price_and_empty_quote_time():
    result = quote_metrics({"price": "abc", "yesterday_price": "105",
                            "quote_time": "", "time": "09:30"})

    assert "price" in result and result["price"] is None
    assert result["prev_close"] == "105.000000"        # 字符串按字面解析
    assert result["quote_time"] == "09:30"             # 空串是假值 → 回退到 time
    assert result["source"] is None
    assert "change" not in result


def test_empty_dict_gives_only_the_base_keys():
    result = quote_metrics({})

    assert list(result.keys()) == BASE_KEYS
    assert all(result[key] is None for key in BASE_KEYS)
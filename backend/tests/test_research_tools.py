from backend.app.research_tools import kline_metrics, portfolio_metrics, quote_metrics


def test_quote_metrics_are_deterministic():
    result = quote_metrics({
        "price": "110",
        "prev_close": "100",
        "open": "105",
        "high": "112",
        "low": "98",
    })

    assert result["change"] == "10.000000"
    assert result["change_pct"] == "10.0000"
    assert result["vs_open_pct"] == "4.7619"
    assert result["intraday_range_pct"] == "14.0000"


def test_kline_metrics_include_research_indicators():
    rows = [
        {
            "date": f"2026-08-{day:02d}",
            "close": 100 + day,
            "high": 101 + day,
            "low": 99 + day,
        }
        for day in range(1, 25)
    ]

    result = kline_metrics({"data": rows})

    assert result["available"] is True
    assert result["sma5"] == "122.000000"
    assert result["sma20"] == "114.500000"
    assert result["ema12"] == "118.500000"
    assert result["rsi14"] == "100.0000"
    assert result["distance_to_sma20_pct"] == "8.2969"
    assert result["support20"] == "104.000000"
    assert result["resistance20"] == "125.000000"


def test_portfolio_metrics_compute_exposure_leverage_and_concentration():
    result = portfolio_metrics({
        "initialCash": 100000,
        "cash": 70000,
        "loanBalance": 15000,
        "frozenMargin": 15000,
        "status": "ACTIVE",
        "positions": {
            "sh518850": {
                "quantity": 1000,
                "currentPrice": 20,
                "loan": 10000,
                "marginUsed": 10000,
                "quoteTime": "2026-09-08T10:00:00",
                "stale": False,
            },
            "hf_XAU": {
                "quantity": 500,
                "currentPrice": 20,
                "loan": 5000,
                "marginUsed": 5000,
                "quoteTime": "2026-09-08T10:00:00",
                "stale": False,
            },
        },
    })

    assert result["available"] is True
    assert result["gross_exposure"] == "30000.000000"
    assert result["net_equity"] == "85000.000000"
    assert result["gross_leverage"] == "0.3529"
    assert result["maintenance_margin_pct"] == "283.3333"
    assert result["total_return_pct"] == "-15.0000"
    assert result["risk_status"] == "SAFE"
    assert result["max_position_concentration_pct"] == "66.6667"
    assert result["stale_position_count"] == 0
    assert result["accounting_invariant_ok"] is True
    assert result["data_quality_status"] == "OK"
    assert result["positions"][0]["concentration_pct"] == "66.6667"
    assert result["positions"][1]["concentration_pct"] == "33.3333"

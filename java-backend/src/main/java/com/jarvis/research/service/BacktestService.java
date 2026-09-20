package com.jarvis.research.service;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.market.dto.DailyKlineDTO;
import com.jarvis.research.market.dto.KlineBarDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双均线回测。
 * 数据由 Java 行情层/数据库提供，前端不再自行计算，也不再直连 Python 回测接口。
 */
@Service
@RequiredArgsConstructor
public class BacktestService {

    private static final String STRATEGY_VERSION = "double-ma-v1";
    private final MarketDataService marketDataService;

    public Map<String, Object> run(String market, int shortMa, int longMa,
                                   double initialCash, int limit) {
        return run(market, shortMa, longMa, initialCash, limit, null);
    }

    public Map<String, Object> run(String market, int shortMa, int longMa,
                                   double initialCash, int limit, String asOf) {
        if (shortMa < 1 || longMa < 2 || shortMa >= longMa) {
            throw new IllegalArgumentException("均线参数需满足 1 <= short_ma < long_ma");
        }
        if (initialCash <= 0) {
            throw new IllegalArgumentException("initial_cash 必须大于0");
        }
        if (limit < longMa || limit > 5000) {
            throw new IllegalArgumentException("limit 必须在 long_ma ~ 5000 之间");
        }

        if (asOf != null && !asOf.isBlank()) {
            try {
                LocalDate.parse(asOf.trim());
            } catch (Exception e) {
                throw new IllegalArgumentException("as_of 必须为 yyyy-MM-dd");
            }
        }

        DailyKlineDTO kline = asOf == null || asOf.isBlank()
                ? marketDataService.getDailyKline(market, limit)
                : marketDataService.getDailyKline(market, limit, asOf.trim());
        List<KlineBarDTO> rows = kline.data();
        if (rows == null || rows.size() < longMa) {
            throw new IllegalArgumentException("K线数据不足，至少需要 " + longMa + " 根");
        }

        List<Double> closes = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        for (KlineBarDTO row : rows) {
            if (row == null || row.close() == null || row.date() == null) continue;
            closes.add(row.close());
            dates.add(row.date());
        }
        if (closes.size() < longMa) {
            throw new IllegalArgumentException("有效K线数据不足，至少需要 " + longMa + " 根");
        }

        final double transactionCost = 0.001;
        List<Double> shortSeries = movingAverage(closes, shortMa);
        List<Double> longSeries = movingAverage(closes, longMa);

        double cash = initialCash;
        double shares = 0.0;
        boolean inPosition = false;
        List<Map<String, Object>> trades = new ArrayList<>();
        List<Map<String, Object>> equityCurve = new ArrayList<>();

        for (int i = 0; i < closes.size(); i++) {
            double close = closes.get(i);
            Double ms = shortSeries.get(i);
            Double ml = longSeries.get(i);

            if (ms != null && ml != null) {
                if (ms > ml && !inPosition) {
                    double buyPrice = close * (1 + transactionCost);
                    shares = cash / buyPrice;
                    cash = 0.0;
                    inPosition = true;
                    trades.add(trade(dates.get(i), "BUY", close, shares));
                } else if (ms < ml && inPosition) {
                    double sellQty = shares;
                    double sellPrice = close * (1 - transactionCost);
                    cash = sellQty * sellPrice;
                    shares = 0.0;
                    inPosition = false;
                    trades.add(trade(dates.get(i), "SELL", close, sellQty));
                }
            }

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", dates.get(i));
            point.put("equity", round2(cash + shares * close));
            point.put("close", close);
            equityCurve.add(point);
        }

        double finalEquity = ((Number) equityCurve.get(equityCurve.size() - 1).get("equity")).doubleValue();
        double bhEquity = initialCash / closes.get(0) * closes.get(closes.size() - 1) / (1 + transactionCost);
        double totalReturn = (finalEquity / initialCash - 1) * 100;
        double buyHoldReturn = (bhEquity / initialCash - 1) * 100;

        double peak = ((Number) equityCurve.get(0).get("equity")).doubleValue();
        double maxDrawdown = 0.0;
        List<Map<String, Object>> drawdownCurve = new ArrayList<>();
        for (Map<String, Object> point : equityCurve) {
            double equity = ((Number) point.get("equity")).doubleValue();
            peak = Math.max(peak, equity);
            double drawdown = peak > 0 ? (peak - equity) / peak : 0.0;
            maxDrawdown = Math.max(maxDrawdown, drawdown);
            point.put("drawdown_pct", round2(drawdown * 100));
            Map<String, Object> drawdownPoint = new LinkedHashMap<>();
            drawdownPoint.put("date", point.get("date"));
            drawdownPoint.put("drawdown_pct", round2(drawdown * 100));
            drawdownCurve.add(drawdownPoint);
        }

        long calendarDays = Math.max(1, ChronoUnit.DAYS.between(
                LocalDate.parse(dates.get(0)),
                LocalDate.parse(dates.get(dates.size() - 1))));
        double annualReturn = finalEquity > 0
                ? (Math.pow(finalEquity / initialCash, 365.0 / calendarDays) - 1) * 100
                : 0.0;
        Map<String, Object> advanced = advancedMetrics(trades, equityCurve);

        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", dates.get(0));
        range.put("end", dates.get(dates.size() - 1));
        range.put("bars", dates.size());

        Map<String, Object> params = new LinkedHashMap<>();
        String effectiveAsOf = dates.get(dates.size() - 1);
        params.put("short_ma", shortMa);
        params.put("long_ma", longMa);
        params.put("transaction_cost", transactionCost);
        params.put("limit", limit);
        params.put("as_of", effectiveAsOf);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", market);
        out.put("range", range);
        out.put("as_of", effectiveAsOf);
        out.put("strategy_version", STRATEGY_VERSION);
        out.put("data_fingerprint", dataFingerprint(market, dates, closes));
        out.put("params", params);
        out.put("initial_cash", initialCash);
        out.put("final_equity", round2(finalEquity));
        out.put("total_return_pct", round2(totalReturn));
        out.put("annual_return_pct", round2(annualReturn));
        out.put("buy_hold_return_pct", round2(buyHoldReturn));
        out.put("max_drawdown_pct", round2(maxDrawdown * 100));
        out.put("drawdown_curve", drawdownCurve);
        out.put("sharpe_ratio", advanced.get("sharpe_ratio"));
        out.put("win_rate_pct", advanced.get("win_rate_pct"));
        out.put("profit_loss_ratio", advanced.get("profit_loss_ratio"));
        out.put("avg_holding_days", advanced.get("avg_holding_days"));
        out.put("completed_trades", advanced.get("completed_trades"));
        out.put("num_trades", trades.size());
        out.put("trades", trades.size() <= 20 ? trades : trades.subList(trades.size() - 20, trades.size()));
        out.put("equity_curve", equityCurve);
        return out;
    }

    /**
     * 高级回测指标只基于已闭合的买卖轮次；最后仍持仓的 BUY 不计入胜率/盈亏比，
     * 避免把未实现盈亏和已实现交易混在一个口径中。
     */
    private Map<String, Object> advancedMetrics(List<Map<String, Object>> trades,
                                                List<Map<String, Object>> equityCurve) {
        final double transactionCost = 0.001;
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < equityCurve.size(); i++) {
            double previous = number(equityCurve.get(i - 1).get("equity"));
            double current = number(equityCurve.get(i).get("equity"));
            if (previous > 0) returns.add(current / previous - 1.0);
        }
        double sharpe = 0.0;
        if (returns.size() > 1) {
            double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double variance = returns.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum()
                    / (returns.size() - 1);
            double deviation = Math.sqrt(Math.max(0.0, variance));
            if (deviation > 0) sharpe = mean / deviation * Math.sqrt(252.0);
        }

        List<Double> profits = new ArrayList<>();
        List<Long> holdingDays = new ArrayList<>();
        Map<String, Object> buy = null;
        for (Map<String, Object> trade : trades) {
            if ("BUY".equals(trade.get("type"))) {
                buy = trade;
            } else if ("SELL".equals(trade.get("type")) && buy != null) {
                double buyPrice = number(buy.get("price")) * (1.0 + transactionCost);
                double sellPrice = number(trade.get("price")) * (1.0 - transactionCost);
                double quantity = number(trade.get("qty"));
                profits.add((sellPrice - buyPrice) * quantity);
                try {
                    holdingDays.add(Math.max(0L, ChronoUnit.DAYS.between(
                            LocalDate.parse(String.valueOf(buy.get("date"))),
                            LocalDate.parse(String.valueOf(trade.get("date"))))));
                } catch (RuntimeException ignored) {
                    // 非日期型数据不阻断主回测结果，只跳过持仓时长。
                }
                buy = null;
            }
        }
        long wins = profits.stream().filter(value -> value > 0).count();
        double winRate = profits.isEmpty() ? 0.0 : wins * 100.0 / profits.size();
        double winningAverage = profits.stream().filter(value -> value > 0)
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
        double losingAverage = profits.stream().filter(value -> value < 0)
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
        double profitLossRatio = losingAverage < 0 ? winningAverage / Math.abs(losingAverage) : 0.0;
        double averageHolding = holdingDays.stream().mapToLong(Long::longValue).average().orElse(0.0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sharpe_ratio", round3(sharpe));
        out.put("win_rate_pct", round2(winRate));
        out.put("profit_loss_ratio", round3(profitLossRatio));
        out.put("avg_holding_days", round2(averageHolding));
        out.put("completed_trades", profits.size());
        return out;
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private String dataFingerprint(String market, List<String> dates, List<Double> closes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(market.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < dates.size(); i++) {
                digest.update(("|" + dates.get(i) + "=" + Double.toString(closes.get(i)))
                        .getBytes(StandardCharsets.UTF_8));
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("无法生成回测数据指纹", e);
        }
    }

    private List<Double> movingAverage(List<Double> values, int window) {
        List<Double> out = new ArrayList<>();
        double sum = 0.0;
        for (int i = 0; i < values.size(); i++) {
            sum += values.get(i);
            if (i >= window) sum -= values.get(i - window);
            out.add(i >= window - 1 ? sum / window : null);
        }
        return out;
    }

    private Map<String, Object> trade(String date, String type, double price, double qty) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date);
        out.put("type", type);
        out.put("price", round3(price));
        out.put("qty", round2(qty));
        return out;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

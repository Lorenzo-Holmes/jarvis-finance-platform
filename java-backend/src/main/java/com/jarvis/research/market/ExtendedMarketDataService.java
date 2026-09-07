package com.jarvis.research.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ExternalWebClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A 股、美股和加密货币的公开行情适配器。
 *
 * <p>本阶段先提供受控标的白名单，避免把任意 URL 或任意外部 symbol 直接透传给行情源。
 * A 股使用腾讯公开接口，美股使用 Yahoo Finance chart 接口，加密货币使用 Binance 公共接口。
 * 后续如需生产级多源容灾，可在本服务后面增加统一行情落库和备用源。</p>
 */
@Slf4j
@Service
public class ExtendedMarketDataService {

    private static final Charset GBK = Charset.forName("GBK");
    private static final Set<String> YAHOO_INTERVALS = Set.of("1d", "1h", "15m");
    private static final Set<String> BINANCE_INTERVALS = Set.of("1d", "1h", "15m", "5m");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private final List<Instrument> instruments = List.of(
            new Instrument("a_share", "sh600519", "贵州茅台", "CNY", "Tencent"),
            new Instrument("a_share", "sz000001", "平安银行", "CNY", "Tencent"),
            new Instrument("a_share", "sz300750", "宁德时代", "CNY", "Tencent"),
            new Instrument("us_stock", "AAPL", "Apple", "USD", "Yahoo Finance"),
            new Instrument("us_stock", "MSFT", "Microsoft", "USD", "Yahoo Finance"),
            new Instrument("us_stock", "NVDA", "NVIDIA", "USD", "Yahoo Finance"),
            new Instrument("us_stock", "TSLA", "Tesla", "USD", "Yahoo Finance"),
            new Instrument("crypto", "BTCUSDT", "Bitcoin", "USDT", "Binance"),
            new Instrument("crypto", "ETHUSDT", "Ethereum", "USDT", "Binance"),
            new Instrument("crypto", "SOLUSDT", "Solana", "USDT", "Binance")
    );

    public ExtendedMarketDataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = ExternalWebClients.create(java.time.Duration.ofSeconds(12));
    }

    public List<Map<String, Object>> listInstruments() {
        return instruments.stream().map(this::instrumentView).toList();
    }

    public Map<String, Object> quote(String market, String symbol) {
        Instrument instrument = requireInstrument(market, symbol);
        try {
            return switch (market) {
                case "a_share" -> quoteTencent(instrument);
                case "us_stock" -> quoteYahoo(instrument);
                case "crypto" -> quoteCryptoWithFallback(instrument);
                default -> throw invalid("不支持的市场: " + market);
            };
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("扩展行情源调用失败 market={}, symbol={}, message={}", market, symbol, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "行情源暂不可用，请稍后重试");
        }
    }

    public Map<String, Object> kline(String market, String symbol, String interval, int limit) {
        Instrument instrument = requireInstrument(market, symbol);
        if (limit < 1 || limit > 500) {
            throw invalid("limit 必须在 1~500 之间");
        }
        String normalized = normalizeInterval(interval);
        try {
            List<Map<String, Object>> data = switch (market) {
                case "a_share" -> {
                    if (!"1d".equals(normalized)) {
                        throw invalid("A股当前仅支持日K");
                    }
                    yield klineTencent(instrument, limit);
                }
                case "us_stock" -> klineYahoo(instrument, normalized, limit);
                case "crypto" -> klineCryptoWithFallback(instrument, normalized, limit);
                default -> throw invalid("不支持的市场: " + market);
            };
            Map<String, Object> technicalAnalysis = enrichTechnicalIndicators(data);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("market", market);
            out.put("symbol", symbol);
            out.put("interval", normalized);
            out.put("count", data.size());
            out.put("data", data);
            out.put("analysis", technicalAnalysis);
            if (!data.isEmpty()) {
                out.put("range", Map.of(
                        "start", data.get(0).get("date"),
                        "end", data.get(data.size() - 1).get("date"),
                        "count", data.size()));
            }
            return out;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("扩展K线源调用失败 market={}, symbol={}, message={}", market, symbol, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "K线源暂不可用，请稍后重试");
        }
    }

    private Map<String, Object> quoteTencent(Instrument instrument) {
        byte[] bytes = webClient.get()
                .uri("https://qt.gtimg.cn/q=" + instrument.symbol())
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
        if (bytes == null) throw upstream("A股行情为空");
        String raw = new String(bytes, GBK);
        Matcher matcher = Pattern.compile("\\\"(.+?)\\\"").matcher(raw);
        if (!matcher.find()) throw upstream("A股行情格式异常");
        String[] values = matcher.group(1).split("~", -1);
        Double price = parseDouble(values, 3);
        Double previous = parseDouble(values, 4);
        if (price == null) throw upstream("A股价格为空");
        Map<String, Object> out = quoteBase(instrument);
        out.put("price", price);
        out.put("prev_close", previous);
        out.put("change", parseDouble(values, 31));
        out.put("change_pct", parseDouble(values, 32));
        out.put("open", parseDouble(values, 5));
        out.put("high", parseDouble(values, 33));
        out.put("low", parseDouble(values, 34));
        out.put("quote_time", LocalDateTime.now().toString());
        return out;
    }

    private Map<String, Object> quoteYahoo(Instrument instrument) throws Exception {
        return quoteYahoo(instrument, instrument.symbol());
    }

    private Map<String, Object> quoteYahoo(Instrument instrument, String providerSymbol) throws Exception {
        JsonNode result = yahooResult(providerSymbol, "1d", "1d");
        JsonNode meta = result.path("meta");
        Double price = number(meta, "regularMarketPrice");
        Double previous = number(meta, "previousClose");
        if (previous == null) previous = number(meta, "chartPreviousClose");
        if (price == null) throw upstream("美股价格为空");
        Map<String, Object> out = quoteBase(instrument);
        out.put("price", price);
        out.put("prev_close", previous);
        out.put("change", price - (previous == null ? price : previous));
        out.put("change_pct", previous == null || previous == 0 ? 0.0 : (price - previous) / previous * 100.0);
        out.put("quote_time", meta.path("regularMarketTime").isNumber()
                ? Instant.ofEpochSecond(meta.path("regularMarketTime").asLong()).toString()
                : LocalDateTime.now().toString());
        return out;
    }

    private Map<String, Object> quoteCryptoWithFallback(Instrument instrument) throws Exception {
        try {
            return quoteBinance(instrument);
        } catch (Exception binanceError) {
            log.warn("Binance 行情不可用，切换 Yahoo 备用源 symbol={}, message={}",
                    instrument.symbol(), binanceError.getMessage());
            Map<String, Object> fallback = quoteYahoo(instrument, yahooCryptoSymbol(instrument.symbol()));
            fallback.put("source", "Yahoo Finance (fallback)");
            return fallback;
        }
    }

    private Map<String, Object> quoteBinance(Instrument instrument) throws Exception {
        JsonNode root = objectMapper.readTree(webClient.get()
                .uri("https://api.binance.com/api/v3/ticker/24hr?symbol=" + instrument.symbol())
                .retrieve()
                .bodyToMono(String.class)
                .block());
        Double price = textDouble(root, "lastPrice");
        Double previous = textDouble(root, "prevClosePrice");
        if (price == null) throw upstream("加密货币价格为空");
        Map<String, Object> out = quoteBase(instrument);
        out.put("price", price);
        out.put("prev_close", previous);
        out.put("change", textDouble(root, "priceChange"));
        out.put("change_pct", textDouble(root, "priceChangePercent"));
        out.put("open", textDouble(root, "openPrice"));
        out.put("high", textDouble(root, "highPrice"));
        out.put("low", textDouble(root, "lowPrice"));
        out.put("quote_time", root.path("closeTime").isNumber()
                ? Instant.ofEpochMilli(root.path("closeTime").asLong()).toString()
                : LocalDateTime.now().toString());
        return out;
    }

    private List<Map<String, Object>> klineTencent(Instrument instrument, int limit) throws Exception {
        String param = instrument.symbol() + ",day,,,500,qfq";
        String body = webClient.get()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("web.ifzq.gtimg.cn")
                        .path("/appstock/app/fqkline/get")
                        .queryParam("param", param).build())
                .retrieve().bodyToMono(String.class).block();
        JsonNode root = objectMapper.readTree(body);
        JsonNode raw = root.path("data").path(instrument.symbol()).path("day");
        if (!raw.isArray()) raw = root.path("data").path(instrument.symbol()).path("qfqday");
        if (!raw.isArray()) throw upstream("A股K线为空");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : raw) {
            if (!item.isArray() || item.size() < 5) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", item.get(0).asText());
            row.put("open", item.get(1).asDouble());
            row.put("close", item.get(2).asDouble());
            row.put("high", item.get(3).asDouble());
            row.put("low", item.get(4).asDouble());
            row.put("volume", item.size() > 5 ? item.get(5).asDouble(0.0) : 0.0);
            out.add(row);
        }
        return tail(out, limit);
    }

    private List<Map<String, Object>> klineYahoo(Instrument instrument, String interval, int limit) throws Exception {
        return klineYahoo(instrument, instrument.symbol(), interval, limit);
    }

    private List<Map<String, Object>> klineYahoo(Instrument instrument, String providerSymbol,
                                                 String interval, int limit) throws Exception {
        JsonNode result = yahooResult(providerSymbol, "1y", interval);
        JsonNode timestamps = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").path(0);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            if (!numberAt(quote.path("open"), i) || !numberAt(quote.path("close"), i)
                    || !numberAt(quote.path("high"), i) || !numberAt(quote.path("low"), i)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", Instant.ofEpochSecond(timestamps.get(i).asLong()).toString());
            row.put("open", quote.path("open").get(i).asDouble());
            row.put("close", quote.path("close").get(i).asDouble());
            row.put("high", quote.path("high").get(i).asDouble());
            row.put("low", quote.path("low").get(i).asDouble());
            row.put("volume", numberAt(quote.path("volume"), i) ? quote.path("volume").get(i).asDouble() : 0.0);
            out.add(row);
        }
        return tail(out, limit);
    }

    private List<Map<String, Object>> klineCryptoWithFallback(Instrument instrument,
                                                               String interval, int limit) throws Exception {
        try {
            return klineBinance(instrument, interval, limit);
        } catch (Exception binanceError) {
            log.warn("Binance K线不可用，切换 Yahoo 备用源 symbol={}, message={}",
                    instrument.symbol(), binanceError.getMessage());
            return klineYahoo(instrument, yahooCryptoSymbol(instrument.symbol()), interval, limit);
        }
    }

    private List<Map<String, Object>> klineBinance(Instrument instrument, String interval, int limit) throws Exception {
        JsonNode root = objectMapper.readTree(webClient.get()
                .uri("https://api.binance.com/api/v3/klines?symbol=" + instrument.symbol()
                        + "&interval=" + interval + "&limit=" + limit)
                .retrieve().bodyToMono(String.class).block());
        if (!root.isArray()) throw upstream("加密货币K线为空");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.isArray() || item.size() < 6) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", Instant.ofEpochMilli(item.get(0).asLong()).toString());
            row.put("open", item.get(1).asDouble());
            row.put("high", item.get(2).asDouble());
            row.put("low", item.get(3).asDouble());
            row.put("close", item.get(4).asDouble());
            row.put("volume", item.get(5).asDouble());
            out.add(row);
        }
        return out;
    }

    private JsonNode yahooResult(String symbol, String range, String interval) throws Exception {
        if (!YAHOO_INTERVALS.contains(interval)) throw invalid("美股不支持该周期: " + interval);
        String body = webClient.get()
                .uri("https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                        + "?range=" + range + "&interval=" + interval)
                .retrieve().bodyToMono(String.class).block();
        JsonNode result = objectMapper.readTree(body).path("chart").path("result").path(0);
        if (result.isMissingNode() || result.isNull()) throw upstream("美股行情为空");
        return result;
    }

    private String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank() || "day".equalsIgnoreCase(interval)) return "1d";
        return interval.trim().toLowerCase();
    }

    private String yahooCryptoSymbol(String symbol) {
        if (symbol != null && symbol.endsWith("USDT") && symbol.length() > 4) {
            return symbol.substring(0, symbol.length() - 4) + "-USD";
        }
        throw invalid("不支持的加密货币标的: " + symbol);
    }

    private Instrument requireInstrument(String market, String symbol) {
        if (market == null || symbol == null) throw invalid("市场和标的不能为空");
        return instruments.stream()
                .filter(item -> item.market().equals(market) && item.symbol().equalsIgnoreCase(symbol.trim()))
                .findFirst()
                .orElseThrow(() -> invalid("不支持的标的: " + market + "/" + symbol));
    }

    private Map<String, Object> instrumentView(Instrument item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", item.market());
        out.put("symbol", item.symbol());
        out.put("name", item.name());
        out.put("currency", item.currency());
        out.put("source", item.source());
        return out;
    }

    private Map<String, Object> quoteBase(Instrument item) {
        Map<String, Object> out = instrumentView(item);
        out.put("quote_time", LocalDateTime.now().atZone(ZoneId.of("Asia/Shanghai")).toString());
        return out;
    }

    private List<Map<String, Object>> tail(List<Map<String, Object>> rows, int limit) {
        int from = Math.max(0, rows.size() - limit);
        return new ArrayList<>(rows.subList(from, rows.size()));
    }

    /**
     * 计算可复核的基础技术指标。这里不使用大模型，也不输出买卖建议；AI 只负责在这些数据之上生成研究性解读。
     */
    Map<String, Object> enrichTechnicalIndicators(List<Map<String, Object>> rows) {
        List<Double> closes = rows.stream().map(row -> numeric(row, "close")).toList();
        List<Double> ema12 = ema(closes, 12);
        List<Double> ema26 = ema(closes, 26);
        List<Double> macd = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            macd.add(ema12.get(i) == null || ema26.get(i) == null ? null : ema12.get(i) - ema26.get(i));
        }
        List<Double> signal = ema(macd, 9);
        List<Double> rsi14 = rsi(closes, 14);
        List<Double> sma5 = sma(closes, 5);
        List<Double> sma20 = sma(closes, 20);

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            putIfPresent(row, "sma5", at(sma5, i));
            putIfPresent(row, "sma20", at(sma20, i));
            putIfPresent(row, "ema12", at(ema12, i));
            putIfPresent(row, "ema26", at(ema26, i));
            putIfPresent(row, "rsi14", at(rsi14, i));
            Double macdValue = at(macd, i);
            Double signalValue = at(signal, i);
            putIfPresent(row, "macd", macdValue);
            putIfPresent(row, "macd_signal", signalValue);
            putIfPresent(row, "macd_histogram",
                    macdValue == null || signalValue == null ? null : macdValue - signalValue);
        }

        Map<String, Object> analysis = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            analysis.put("status", "insufficient_data");
            analysis.put("message", "暂无足够K线数据");
            return analysis;
        }
        int latestIndex = rows.size() - 1;
        Double close = numeric(rows.get(latestIndex), "close");
        Double latestSma20 = at(sma20, latestIndex);
        Double latestEma12 = at(ema12, latestIndex);
        Double latestEma26 = at(ema26, latestIndex);
        Double latestRsi = at(rsi14, latestIndex);
        Double latestMacd = at(macd, latestIndex);
        Double latestSignal = at(signal, latestIndex);

        String trend = "neutral";
        String trendLabel = "震荡观察";
        if (close != null && latestSma20 != null && latestEma12 != null && latestEma26 != null) {
            if (close > latestSma20 && latestEma12 > latestEma26) {
                trend = "bullish";
                trendLabel = "偏强：价格在20期均线上方，短期均线向上";
            } else if (close < latestSma20 && latestEma12 < latestEma26) {
                trend = "bearish";
                trendLabel = "偏弱：价格在20期均线下方，短期均线向下";
            }
        }
        String momentum = "normal";
        String momentumLabel = "动能中性";
        if (latestRsi != null && latestRsi >= 70) {
            momentum = "overbought";
            momentumLabel = "RSI偏高，注意短线过热";
        } else if (latestRsi != null && latestRsi <= 30) {
            momentum = "oversold";
            momentumLabel = "RSI偏低，注意短线超跌";
        }

        int windowStart = Math.max(0, rows.size() - 20);
        double support = rows.subList(windowStart, rows.size()).stream()
                .mapToDouble(row -> numeric(row, "low") == null ? Double.POSITIVE_INFINITY : numeric(row, "low"))
                .min().orElse(Double.NaN);
        double resistance = rows.subList(windowStart, rows.size()).stream()
                .mapToDouble(row -> numeric(row, "high") == null ? Double.NEGATIVE_INFINITY : numeric(row, "high"))
                .max().orElse(Double.NaN);

        analysis.put("status", "ok");
        analysis.put("trend", trend);
        analysis.put("trend_label", trendLabel);
        analysis.put("momentum", momentum);
        analysis.put("momentum_label", momentumLabel);
        analysis.put("support_20", finiteOrNull(support));
        analysis.put("resistance_20", finiteOrNull(resistance));
        Map<String, Object> indicators = new LinkedHashMap<>();
        indicators.put("close", close);
        indicators.put("sma20", latestSma20);
        indicators.put("ema12", latestEma12);
        indicators.put("ema26", latestEma26);
        indicators.put("rsi14", latestRsi);
        indicators.put("macd", latestMacd);
        indicators.put("macd_signal", latestSignal);
        analysis.put("indicators", indicators);
        analysis.put("disclaimer", "技术指标仅供研究参考，不构成投资建议");
        return analysis;
    }

    private List<Double> sma(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (i + 1 < period || values.subList(i - period + 1, i + 1).stream().anyMatch(value -> value == null)) {
                out.add(null);
            } else {
                out.add(values.subList(i - period + 1, i + 1).stream()
                        .mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
            }
        }
        return out;
    }

    private List<Double> ema(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        double multiplier = 2.0 / (period + 1);
        Double previous = null;
        for (Double value : values) {
            if (value == null) {
                out.add(null);
                continue;
            }
            previous = previous == null ? value : (value - previous) * multiplier + previous;
            out.add(previous);
        }
        return out;
    }

    private List<Double> rsi(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) out.add(null);
        if (values.size() <= period) return out;
        double gains = 0;
        double losses = 0;
        for (int i = 1; i <= period; i++) {
            Double change = change(values, i);
            if (change == null) return out;
            if (change >= 0) gains += change;
            else losses -= change;
        }
        double averageGain = gains / period;
        double averageLoss = losses / period;
        out.set(period, rsiValue(averageGain, averageLoss));
        for (int i = period + 1; i < values.size(); i++) {
            Double change = change(values, i);
            if (change == null) continue;
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            averageGain = (averageGain * (period - 1) + gain) / period;
            averageLoss = (averageLoss * (period - 1) + loss) / period;
            out.set(i, rsiValue(averageGain, averageLoss));
        }
        return out;
    }

    private Double rsiValue(double averageGain, double averageLoss) {
        if (averageLoss == 0) return 100.0;
        return 100.0 - 100.0 / (1 + averageGain / averageLoss);
    }

    private Double change(List<Double> values, int index) {
        if (index <= 0 || index >= values.size() || values.get(index) == null || values.get(index - 1) == null) {
            return null;
        }
        return values.get(index) - values.get(index - 1);
    }

    private Double numeric(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Double at(List<Double> values, int index) {
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }

    private void putIfPresent(Map<String, Object> row, String key, Double value) {
        if (value != null && Double.isFinite(value)) row.put(key, value);
    }

    private Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private boolean numberAt(JsonNode node, int index) {
        return node != null && node.isArray() && index < node.size()
                && node.get(index) != null && node.get(index).isNumber();
    }

    private Double number(JsonNode node, String field) {
        return node != null && node.path(field).isNumber() ? node.path(field).asDouble() : null;
    }

    private Double textDouble(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return parseDouble(value);
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parseDouble(String[] values, int index) {
        return index < values.length ? parseDouble(values[index]) : null;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException upstream(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    private record Instrument(String market, String symbol, String name, String currency, String source) {}
}

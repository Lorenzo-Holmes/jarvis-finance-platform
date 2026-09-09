package com.jarvis.research.market;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.market.MarketPreferenceDtos.Instrument;
import com.jarvis.research.market.MarketPreferenceDtos.PreferenceView;
import com.jarvis.research.market.MarketPreferenceDtos.UpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 负责用户多市场标的偏好的校验、去重和持久化。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketPreferenceService {

    private static final int MAX_ITEMS = 100;
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,20}");
    private static final Pattern HIDDEN_KEY_PATTERN =
            Pattern.compile("(a_share|us_stock|crypto):[A-Za-z0-9._-]{1,20}");
    private static final Set<String> SUPPORTED_MARKETS = Set.of("a_share", "us_stock", "crypto");

    private final MarketPreferenceRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PreferenceView get(Long userId) {
        return repository.findByUserId(userId)
                .map(this::toView)
                .orElseGet(() -> new PreferenceView(List.of(), List.of(), false));
    }

    @Transactional
    public PreferenceView replace(Long userId, UpdateRequest request) {
        PreferenceView normalized = normalize(request);
        MarketPreference preference = repository.findByUserId(userId)
                .orElseGet(() -> MarketPreference.builder().userId(userId).build());
        preference.setWatchlistJson(writeJson(normalized.getWatchlist()));
        preference.setHiddenDefaultKeysJson(writeJson(normalized.getHiddenDefaultKeys()));
        repository.save(preference);
        return new PreferenceView(normalized.getWatchlist(), normalized.getHiddenDefaultKeys(), true);
    }

    private PreferenceView toView(MarketPreference preference) {
        return new PreferenceView(
                normalizeInstruments(readList(preference.getWatchlistJson(), new TypeReference<>() {
                })),
                normalizeHiddenKeys(readList(preference.getHiddenDefaultKeysJson(), new TypeReference<>() {
                })),
                true);
    }

    private PreferenceView normalize(UpdateRequest request) {
        if (request == null) throw badRequest("偏好数据不能为空");
        return new PreferenceView(
                normalizeInstruments(request.getWatchlist()),
                normalizeHiddenKeys(request.getHiddenDefaultKeys()),
                true);
    }

    private List<Instrument> normalizeInstruments(List<Instrument> values) {
        Map<String, Instrument> unique = new LinkedHashMap<>();
        for (Instrument value : values == null ? List.<Instrument>of() : values) {
            if (value == null) throw badRequest("自选标的不能为空");
            String market = trim(value.getMarket()).toLowerCase(Locale.ROOT);
            String symbol = trim(value.getSymbol());
            if (!SUPPORTED_MARKETS.contains(market) || !SYMBOL_PATTERN.matcher(symbol).matches()) {
                throw badRequest("自选标的格式不正确");
            }
            Instrument item = new Instrument();
            item.setMarket(market);
            item.setSymbol(symbol);
            item.setName(limit(trim(value.getName()), 80, symbol));
            item.setCurrency(limit(trim(value.getCurrency()), 12, ""));
            item.setSource(limit(trim(value.getSource()), 40, ""));
            unique.putIfAbsent(market + ":" + symbol, item);
            if (unique.size() >= MAX_ITEMS) break;
        }
        return new ArrayList<>(unique.values());
    }

    private List<String> normalizeHiddenKeys(List<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String key = trim(value);
            if (!HIDDEN_KEY_PATTERN.matcher(key).matches()) throw badRequest("隐藏默认标的格式不正确");
            unique.add(key);
            if (unique.size() >= MAX_ITEMS) break;
        }
        return new ArrayList<>(unique);
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "[]" : json, type);
        } catch (JsonProcessingException e) {
            log.warn("用户市场偏好 JSON 损坏，按空偏好处理");
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("市场偏好序列化失败", e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String limit(String value, int max, String fallback) {
        String result = value.isBlank() ? fallback : value;
        return result.length() > max ? result.substring(0, max) : result;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

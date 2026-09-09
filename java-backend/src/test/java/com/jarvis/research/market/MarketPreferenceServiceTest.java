package com.jarvis.research.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.market.MarketPreferenceDtos.Instrument;
import com.jarvis.research.market.MarketPreferenceDtos.PreferenceView;
import com.jarvis.research.market.MarketPreferenceDtos.UpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketPreferenceServiceTest {

    private final MarketPreferenceRepository repository = mock(MarketPreferenceRepository.class);
    private final MarketPreferenceService service = new MarketPreferenceService(repository, new ObjectMapper());

    @Test
    void replacesPreferencesWithDeduplicatedUserScopedData() {
        Instrument first = instrument("us_stock", "AAPL", "Apple");
        Instrument duplicate = instrument("us_stock", "AAPL", "duplicate");
        UpdateRequest request = new UpdateRequest();
        request.setWatchlist(List.of(first, duplicate));
        request.setHiddenDefaultKeys(List.of("a_share:sh600519", "a_share:sh600519"));
        when(repository.findByUserId(23L)).thenReturn(Optional.empty());
        when(repository.save(any(MarketPreference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PreferenceView result = service.replace(23L, request);

        assertTrue(result.isPersisted());
        assertEquals(1, result.getWatchlist().size());
        assertEquals("Apple", result.getWatchlist().get(0).getName());
        assertEquals(List.of("a_share:sh600519"), result.getHiddenDefaultKeys());
        verify(repository).save(any(MarketPreference.class));
    }

    @Test
    void rejectsMalformedPreferenceBeforeSaving() {
        Instrument invalid = instrument("a_share", "600519/1", "bad");
        UpdateRequest request = new UpdateRequest();
        request.setWatchlist(List.of(invalid));

        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.replace(23L, request)).getStatusCode().value());
    }

    @Test
    void returnsEmptyViewWhenUserHasNotSavedPreferences() {
        when(repository.findByUserId(23L)).thenReturn(Optional.empty());

        PreferenceView result = service.get(23L);

        assertEquals(List.of(), result.getWatchlist());
        assertEquals(List.of(), result.getHiddenDefaultKeys());
        assertEquals(false, result.isPersisted());
    }

    private Instrument instrument(String market, String symbol, String name) {
        Instrument instrument = new Instrument();
        instrument.setMarket(market);
        instrument.setSymbol(symbol);
        instrument.setName(name);
        return instrument;
    }
}

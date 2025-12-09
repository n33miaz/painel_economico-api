package br.com.painel_economico.controller;

import br.com.painel_economico.dto.HistoricalDataPoint;
import br.com.painel_economico.dto.Indicator;
import br.com.painel_economico.service.IndicatorService;
import reactor.core.publisher.Mono;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/indicators")
public class IndicatorController {

    private final IndicatorService indicatorService;

    public IndicatorController(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    @GetMapping("/all")
    public Mono<ResponseEntity<List<Indicator>>> getAllIndicators() {
        return indicatorService.getAllIndicators()
                .map(indicators -> ResponseEntity.ok(indicators));
    }

    @GetMapping("/historical/{currencyCode}")
    public Mono<ResponseEntity<List<HistoricalDataPoint>>> getHistoricalData(
            @PathVariable String currencyCode,
            @RequestParam(defaultValue = "7") int days) {
        return indicatorService.getHistoricalData(currencyCode, days)
                .map(data -> ResponseEntity.ok(data));
    }
}
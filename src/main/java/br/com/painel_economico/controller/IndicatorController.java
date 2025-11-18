package br.com.painel_economico.controller;

import br.com.painel_economico.dto.HistoricalDataPointDTO;
import br.com.painel_economico.dto.IndicatorDTO;
import br.com.painel_economico.service.IndicatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/indicators")
public class IndicatorController {

    private final IndicatorService indicatorService;

    public IndicatorController(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    @GetMapping("/all")
    public ResponseEntity<List<IndicatorDTO>> getAllIndicators() {
        List<IndicatorDTO> indicators = indicatorService.getAllIndicators();
        return ResponseEntity.ok(indicators);
    }

    @GetMapping("/historical/{currencyCode}")
    public ResponseEntity<List<HistoricalDataPointDTO>> getHistoricalData(
            @PathVariable String currencyCode,
            @RequestParam(defaultValue = "7") int days) {
        List<HistoricalDataPointDTO> data = indicatorService.getHistoricalData(currencyCode, days);
        return ResponseEntity.ok(data);
    }
}
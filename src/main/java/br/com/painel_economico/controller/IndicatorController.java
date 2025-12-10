package br.com.painel_economico.controller;

import br.com.painel_economico.dto.HistoricalDataPoint;
import br.com.painel_economico.dto.Indicator;
import br.com.painel_economico.service.IndicatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/indicators")
@Tag(name = "Indicadores Econômicos", description = "Endpoints para cotações e índices financeiros")
public class IndicatorController {

    private final IndicatorService indicatorService;

    public IndicatorController(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    @Operation(summary = "Listar todos os indicadores", description = "Retorna cotações de moedas e índices (IBOVESPA, etc) atualizados.")
    @GetMapping("/all")
    public Mono<ResponseEntity<List<Indicator>>> getAllIndicators() {
        return indicatorService.getAllIndicators()
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Dados Históricos", description = "Retorna o histórico de variação de uma moeda nos últimos dias.")
    @GetMapping("/historical/{currencyCode}")
    public Mono<ResponseEntity<List<HistoricalDataPoint>>> getHistoricalData(
            @PathVariable String currencyCode,
            @RequestParam(defaultValue = "7") int days) {
        return indicatorService.getHistoricalData(currencyCode, days)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Conversor de Moedas", description = "Converte um valor em Reais (BRL) para a moeda alvo.")
    @GetMapping("/convert")
    public Mono<ResponseEntity<Map<String, Object>>> convertCurrency(
            @RequestParam String code,
            @RequestParam BigDecimal amount) {

        return indicatorService.calculateConversion(code, amount)
                .map(result -> {
                    Map<String, Object> response = Map.of(
                            "currency", code,
                            "amountBrl", amount,
                            "result", result);
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(IllegalArgumentException.class, e -> {
                    Map<String, Object> errorResponse = Map.of("error", e.getMessage());
                    return Mono.just(ResponseEntity.badRequest().body(errorResponse));
                });
    }
}
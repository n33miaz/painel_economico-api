package br.com.economize.controller;

import br.com.economize.dto.indicator.ForeignQuote;
import br.com.economize.dto.indicator.MacroIndicator;
import br.com.economize.dto.indicator.TreasuryBond;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.service.macro.ForeignQuoteService;
import br.com.economize.service.macro.MacroIndicatorService;
import br.com.economize.service.macro.TreasuryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Indicadores de referência para a personalização: o que interfere em
 * CDB/CDI, Tesouro Direto e ETF no exterior. Controller separado do
 * {@link IndicatorController} de propósito — aquele carrega o contrato do
 * APK publicado e a cota da Brapi; este só agrega fontes públicas sem chave.
 */
@RestController
@RequestMapping("/api/v1/indicators")
@Tag(name = "Indicadores Econômicos", description = "Endpoints para cotações e índices financeiros")
public class MarketReferenceController {

    private final MacroIndicatorService macroIndicatorService;
    private final TreasuryService treasuryService;
    private final ForeignQuoteService foreignQuoteService;

    public MarketReferenceController(MacroIndicatorService macroIndicatorService, TreasuryService treasuryService,
            ForeignQuoteService foreignQuoteService) {
        this.macroIndicatorService = macroIndicatorService;
        this.treasuryService = treasuryService;
        this.foreignQuoteService = foreignQuoteService;
    }

    @Operation(summary = "Indicadores macro (CDI, Selic, IPCA, PTAX, poupança, IGP-M)", description = """
            Os índices que mexem no rendimento do que a pessoa normalmente tem: \
            CDI e Selic (CDB, Tesouro Selic), IPCA do mês e em 12 meses (Tesouro \
            IPCA+), dólar PTAX (ETF no exterior), poupança e IGP-M. Fonte: SGS do \
            Banco Central, cache de 6h.

            Cada série falha sozinha: a que não respondeu vem do último snapshot \
            com `stale=true`, e se nunca houve snapshot ela simplesmente não \
            aparece. `referenceDate` é a data do dado (o mês, no IPCA); `asOf` é \
            quando ele foi obtido.""")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Lista de indicadores macro") })
    @GetMapping("/macro")
    public Mono<ResponseEntity<List<MacroIndicator>>> getMacroIndicators() {
        return macroIndicatorService.getMacroIndicators().map(ResponseEntity::ok);
    }

    @Operation(summary = "Títulos do Tesouro Direto", description = """
            Taxas e preços do dia de cada título (Selic, IPCA+, Prefixado, Renda+, \
            Educa+), com `indexer` em SELIC | IPCA | PREFIXADO | OTHER. Fonte: JSON \
            oficial do Tesouro quando disponível, senão o CSV do Tesouro \
            Transparente (taxas da manhã, sem aplicação mínima). Cache de 1h; \
            com as fontes fora, o snapshot do dia anterior sai com `stale=true`.""")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Lista de títulos") })
    @GetMapping("/treasury")
    public Mono<ResponseEntity<List<TreasuryBond>>> getTreasuryBonds() {
        return treasuryService.getBonds().map(ResponseEntity::ok);
    }

    @Operation(summary = "Cotação de ETF/ação estrangeira", description = """
            Preço de um papel negociado no exterior (ex.: VT), sem chave de API: \
            Yahoo Finance com fallback Stooq, cache de 30 min e snapshot. \
            `priceBrl` é o preço convertido pelo dólar do /all (nulo sem dólar). \
            Símbolo: letras, ponto e hífen, até 12 caracteres; hoje só `market=US`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cotação"),
            @ApiResponse(responseCode = "400", description = "Símbolo ou mercado inválido", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Nenhuma fonte conhece o papel", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/quote/{symbol}")
    public Mono<ResponseEntity<ForeignQuote>> getForeignQuote(
            @Parameter(description = "Ticker no mercado de origem", example = "VT") @PathVariable String symbol,
            @Parameter(description = "Mercado; hoje apenas US", example = "US") @RequestParam(defaultValue = "US") String market) {

        String normalizedSymbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (!ForeignQuoteService.SYMBOL.matcher(normalizedSymbol).matches()) {
            throw new IllegalArgumentException(
                    "Símbolo inválido: use letras, ponto e hífen, até 12 caracteres (ex.: VT, BRK.B).");
        }
        String normalizedMarket = market == null ? "" : market.trim().toUpperCase(Locale.ROOT);
        if (!ForeignQuoteService.MARKET_US.equals(normalizedMarket)) {
            throw new IllegalArgumentException("Mercado não suportado: " + market + " (disponível: US).");
        }

        return foreignQuoteService.getQuote(normalizedSymbol, normalizedMarket)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(
                        "Cotação não encontrada para " + normalizedSymbol)));
    }
}

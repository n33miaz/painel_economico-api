package br.com.economize.service.investment;

import br.com.economize.dto.investment.InvestmentRequests;
import br.com.economize.dto.investment.InvestmentResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.exception.ServiceUnavailableException;
import br.com.economize.model.InvestmentPosition;
import br.com.economize.model.InvestmentPosition.Indexer;
import br.com.economize.model.InvestmentPosition.Source;
import br.com.economize.model.InvestmentPosition.Type;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import br.com.economize.repository.InvestmentPositionRepository;
import br.com.economize.repository.PluggyItemRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.connector.pluggy.PluggyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvestmentService — sync, cadastro manual e resumo")
class InvestmentServiceTest {

    private static final String EMAIL = "teste@economize.app";

    @Mock
    private InvestmentPositionRepository positionRepository;
    @Mock
    private PluggyItemRepository pluggyItemRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private InvestmentMovementService movementService;
    @Mock
    private ObjectProvider<PluggyClient> pluggyClientProvider;
    @Mock
    private PluggyClient pluggyClient;

    private InvestmentService service;
    private User user;
    private PluggyItem inter;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(positionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(positionRepository.findByUserIdAndSourceAndProviderPositionId(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(movementService.movements(eq(user), any())).thenReturn(new InvestmentResponses.Movements(
                12, LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 6), List.of(),
                new InvestmentResponses.MovementTotals(new BigDecimal("3000"), new BigDecimal("500"),
                        new BigDecimal("42.10"), BigDecimal.ZERO),
                new BigDecimal("2500")));

        inter = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").connectorName("Banco Inter").build();
        when(pluggyClientProvider.getIfAvailable()).thenReturn(pluggyClient);
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(inter));

        service = new InvestmentService(positionRepository, pluggyItemRepository, userRepository,
                movementService, pluggyClientProvider, 3);
    }

    // ------------------------------------------------------------------ sync

    @Test
    @DisplayName("sync cria uma posição por investimento do provedor, com tipo, indexador e instituição do conector")
    void syncCriaPosicoes() {
        Map<String, Object> semId = new HashMap<>(PluggyInvestmentMapperTest.cdb());
        semId.remove("id");
        when(pluggyClient.investments("api-key", "item-1")).thenReturn(List.of(
                PluggyInvestmentMapperTest.cdb(), PluggyInvestmentMapperTest.tesouroSelic(),
                PluggyInvestmentMapperTest.etf(), semId));

        InvestmentResponses.SyncResult result = service.sync(EMAIL);

        assertThat(result.created()).isEqualTo(3);
        assertThat(result.updated()).isZero();
        assertThat(result.synced()).isEqualTo(3);
        assertThat(result.itemsRead()).isEqualTo(1);
        assertThat(result.skippedItems()).isZero();
        // sem id não há upsert possível: fica de fora, contado
        assertThat(result.skippedPositions()).isEqualTo(1);

        ArgumentCaptor<InvestmentPosition> captor = ArgumentCaptor.forClass(InvestmentPosition.class);
        verify(positionRepository, times(3)).saveAndFlush(captor.capture());
        List<InvestmentPosition> saved = captor.getAllValues();
        assertThat(saved).allSatisfy(p -> {
            assertThat(p.getSource()).isEqualTo(Source.CONNECTOR);
            assertThat(p.getUser()).isSameAs(user);
            assertThat(p.getPluggyItemId()).isEqualTo(inter.getId());
            // a instituição é o CONECTOR (onde está custodiado), não o emissor
            assertThat(p.getInstitution()).isEqualTo("Banco Inter");
            assertThat(p.getPositionDate()).isNotNull();
        });
        InvestmentPosition cdb = saved.stream().filter(p -> "inv-cdb-1".equals(p.getProviderPositionId())).findFirst().orElseThrow();
        assertThat(cdb.getType()).isEqualTo(Type.FIXED_INCOME);
        assertThat(cdb.getIndexer()).isEqualTo(Indexer.CDI);
        assertThat(cdb.getRate()).isEqualTo("110% CDI");
        assertThat(cdb.getCurrentValue()).isEqualByComparingTo("1123.45");
        InvestmentPosition tesouro = saved.stream().filter(p -> "inv-tes-1".equals(p.getProviderPositionId())).findFirst().orElseThrow();
        assertThat(tesouro.getType()).isEqualTo(Type.TREASURY);
        assertThat(tesouro.getIndexer()).isEqualTo(Indexer.SELIC);
        InvestmentPosition etf = saved.stream().filter(p -> "inv-etf-1".equals(p.getProviderPositionId())).findFirst().orElseThrow();
        assertThat(etf.getType()).isEqualTo(Type.ETF);
        assertThat(etf.getCode()).isEqualTo("VT");
        assertThat(etf.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("sync é idempotente: a posição conhecida é atualizada no lugar, nunca duplicada")
    void syncAtualizaNoLugar() {
        InvestmentPosition existente = InvestmentPosition.builder()
                .id(UUID.randomUUID()).user(user).source(Source.CONNECTOR).providerPositionId("inv-cdb-1")
                .name("CDB velho").type(Type.FIXED_INCOME)
                .currentValue(new BigDecimal("1000")).positionDate(LocalDate.of(2026, 8, 1))
                .build();
        when(positionRepository.findByUserIdAndSourceAndProviderPositionId(user.getId(), Source.CONNECTOR, "inv-cdb-1"))
                .thenReturn(Optional.of(existente));
        when(pluggyClient.investments("api-key", "item-1")).thenReturn(List.of(PluggyInvestmentMapperTest.cdb()));

        InvestmentResponses.SyncResult result = service.sync(EMAIL);

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        verify(positionRepository, never()).saveAndFlush(any());
        verify(positionRepository).save(existente);
        // o retrato do provedor substitui o antigo, e a data avança
        assertThat(existente.getName()).isEqualTo("CDB Banco Inter 110% CDI");
        assertThat(existente.getCurrentValue()).isEqualByComparingTo("1123.45");
        assertThat(existente.getPositionDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("conexão sem investimento não gera nada e conta como lida; posição que sumiu NÃO é apagada")
    void syncItemSemInvestimento() {
        when(pluggyClient.investments("api-key", "item-1")).thenReturn(List.of());

        InvestmentResponses.SyncResult result = service.sync(EMAIL);

        assertThat(result.synced()).isZero();
        assertThat(result.itemsRead()).isEqualTo(1);
        verify(positionRepository, never()).delete(any());
        verify(positionRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("uma conexão recusada pelo provedor é pulada e as outras seguem; todas recusadas vira erro de provedor")
    void syncTolerantAFalhaDeUmaConexao() {
        PluggyItem nubank = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-2").connectorName("Nubank").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(inter, nubank));
        WebClientResponseException recusa = WebClientResponseException.create(
                401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);
        when(pluggyClient.investments("api-key", "item-1")).thenThrow(recusa);
        when(pluggyClient.investments("api-key", "item-2")).thenReturn(List.of(PluggyInvestmentMapperTest.etf()));

        InvestmentResponses.SyncResult result = service.sync(EMAIL);
        assertThat(result.itemsRead()).isEqualTo(1);
        assertThat(result.skippedItems()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);

        when(pluggyClient.investments("api-key", "item-2")).thenThrow(recusa);
        assertThatThrownBy(() -> service.sync(EMAIL)).isSameAs(recusa);
    }

    @Test
    @DisplayName("conector desligado é 503 — pedido válido que esta instalação não atende")
    void syncSemConectorE503() {
        when(pluggyClientProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.sync(EMAIL))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("PLUGGY_ENABLED");
    }

    @Test
    @DisplayName("sem credenciais ou sem conexão registrada é 400 com orientação")
    void syncSemCredenciaisOuItens() {
        when(pluggyClient.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.sync(EMAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLUGGY_CLIENT_ID");

        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of());
        assertThatThrownBy(() -> service.sync(EMAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nenhuma conexão");
    }

    // -------------------------------------------------------------- manual

    @Test
    @DisplayName("cadastro manual normaliza código e moeda, nasce MANUAL e sem valor atual declara needsQuote")
    void criaPosicaoManual() {
        InvestmentResponses.PositionItem item = service.create(EMAIL, new InvestmentRequests.CreatePosition(
                "  Vanguard Total World  ", "etf", null, "usd", null, "vt", "Avenue", null,
                new BigDecimal("12"), null, new BigDecimal("6000"), null, null, null));

        assertThat(item.source()).isEqualTo("MANUAL");
        assertThat(item.name()).isEqualTo("Vanguard Total World");
        assertThat(item.type()).isEqualTo("ETF");
        assertThat(item.typeLabel()).isEqualTo("ETFs");
        assertThat(item.indexer()).isEqualTo("USD");
        assertThat(item.code()).isEqualTo("VT");
        assertThat(item.currency()).isEqualTo("BRL");
        assertThat(item.currentValue()).isNull();
        assertThat(item.profit()).isNull();
        assertThat(item.needsQuote()).isTrue();
        assertThat(item.editable()).isTrue();
        assertThat(item.stale()).isFalse();
    }

    @Test
    @DisplayName("tipo, indexador e moeda inválidos respondem 400 com a lista aceita; teto de manuais também é 400")
    void validaCadastroManual() {
        assertThatThrownBy(() -> service.create(EMAIL, request("X", "acao_da_bolsa", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FIXED_INCOME");
        assertThatThrownBy(() -> service.create(EMAIL, request("X", "ETF", "dolar", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CDI");
        assertThatThrownBy(() -> service.create(EMAIL, request("X", "ETF", null, "R$")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ISO");
        verify(positionRepository, never()).save(any());

        when(positionRepository.countByUserIdAndSource(user.getId(), Source.MANUAL)).thenReturn(3L);
        assertThatThrownBy(() -> service.create(EMAIL, request("X", "ETF", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Limite de 3");
    }

    @Test
    @DisplayName("PATCH altera só o que veio na posição manual; posição do conector não se edita; id alheio é 404")
    void atualizaPosicao() {
        InvestmentPosition manual = InvestmentPosition.builder()
                .id(UUID.randomUUID()).user(user).source(Source.MANUAL).name("VT").type(Type.ETF)
                .code("VT").currency("USD").quantity(new BigDecimal("10")).build();
        when(positionRepository.findByIdAndUserId(manual.getId(), user.getId())).thenReturn(Optional.of(manual));

        InvestmentResponses.PositionItem item = service.update(EMAIL, manual.getId(), new InvestmentRequests.UpdatePosition(
                null, null, null, null, null, null, null, null,
                new BigDecimal("15"), null, new BigDecimal("1500"), new BigDecimal("1800"), null, null));
        assertThat(item.quantity()).isEqualByComparingTo("15");
        assertThat(item.name()).isEqualTo("VT");
        assertThat(item.currency()).isEqualTo("USD");
        assertThat(item.profit()).isEqualByComparingTo("300");
        assertThat(item.needsQuote()).isFalse();

        InvestmentPosition doConector = InvestmentPosition.builder()
                .id(UUID.randomUUID()).user(user).source(Source.CONNECTOR).name("CDB").type(Type.FIXED_INCOME).build();
        when(positionRepository.findByIdAndUserId(doConector.getId(), user.getId())).thenReturn(Optional.of(doConector));
        assertThatThrownBy(() -> service.update(EMAIL, doConector.getId(), new InvestmentRequests.UpdatePosition(
                "outro", null, null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conector");

        UUID alheio = UUID.randomUUID();
        when(positionRepository.findByIdAndUserId(alheio, user.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(EMAIL, alheio, new InvestmentRequests.UpdatePosition(
                "outro", null, null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(EMAIL, alheio)).isInstanceOf(ResourceNotFoundException.class);
        verify(positionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("DELETE remove posição de qualquer origem — é como o usuário descarta a que sumiu do provedor")
    void removePosicaoDoConector() {
        InvestmentPosition velha = InvestmentPosition.builder()
                .id(UUID.randomUUID()).user(user).source(Source.CONNECTOR).name("CDB").type(Type.FIXED_INCOME).build();
        when(positionRepository.findByIdAndUserId(velha.getId(), user.getId())).thenReturn(Optional.of(velha));

        service.delete(EMAIL, velha.getId());

        verify(positionRepository).delete(velha);
    }

    // -------------------------------------------------------------- resumo

    @Test
    @DisplayName("resumo soma só o que é conhecido, declara needsQuote e marca a posição desatualizada")
    void resumo() {
        LocalDate hoje = LocalDate.now(ZoneOffset.UTC);
        OffsetDateTime agora = OffsetDateTime.now(ZoneOffset.UTC);
        InvestmentPosition cdb = InvestmentPosition.builder()
                .id(UUID.randomUUID()).user(user).source(Source.CONNECTOR).name("CDB Inter").type(Type.FIXED_INCOME)
                .indexer(Indexer.CDI).institution("Banco Inter").currency("BRL")
                .investedAmount(new BigDecimal("1000")).currentValue(new BigDecimal("1100"))
                .positionDate(hoje.minusDays(30)).updatedAt(agora.minusDays(30)).build();
        InvestmentPosition tesouro = InvestmentPosition.builder()
                .id(UUID.randomUUID()).user(user).source(Source.CONNECTOR).name("Tesouro Selic").type(Type.TREASURY)
                .indexer(Indexer.SELIC).institution("Banco Inter").currency("BRL")
                .investedAmount(new BigDecimal("2000")).currentValue(new BigDecimal("2100"))
                .positionDate(hoje).updatedAt(agora).build();
        InvestmentPosition vt = InvestmentPosition.builder()
                .id(UUID.randomUUID()).user(user).source(Source.MANUAL).name("Vanguard Total World").type(Type.ETF)
                .code("VT").indexer(Indexer.USD).institution("Avenue").currency("USD")
                .investedAmount(new BigDecimal("5000")).quantity(new BigDecimal("10"))
                .updatedAt(agora.minusDays(2)).build();
        when(positionRepository.findAllByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of(cdb, tesouro, vt));

        InvestmentResponses.Summary s = service.summary(EMAIL);

        assertThat(s.positionsCount()).isEqualTo(3);
        assertThat(s.pricedPositions()).isEqualTo(2);
        // o aplicado soma TUDO; o valor atual e o lucro só onde há cotação —
        // somar os 5000 da ETF sem valor faria o total parecer prejuízo
        assertThat(s.totalInvested()).isEqualByComparingTo("8000");
        assertThat(s.currentValue()).isEqualByComparingTo("3200");
        assertThat(s.profit()).isEqualByComparingTo("200");
        assertThat(s.profitPercent()).isEqualByComparingTo("6.67");
        assertThat(s.needsQuote()).containsExactly("VT");
        assertThat(s.sources()).containsExactly("CONNECTOR", "MANUAL");
        assertThat(s.updatedAt()).isEqualTo(agora);
        // CDB com posição de 30 dias atrás está desatualizado; a manual nunca
        assertThat(s.stalePositions()).isEqualTo(1);

        assertThat(s.byType()).hasSize(2);
        assertThat(s.byType().get(0).type()).isEqualTo("TREASURY");
        assertThat(s.byType().get(0).label()).isEqualTo("Tesouro Direto");
        assertThat(s.byType().get(0).share()).isEqualByComparingTo("0.6563");
        assertThat(s.byType().get(1).share()).isEqualByComparingTo("0.3438");
        assertThat(s.byInstitution()).hasSize(1);
        assertThat(s.byInstitution().get(0).institution()).isEqualTo("Banco Inter");
        assertThat(s.byInstitution().get(0).share()).isEqualByComparingTo("1.0000");
        assertThat(s.byIndexer()).extracting(InvestmentResponses.IndexerSlice::indexer).containsExactly("SELIC", "CDI");

        assertThat(s.movements12m().applied()).isEqualByComparingTo("3000");
        assertThat(s.movements12m().redeemed()).isEqualByComparingTo("500");
        assertThat(s.movements12m().yield()).isEqualByComparingTo("42.10");
        assertThat(s.movements12m().net()).isEqualByComparingTo("2500");
        verify(movementService).movements(user, 12);
    }

    @Test
    @DisplayName("sem posição nenhuma o resumo é zero, sem lucro e sem fatias — nunca erro")
    void resumoVazio() {
        when(positionRepository.findAllByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of());

        InvestmentResponses.Summary s = service.summary(EMAIL);

        assertThat(s.positionsCount()).isZero();
        assertThat(s.totalInvested()).isEqualByComparingTo("0");
        assertThat(s.currentValue()).isEqualByComparingTo("0");
        assertThat(s.profit()).isNull();
        assertThat(s.profitPercent()).isNull();
        assertThat(s.byType()).isEmpty();
        assertThat(s.needsQuote()).isEmpty();
        assertThat(s.updatedAt()).isNull();
    }

    @Test
    @DisplayName("a listagem marca stale só na posição do conector sem data recente")
    void listagemMarcaStale() {
        LocalDate hoje = LocalDate.now(ZoneOffset.UTC);
        InvestmentPosition semData = InvestmentPosition.builder()
                .id(UUID.randomUUID()).user(user).source(Source.CONNECTOR).name("A").type(Type.OTHER).build();
        InvestmentPosition recente = InvestmentPosition.builder()
                .id(UUID.randomUUID()).user(user).source(Source.CONNECTOR).name("B").type(Type.OTHER)
                .positionDate(hoje.minusDays(3)).build();
        InvestmentPosition manualVelha = InvestmentPosition.builder()
                .id(UUID.randomUUID()).user(user).source(Source.MANUAL).name("C").type(Type.OTHER)
                .positionDate(hoje.minusYears(1)).build();
        when(positionRepository.findAllByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of(semData, recente, manualVelha));

        List<InvestmentResponses.PositionItem> items = service.list(EMAIL);

        assertThat(items).extracting(InvestmentResponses.PositionItem::stale).containsExactly(true, false, false);
        assertThat(items).extracting(InvestmentResponses.PositionItem::editable).containsExactly(false, false, true);
    }

    private static InvestmentRequests.CreatePosition request(String name, String type, String indexer, String currency) {
        return new InvestmentRequests.CreatePosition(name, type, null, indexer, null, null, null, currency,
                null, null, null, null, null, null);
    }
}

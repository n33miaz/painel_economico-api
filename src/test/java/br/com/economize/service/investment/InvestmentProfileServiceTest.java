package br.com.economize.service.investment;

import br.com.economize.dto.investment.InvestmentRequests;
import br.com.economize.dto.investment.InvestmentResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.InvestmentInterest;
import br.com.economize.model.InvestmentPosition;
import br.com.economize.model.InvestmentPosition.Indexer;
import br.com.economize.model.InvestmentPosition.Source;
import br.com.economize.model.InvestmentPosition.Type;
import br.com.economize.model.User;
import br.com.economize.repository.InvestmentInterestRepository;
import br.com.economize.repository.InvestmentPositionRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvestmentProfileService — a personalização derivada do que o usuário tem")
class InvestmentProfileServiceTest {

    private static final String EMAIL = "teste@economize.app";

    @Mock
    private InvestmentPositionRepository positionRepository;
    @Mock
    private InvestmentInterestRepository interestRepository;
    @Mock
    private InvestmentMovementService movementService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private InvestmentProfileService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(interestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(interestRepository.findByUserIdAndKindAndCode(any(), any(), any())).thenReturn(Optional.empty());
        when(positionRepository.findAllByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of());
        when(interestRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of());
        when(movementService.normalizedDescriptions(eq(user), eq(12))).thenReturn(List.of());
    }

    private static InvestmentPosition posicao(Type type, String nome, String code, Indexer indexer, String currency,
                                              String subtype, String institution) {
        return InvestmentPosition.builder()
                .id(UUID.randomUUID()).source(Source.MANUAL).type(type).name(nome).code(code)
                .indexer(indexer).currency(currency).subtype(subtype).institution(institution).build();
    }

    private static InvestmentInterest interesse(InvestmentInterest.Kind kind, String code, String market) {
        return InvestmentInterest.builder().id(UUID.randomUUID()).kind(kind).code(code).market(market).build();
    }

    @Test
    @DisplayName("o caso do dono: CDB, Tesouro e a ETF VT derivam CDI, SELIC, IPCA, USD, o ticker e os tópicos")
    void derivaDoCasoDoDono() {
        List<InvestmentPosition> posicoes = List.of(
                posicao(Type.FIXED_INCOME, "CDB Inter 110% CDI", null, Indexer.CDI, "BRL", "CDB", "Banco Inter"),
                posicao(Type.TREASURY, "Tesouro IPCA+ 2035", null, Indexer.IPCA, "BRL", "TESOURO_IPCA", "Banco Inter"),
                posicao(Type.ETF, "Vanguard Total World", "VT", Indexer.USD, "USD", "ETF", "Avenue"));

        InvestmentResponses.Profile p = service.derive(posicoes, List.of(), List.of());

        assertThat(p.isDefault()).isFalse();
        assertThat(p.indexers()).containsExactly("CDI", "SELIC", "IPCA", "USD");
        assertThat(p.watch()).extracting(InvestmentResponses.WatchItem::kind, InvestmentResponses.WatchItem::code,
                        InvestmentResponses.WatchItem::market, InvestmentResponses.WatchItem::source)
                .containsExactly(
                        tuple("RATE", "CDI", null, "DERIVED"),
                        tuple("RATE", "SELIC", null, "DERIVED"),
                        tuple("INDEX", "IPCA", null, "DERIVED"),
                        tuple("CURRENCY", "USD", null, "DERIVED"),
                        tuple("TICKER", "VT", "US", "DERIVED"));
        // só ids do vocabulário fixo, macro-br sempre, nenhum tópico inventado
        assertThat(p.topics()).containsExactly(
                "renda-fixa", "selic-cdi", "tesouro", "inflacao", "etf-exterior", "cambio", "macro-global", "macro-br");
        assertThat(p.topics()).allMatch(InvestmentProfileService.TOPIC_VOCABULARY::contains);
        assertThat(p.derivedFrom().positions()).hasSize(3);
        assertThat(p.derivedFrom().positions().get(0)).startsWith("CDB Inter 110% CDI (Banco Inter) → CDI");
        assertThat(p.derivedFrom().movements()).isEmpty();
        assertThat(p.derivedFrom().manualInterests()).isEmpty();
        assertThat(p.derivedFrom().note()).isNull();
    }

    @Test
    @DisplayName("sem posição, o extrato sozinho deriva: CDB e Tesouro no texto viram CDI, SELIC e IPCA")
    void derivaDosMovimentos() {
        InvestmentResponses.Profile p = service.derive(List.of(),
                List.of("aplicacao cdb inter", "rendimentos", "tesouro selic compra", "cdb mercado pago"),
                List.of());

        assertThat(p.isDefault()).isFalse();
        assertThat(p.indexers()).containsExactly("CDI", "SELIC", "IPCA");
        assertThat(p.topics()).containsExactly("renda-fixa", "selic-cdi", "tesouro", "inflacao", "macro-br");
        assertThat(p.derivedFrom().movements()).hasSize(2);
        assertThat(p.derivedFrom().movements().get(0)).startsWith("2 movimentos de renda fixa");
        assertThat(p.derivedFrom().movements().get(1)).startsWith("1 movimento de Tesouro Direto");
    }

    @Test
    @DisplayName("sem nada, o perfil é o padrão — e diz por quê")
    void perfilPadrao() {
        InvestmentResponses.Profile p = service.derive(List.of(), List.of(), List.of());

        assertThat(p.isDefault()).isTrue();
        assertThat(p.topics()).containsExactly("macro-br", "financas-pessoais", "selic-cdi");
        assertThat(p.indexers()).isEmpty();
        assertThat(p.watch()).isEmpty();
        assertThat(p.derivedFrom().note()).contains("perfil padrão");
    }

    @Test
    @DisplayName("interesse manual entra, aparece como MANUAL e tira o perfil do padrão")
    void interesseManualEntra() {
        List<InvestmentInterest> manuais = List.of(
                interesse(InvestmentInterest.Kind.CURRENCY, "USD", null),
                interesse(InvestmentInterest.Kind.TOPIC, "cripto", null),
                interesse(InvestmentInterest.Kind.TICKER, "PETR4", "BR"));

        InvestmentResponses.Profile p = service.derive(List.of(), List.of(), manuais);

        assertThat(p.isDefault()).isFalse();
        assertThat(p.indexers()).containsExactly("USD");
        assertThat(p.watch()).extracting(InvestmentResponses.WatchItem::code, InvestmentResponses.WatchItem::source)
                .containsExactly(tuple("USD", "MANUAL"), tuple("PETR4", "MANUAL"));
        assertThat(p.topics()).containsExactly("cripto", "macro-br");
        assertThat(p.derivedFrom().manualInterests()).hasSize(3);
    }

    @Test
    @DisplayName("quando o mesmo item é derivado E declarado, a resposta o mostra como MANUAL — é o que se pode remover")
    void manualPrevaleceSobreDerivado() {
        InvestmentResponses.Profile p = service.derive(
                List.of(posicao(Type.FIXED_INCOME, "CDB", null, Indexer.CDI, "BRL", "CDB", null)),
                List.of(),
                List.of(interesse(InvestmentInterest.Kind.RATE, "CDI", null)));

        assertThat(p.watch()).hasSize(1);
        assertThat(p.watch().get(0).source()).isEqualTo("MANUAL");
        assertThat(p.indexers()).containsExactly("CDI");
    }

    @Test
    @DisplayName("FII, cripto, previdência e ação brasileira derivam os tópicos próprios")
    void outrosTipos() {
        InvestmentResponses.Profile p = service.derive(List.of(
                        posicao(Type.FUND, "HGLG11", "HGLG11", null, "BRL", "FII", "XP"),
                        posicao(Type.CRYPTO, "Bitcoin", "BTC", Indexer.NONE, "BRL", null, "Binance"),
                        posicao(Type.PENSION, "VGBL", null, null, "BRL", "RETIREMENT", "Brasilprev"),
                        posicao(Type.EQUITY, "Petrobras", "PETR4", Indexer.NONE, "BRL", "STOCK", "Clear")),
                List.of(), List.of());

        assertThat(p.topics()).containsExactly("fiis", "cripto", "previdencia", "bolsa", "macro-br");
        assertThat(p.indexers()).isEmpty();
        assertThat(p.watch()).extracting(InvestmentResponses.WatchItem::code, InvestmentResponses.WatchItem::market)
                .containsExactly(tuple("BTC", "CRYPTO"), tuple("PETR4", "BR"));
    }

    @Test
    @DisplayName("profile(email) lê posições, interesses e 12 meses de movimentos do usuário do token")
    void profileLeDoUsuario() {
        when(positionRepository.findAllByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of(
                posicao(Type.TREASURY, "Tesouro Selic 2029", null, Indexer.SELIC, "BRL", "TESOURO_SELIC", "Inter")));

        InvestmentResponses.Profile p = service.profile(EMAIL);

        assertThat(p.indexers()).contains("SELIC", "IPCA");
        verify(movementService).normalizedDescriptions(user, 12);
    }

    // ---------------------------------------------------------- interesses

    @Test
    @DisplayName("declarar interesse normaliza tipo, código e mercado para maiúsculas")
    void declaraInteresse() {
        InvestmentResponses.InterestItem item = service.addInterest(EMAIL,
                new InvestmentRequests.CreateInterest("ticker", "vt", "us"));

        assertThat(item.kind()).isEqualTo("TICKER");
        assertThat(item.code()).isEqualTo("VT");
        assertThat(item.market()).isEqualTo("US");
    }

    @Test
    @DisplayName("tópico é slug minúsculo e precisa existir no vocabulário; mercado só vale para TICKER")
    void declaraTopico() {
        InvestmentResponses.InterestItem item = service.addInterest(EMAIL,
                new InvestmentRequests.CreateInterest("TOPIC", "ETF-Exterior", "US"));

        assertThat(item.code()).isEqualTo("etf-exterior");
        assertThat(item.market()).isNull();

        assertThatThrownBy(() -> service.addInterest(EMAIL, new InvestmentRequests.CreateInterest("TOPIC", "memes", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selic-cdi");
        assertThatThrownBy(() -> service.addInterest(EMAIL, new InvestmentRequests.CreateInterest("HOBBY", "USD", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RATE, INDEX");
        assertThatThrownBy(() -> service.addInterest(EMAIL, new InvestmentRequests.CreateInterest("RATE", "C D I", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Código inválido");
    }

    @Test
    @DisplayName("declarar o mesmo interesse duas vezes devolve o existente sem gravar de novo")
    void declararEIdempotente() {
        InvestmentInterest existente = interesse(InvestmentInterest.Kind.CURRENCY, "USD", null);
        when(interestRepository.findByUserIdAndKindAndCode(user.getId(), InvestmentInterest.Kind.CURRENCY, "USD"))
                .thenReturn(Optional.of(existente));

        InvestmentResponses.InterestItem item = service.addInterest(EMAIL,
                new InvestmentRequests.CreateInterest("currency", "usd", null));

        assertThat(item.id()).isEqualTo(existente.getId());
        verify(interestRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("remover interesse inexistente para este usuário é 404; existente é apagado pela chave normalizada")
    void removeInteresse() {
        assertThatThrownBy(() -> service.removeInterest(EMAIL, "RATE", "CDI"))
                .isInstanceOf(ResourceNotFoundException.class);

        InvestmentInterest existente = interesse(InvestmentInterest.Kind.TOPIC, "cripto", null);
        when(interestRepository.findByUserIdAndKindAndCode(user.getId(), InvestmentInterest.Kind.TOPIC, "cripto"))
                .thenReturn(Optional.of(existente));

        service.removeInterest(EMAIL, "topic", "CRIPTO");

        verify(interestRepository).delete(existente);
    }
}

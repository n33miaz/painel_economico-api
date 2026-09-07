package br.com.economize.service.connector;

import br.com.economize.dto.connector.ConnectionResponse;
import br.com.economize.service.BankStatementService;

import java.util.List;
import java.util.UUID;

/**
 * A porta do conector bancário (Open Finance), sem o nome de quem está por trás.
 *
 * <p>O pedido do dono foi direto: em nenhum lugar do app deve aparecer
 * "Pluggy" — o usuário só vê que pode conectar o banco — e precisamos poder
 * trocar de agregador sem publicar APK novo. Esta interface é o que o
 * {@code ConnectorController} neutro enxerga; a implementação atual delega ao
 * Pluggy, e uma segunda implementação (outro agregador, ou o Open Finance
 * direto) entra sem tocar em controller nem em app.
 *
 * <p>O que a interface NÃO esconde: o {@code itemId} é a referência da conexão
 * no provedor, e o app precisa devolvê-lo depois que o widget conclui — é
 * opaco para ele, e por isso pode continuar se chamando assim. O widget em si
 * também é do provedor: {@link #widget()} diz ao site qual script carregar,
 * para a página-ponte não ter URL fixa de ninguém.
 */
public interface OpenFinanceProvider {

    /** Identificador técnico da implementação ("pluggy"); nunca é exibido. */
    String id();

    /** Nome que o app pode mostrar: "Open Finance", não o do agregador. */
    String displayName();

    /**
     * Existe um provedor de verdade nesta instalação? Falso na implementação
     * vazia que entra quando o conector está desligado — o app esconde a seção.
     */
    boolean enabled();

    ProviderStatus status(String email);

    /** Token de curta duração que abre o widget; com itemId, em modo atualização. */
    String connectToken(String email, String itemId);

    /** Registra a conexão que o widget criou, depois de validar o dono. */
    ConnectionResponse registerItem(String email, String itemId);

    List<ConnectionResponse> listItems(String email);

    void unlinkItem(String email, UUID id);

    SyncResult sync(String email, int days);

    /** Como abrir o widget deste provedor; nulo quando não há provedor. */
    WidgetDescriptor widget();

    /**
     * {@code configured} significa "este usuário consegue sincronizar agora":
     * credenciais da aplicação presentes E pelo menos uma conexão dele.
     */
    record ProviderStatus(boolean enabled, boolean configured, long itemCount) {
    }

    /**
     * O script do widget e o "tipo" dele, para a página-ponte do site saber
     * qual integração montar. {@code kind} é um rótulo estável por provedor
     * ("pluggy-connect"); {@code scriptUrl} pode mudar de versão por property.
     */
    record WidgetDescriptor(String scriptUrl, String kind) {
    }

    /** Resultado da importação + quantas conexões foram percorridas. */
    record SyncResult(BankStatementService.ImportResult result, int itemsSynced) {
    }
}

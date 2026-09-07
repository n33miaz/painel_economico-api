package br.com.economize.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * O último snapshot bom de uma chave de mercado, persistido — ver V25.
 *
 * <p>É a camada de baixo do {@code MarketSnapshotStore}: a de cima (memória)
 * morre a cada reinício do container, e no plano free o Render reinicia com
 * frequência. Esta linha é o que permite à Home mostrar um preço marcado como
 * velho, em vez de esqueleto, no primeiro minuto depois de um deploy.
 */
@Entity
@Table(name = "market_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MarketSnapshot {

    /**
     * Entre aspas porque {@code KEY} é palavra reservada no H2 dos testes
     * (no Postgres é permitida sem aspas, e a V25 a cria em minúsculas — a
     * forma citada casa com ela).
     */
    @Id
    @Column(name = "\"key\"", nullable = false, length = 80)
    private String key;

    /** O payload em JSON, no formato que o próprio ObjectMapper do Spring gera. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "saved_at", nullable = false)
    private OffsetDateTime savedAt;

    /** Fonte verdadeira do payload: "AwesomeAPI", "Frankfurter (BCE)+CoinGecko"... */
    @Column(length = 40)
    private String source;
}

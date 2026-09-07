package br.com.economize.service;

import br.com.economize.dto.account.CardInvoicesResponse;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.InvoiceReserve;
import br.com.economize.model.User;
import br.com.economize.repository.InvoiceReserveRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reserva de fatura — EC-181: o dinheiro que o dono já separou para pagar um
 * cartão e que, por isso, não deveria ser lido como saldo disponível.
 *
 * <p><b>Por que não vira lançamento.</b> Registrar a reserva como um débito na
 * conta falsificaria o extrato — o dinheiro não saiu — e, quando a fatura fosse
 * paga de verdade, haveria dois débitos: o inventado e o real. A reserva é uma
 * INTENÇÃO, e intenção se desfaz sem deixar rastro contábil.
 *
 * <p>Uma reserva por ciclo (cartão + referência): o PUT é upsert justamente
 * porque corrigir o valor separado é a operação mais comum — a fatura em aberto
 * cresce até fechar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceReserveService {

    private final InvoiceReserveRepository repository;
    private final ConnectorAccountService accountService;
    private final UserRepository userRepository;

    /**
     * Cria ou atualiza a reserva do ciclo. Devolve o que a tela precisa mostrar
     * logo em seguida, já com o nome da conta que guarda o valor.
     */
    @Transactional
    public CardInvoicesResponse.Reserve save(String email, UUID cardAccountId, String reference,
                                             BigDecimal amount, UUID heldInAccountId, String note) {
        User user = requireUser(email);
        ConnectorAccount card = accountService.requireOwned(cardAccountId, user.getId());
        if (!card.isCreditCard()) {
            // mesma decisão do CardInvoiceService: 400 e não 404 — a conta existe
            // e é do usuário, e conta corrente simplesmente não tem fatura
            throw new IllegalArgumentException(
                    "Esta conta não é um cartão de crédito — reserva de fatura só existe para cartão");
        }
        String normalizada = requireReference(reference);
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("O valor reservado precisa ser maior que zero");
        }

        // A conta que guarda o dinheiro é opcional, mas quando vem tem de ser do
        // mesmo dono: sem esta checagem daria para apontar a reserva para a conta
        // de outra pessoa e ler o nome dela na resposta
        ConnectorAccount cofre = heldInAccountId == null
                ? null
                : accountService.requireOwned(heldInAccountId, user.getId());

        InvoiceReserve reserva = repository
                .findByUserIdAndCardAccountIdAndReference(user.getId(), card.getId(), normalizada)
                .orElseGet(() -> InvoiceReserve.builder()
                        .user(user)
                        .cardAccount(card)
                        .reference(normalizada)
                        .build());
        reserva.setAmount(amount);
        reserva.setHeldInAccount(cofre);
        reserva.setNote(note != null && note.isBlank() ? null : note);
        InvoiceReserve salva = repository.save(reserva);
        log.info("Reserva de fatura gravada: cartão={} ciclo={} valor={}",
                card.getId(), normalizada, amount);
        return toResponse(salva);
    }

    /** Remove a reserva do ciclo. Idempotente: apagar o que não existe é sucesso. */
    @Transactional
    public void delete(String email, UUID cardAccountId, String reference) {
        User user = requireUser(email);
        ConnectorAccount card = accountService.requireOwned(cardAccountId, user.getId());
        repository.findByUserIdAndCardAccountIdAndReference(
                        user.getId(), card.getId(), requireReference(reference))
                .ifPresent(repository::delete);
    }

    /**
     * As reservas de um cartão indexadas pela referência do ciclo — é assim que
     * o {@link CardInvoiceService} anexa cada uma à sua fatura sem uma consulta
     * por ciclo.
     */
    @Transactional(readOnly = true)
    public Map<String, CardInvoicesResponse.Reserve> byReference(UUID userId, UUID cardAccountId) {
        return repository.findAllByUserIdAndCardAccountId(userId, cardAccountId).stream()
                .collect(Collectors.toMap(InvoiceReserve::getReference, InvoiceReserveService::toResponse,
                        // o UNIQUE (cartão, ciclo) impede a colisão no banco; o
                        // merge existe só para o Collectors não explodir se um dia
                        // a constraint for afrouxada
                        (a, b) -> b));
    }

    /**
     * Total separado pelo usuário, somando todos os cartões e ciclos. É o número
     * que a previsão de saldo desconta do disponível.
     */
    @Transactional(readOnly = true)
    public BigDecimal totalReserved(UUID userId) {
        return repository.findAllByUserId(userId).stream()
                .map(InvoiceReserve::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static CardInvoicesResponse.Reserve toResponse(InvoiceReserve reserva) {
        ConnectorAccount cofre = reserva.getHeldInAccount();
        return new CardInvoicesResponse.Reserve(
                reserva.getId(),
                reserva.getAmount(),
                cofre == null ? null : cofre.getId(),
                cofre == null ? null : cofre.getName(),
                reserva.getNote());
    }

    /**
     * A referência é o mês em que a fatura fecha, no mesmo formato que a resposta
     * de faturas devolve. Validar aqui evita a reserva órfã: "2026-9" e "set/26"
     * gravariam sem erro e nunca casariam com ciclo nenhum, e o dono veria a
     * fatura sem cobertura sem entender por quê.
     */
    private String requireReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Informe o ciclo da fatura, no formato AAAA-MM");
        }
        try {
            return YearMonth.parse(reference.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Ciclo inválido: use o formato AAAA-MM (recebido: " + reference + ")");
        }
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }
}

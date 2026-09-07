package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Dinheiro do titular trocando de bolso — e como dizer isso ao app.
 *
 * <p>O problema que este serviço resolve foi medido no extrato real: em agosto,
 * a renda da casa apareceu <b>R$ 2.191 acima</b> da renda de verdade do casal, e
 * a diferença inteira era dinheiro andando entre contas da mesma pessoa. Um Pix
 * que sai do Inter e chega no Mercado Pago é UM movimento; importados os dois
 * extratos, ele conta como uma despesa e uma receita que nunca existiram.
 *
 * <p>Até aqui, {@code internal_transfer} só era marcado na importação, e só para
 * a perna de cartão que o conector sabia identificar (V15/EC-106). Não havia
 * detecção de "Pix meu para mim mesmo" nem jeito de o usuário dizer
 * "essa linha é minha" — a recomendação de produto Q9 do registro 59 pedia
 * exatamente este botão, e ele nunca foi feito.
 *
 * <p><b>O sinal usado é o nome do próprio titular na descrição</b>, e é forte por
 * construção: o extrato escreve a contraparte do Pix, então "Pix recebido -
 * Neemias Cormino Manso" na conta do Neemias é ele pagando a si mesmo. Exige o
 * nome com <b>pelo menos dois tokens</b> (nome + sobrenome): um "Ana" solto
 * casaria com qualquer Ana do país.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalTransferService {

    /**
     * Só descrição de transferência entra na varredura. Uma compra num
     * estabelecimento que por acaso se chame como o titular não é movimentação
     * entre contas dele — e "MERCADO SILVA" existe.
     */
    private static final Pattern TRANSFER_LIKE = Pattern.compile(
            "\\b(pix|ted|doc|transferencia|transferido|deposito)\\b");

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9 ]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /**
     * Partículas que não identificam ninguém: exigir "de", "dos" e "da" no meio
     * faria "Neemias Manso" (como o banco às vezes abrevia) deixar de casar.
     */
    private static final Set<String> PARTICLES = Set.of("de", "da", "do", "das", "dos", "e");

    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    /**
     * Marca (ou desmarca) UMA linha como movimentação entre contas do titular.
     * É a decisão do usuário, e por isso vence qualquer heurística: nada aqui
     * volta atrás sozinho depois.
     */
    public BankTransaction setInternal(String email, UUID transactionId, boolean internal) {
        User user = requireUser(email);
        BankTransaction tx = bankTransactionRepository
                .findByIdAndUserId(transactionId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Transação não encontrada"));
        tx.setInternalTransfer(internal);
        return bankTransactionRepository.save(tx);
    }

    /**
     * Varre o histórico e marca as transferências em que a contraparte é o
     * próprio titular.
     *
     * <p>Não desmarca nada: linha que já está marcada (pela importação ou pela
     * mão do usuário) fica como está. Assim rodar duas vezes é seguro, e uma
     * decisão manual nunca é desfeita por esta varredura.
     */
    public Outcome reconcileByOwnName(String email) {
        User user = requireUser(email);
        List<String> tokens = nameTokens(user.getName());
        if (tokens.size() < 2) {
            // Sem nome completo no cadastro não há sinal: responder "zero" é
            // honesto, inventar um casamento por primeiro nome não é
            log.info("Varredura de movimentação própria sem nome completo, user={}", email);
            return new Outcome(0, 0, false);
        }

        List<BankTransaction> all = bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId());
        Set<UUID> toMark = new LinkedHashSet<>();
        for (BankTransaction tx : all) {
            if (tx.isInternalTransfer()) continue;
            String normalized = normalize(tx.getDescription());
            if (normalized.isEmpty()) continue;
            if (!TRANSFER_LIKE.matcher(normalized).find()) continue;
            if (!carriesOwnName(normalized, tokens)) continue;
            toMark.add(tx.getId());
        }

        if (!toMark.isEmpty()) {
            bankTransactionRepository.markAsInternalTransfer(user.getId(), toMark);
        }
        log.info("Varredura de movimentação própria: {} de {} lançamento(s) marcados, user={}",
                toMark.size(), all.size(), email);
        return new Outcome(all.size(), toMark.size(), true);
    }

    /**
     * Todos os tokens do nome têm de estar presentes, cada um como palavra
     * inteira. Basta um faltar para não ser o titular: "Neemias Cormino Manso"
     * não casa com "Neemias Cormino Souza".
     */
    private boolean carriesOwnName(String normalized, List<String> tokens) {
        String padded = " " + normalized + " ";
        for (String token : tokens) {
            if (!padded.contains(" " + token + " ")) return false;
        }
        return true;
    }

    private List<String> nameTokens(String name) {
        List<String> tokens = new ArrayList<>();
        if (name == null) return tokens;
        for (String piece : normalize(name).split(" ")) {
            // token de uma letra é inicial abreviada; partícula não identifica
            if (piece.length() < 2 || PARTICLES.contains(piece)) continue;
            tokens.add(piece);
        }
        return tokens;
    }

    private String normalize(String value) {
        if (value == null) return "";
        String base = ACCENTS
                .matcher(Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
                .replaceAll("");
        return SPACES.matcher(NON_WORD.matcher(base).replaceAll(" ")).replaceAll(" ").trim();
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    /**
     * @param scanned      quantos lançamentos foram examinados
     * @param marked       quantos passaram a contar como movimentação própria
     * @param hasFullName  false = o cadastro não tem nome completo, e a
     *                     varredura não tinha sinal nenhum para usar
     */
    public record Outcome(int scanned, int marked, boolean hasFullName) {
    }
}

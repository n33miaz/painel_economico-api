package br.com.economize.service.family;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.FamilyMemberRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.CounterpartyMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * O dinheiro que circula DENTRO da casa — e por que a casa contava duas vezes.
 *
 * <p>Medido na produção em 07/09/2026, depois que o EC-187 já tinha tirado as
 * movimentações de cada um entre as próprias contas: a Casa de agosto ainda
 * somava <b>R$ 7.058,28</b> de receita para um casal cuja renda de verdade no
 * mês era <b>R$ 6.021,17</b>. A diferença — R$ 1.037,11 — eram dois Pix do
 * marido para a esposa (R$ 650,00 e R$ 387,11). Sai como despesa dele, entra
 * como receita dela, e a casa lê os dois lados como dinheiro novo.
 *
 * <p><b>Não é {@code internalTransfer}.</b> Aquela marca vale para contas do
 * mesmo titular e some de TODA soma. Aqui a resposta depende de quem pergunta:
 * na tela "Eu" da esposa o dinheiro entrou mesmo e é receita dela; na tela da
 * Casa não é, porque renda da casa é o que entra de fora. Por isso a marca é
 * outra e só as consultas da casa a filtram.
 *
 * <p>O sinal é o mesmo do EC-187, apontado para o outro lado: o extrato escreve
 * a contraparte do Pix, então uma transferência que carrega o nome completo de
 * OUTRO membro da casa é dinheiro que ficou dentro dela.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyTransferService {

    private final FamilyMemberRepository memberRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    /**
     * Marca (ou desmarca) UMA linha como dinheiro que ficou dentro da casa. É a
     * decisão da pessoa e vence a heurística: a varredura nunca desfaz.
     */
    public BankTransaction setFamilyTransfer(String email, UUID transactionId, boolean familyTransfer) {
        User user = requireUser(email);
        BankTransaction tx = bankTransactionRepository
                .findByIdAndUserId(transactionId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Transação não encontrada"));
        tx.setFamilyTransfer(familyTransfer);
        return bankTransactionRepository.save(tx);
    }

    /**
     * Varre os lançamentos de quem chamou e marca as transferências cuja
     * contraparte é outra pessoa da mesma casa.
     *
     * <p>Cada um roda pela SUA conta: a API nunca escreve na conta de outro
     * usuário, nem quando ele é da mesma família. Rodar duas vezes é seguro —
     * nada é desmarcado, e linha já marcada é pulada.
     */
    public Outcome reconcile(String email) {
        User user = requireUser(email);
        FamilyMember me = memberRepository.findByUserId(user.getId()).orElse(null);
        if (me == null) {
            // Sem casa não há "dentro da casa": zero, e o app diz por quê
            return new Outcome(0, 0, 0);
        }

        List<List<String>> outros = new ArrayList<>();
        for (FamilyMember membro : memberRepository.findAllByGroupIdOrderByJoinedAtAsc(me.getGroup().getId())) {
            if (membro.getId().equals(me.getId())) continue;
            List<String> tokens = CounterpartyMatcher.nameTokens(nameOf(membro));
            // Mesmo piso do EC-187: com um token só, "Alice" casaria com
            // qualquer Alice do país e a casa esconderia receita de verdade
            if (tokens.size() < 2) continue;
            outros.add(tokens);
        }
        if (outros.isEmpty()) {
            log.info("Varredura da casa sem outro membro com nome completo, user={}", email);
            return new Outcome(0, 0, 0);
        }

        List<BankTransaction> all = bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId());
        Set<UUID> marcar = new LinkedHashSet<>();
        for (BankTransaction tx : all) {
            if (tx.isFamilyTransfer() || tx.isInternalTransfer() || tx.isIgnored()) continue;
            String normalizado = CounterpartyMatcher.normalize(tx.getDescription());
            if (normalizado.isEmpty()) continue;
            if (!CounterpartyMatcher.TRANSFER_LIKE.matcher(normalizado).find()) continue;
            if (outros.stream().noneMatch(tokens -> CounterpartyMatcher.carries(normalizado, tokens))) {
                continue;
            }
            marcar.add(tx.getId());
        }

        if (!marcar.isEmpty()) {
            bankTransactionRepository.markAsFamilyTransfer(user.getId(), marcar);
        }
        log.info("Varredura da casa: {} de {} lançamento(s) marcados, user={}",
                marcar.size(), all.size(), email);
        return new Outcome(all.size(), marcar.size(), outros.size());
    }

    /**
     * O nome do CADASTRO, que é como o banco escreve a contraparte do Pix. Nulo
     * quando o vínculo veio sem usuário carregado — a varredura simplesmente
     * ignora esse membro em vez de comparar contra nada.
     */
    private String nameOf(FamilyMember membro) {
        return membro.getUser() != null ? membro.getUser().getName() : null;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    /**
     * @param scanned quantos lançamentos foram examinados
     * @param marked  quantos passaram a ficar de fora da soma da casa
     * @param against contra quantos outros membros a varredura teve nome
     *                completo para comparar — zero explica um resultado zerado
     */
    public record Outcome(int scanned, int marked, int against) {
    }
}

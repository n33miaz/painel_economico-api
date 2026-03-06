package br.com.painel_economico.service;

import br.com.painel_economico.model.BankTransaction;
import br.com.painel_economico.model.Transaction;
import br.com.painel_economico.model.User;
import br.com.painel_economico.repository.BankTransactionRepository;
import br.com.painel_economico.repository.TransactionRepository;
import br.com.painel_economico.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiAssistantService {

    private final ChatClient chatClient;
    private final UserRepository userRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final TransactionRepository transactionRepository;

    public AiAssistantService(ChatClient.Builder chatClientBuilder,
                              UserRepository userRepository,
                              BankTransactionRepository bankTransactionRepository,
                              TransactionRepository transactionRepository) {
        this.chatClient = chatClientBuilder.build();
        this.userRepository = userRepository;
        this.bankTransactionRepository = bankTransactionRepository;
        this.transactionRepository = transactionRepository;
    }

    public Mono<String> askAssistant(String email, String userQuestion) {
        return Mono.fromCallable(() -> {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

            List<BankTransaction> bankTxs = bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId());
            List<Transaction> walletTxs = transactionRepository.findAllByUserIdOrderByTransactionDateDesc(user.getId());

            String context = buildFinancialContext(bankTxs, walletTxs);

            // Contexto AI
            String systemPromptText = """
                    Você é o assistente financeiro virtual do aplicativo 'Painel Econômico'.
                    Seu objetivo é ajudar o usuário a entender suas finanças, analisar gastos e dar dicas de investimentos.
                    Seja conciso, profissional, mas amigável. Responda em português do Brasil.
                    Use formatação Markdown para destacar valores e tópicos.

                    Aqui estão os dados financeiros atuais do usuário para contexto:
                    {context}

                    Regras:
                    - Baseie-se estritamente nos dados fornecidos.
                    - Se o usuário perguntar algo fora do escopo financeiro, recuse educadamente.
                    - Não recomende compra/venda direta de ativos específicos, apenas dê orientações gerais.
                    """;

            SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemPromptText);
            Message systemMessage = systemPromptTemplate.createMessage(Map.of("context", context));
            UserMessage userMessage = new UserMessage(userQuestion);

            Prompt prompt = new Prompt(List.of(systemMessage, userMessage));

            log.info("Enviando prompt para a IA para o usuário: {}", email);
            return chatClient.prompt(prompt).call().content();

        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildFinancialContext(List<BankTransaction> bankTxs, List<Transaction> walletTxs) {
        StringBuilder sb = new StringBuilder();

        BigDecimal totalIncome = bankTxs.stream()
                .filter(t -> "CREDIT".equals(t.getType()))
                .map(BankTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalExpense = bankTxs.stream()
                .filter(t -> "DEBIT".equals(t.getType()))
                .map(BankTransaction::getAmount)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        sb.append("--- RESUMO BANCÁRIO ---\n");
        sb.append("Total Entradas: R$ ").append(totalIncome).append("\n");
        sb.append("Total Saídas: R$ ").append(totalExpense).append("\n");
        sb.append("Últimas transações bancárias:\n");

        bankTxs.stream().limit(15).forEach(t -> {
            sb.append(String.format("- %s | %s | R$ %s\n", t.getDate().toLocalDate(), t.getDescription(), t.getAmount()));
        });

        sb.append("\n--- CARTEIRA DE INVESTIMENTOS ---\n");
        if (walletTxs.isEmpty()) {
            sb.append("O usuário não possui investimentos cadastrados.\n");
        } else {
            walletTxs.forEach(t -> {
                sb.append(String.format("- %s: %s cotas (Preço médio: R$ %s)\n", t.getAssetCode(), t.getQuantity(), t.getPriceAtTransaction()));
            });
        }

        return sb.toString();
    }
}